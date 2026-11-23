package de.df.jauswertung.zeitnahme;

import static de.df.jauswertung.zeitnahme.ZeitnahmeTexte.text;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.IntStream;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;

public final class ZeitnahmeTisch extends JFrame {
    private static final Logger LOG = Logger.getLogger(ZeitnahmeTisch.class.getName());

    private final List<UUID> handys = IntStream.range(0, 8).mapToObj(index -> UUID.randomUUID()).toList();
    private final UUID wettkampf = UUID.randomUUID();
    private final ZeitnahmeVersuch.Sitzung sitzung = new ZeitnahmeVersuch.Sitzung();
    private final ZeitnahmeAufstellung aufstellung = new ZeitnahmeAufstellung(handys);
    private final JLabel stand = new JLabel();
    private final JLabel meldung = new JLabel(" ");
    private final JTextArea ergebnisse = new JTextArea(12, 32);
    private final JButton netzStart = knopf("NetzStart", this::netzStarten);
    private final JButton koppeln = knopf("Koppeln", this::koppeln);
    private final JButton scharf = knopf("Scharf", this::scharfSchalten);
    private final JButton testton = knopf("Testton", () -> ton(true));
    private final JButton start = knopf("Start", () -> ton(false));
    private final JButton eingabe = knopf("Eingabe", this::stoppuhr);
    private final JButton export = knopf("Export", this::exportieren);
    private final JButton ende = knopf("Ende", this::laufBeenden);
    private final Timer anzeige = new Timer(250, event -> aktualisieren());

    private ZeitnahmeDienst dienst;
    private ZeitnahmeVersuch versuch;
    private ZeitnahmeAufstellung.Lauf lauf;
    private String adresse;
    private boolean arbeitet;
    private boolean exportiert;

    public static void main(String[] argumente) {
        SwingUtilities.invokeLater(() -> new ZeitnahmeTisch().setVisible(true));
    }

    ZeitnahmeTisch() {
        super(text("Titel"));
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        var inhalt = new JPanel(new BorderLayout(10, 10));
        inhalt.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        inhalt.add(aufstellung, BorderLayout.WEST);
        ergebnisse.setEditable(false);
        ergebnisse.setFocusable(false);
        inhalt.add(new JScrollPane(ergebnisse), BorderLayout.CENTER);
        var aktionen = new JPanel(new GridLayout(0, 4, 5, 5));
        for (var knopf : List.of(netzStart, koppeln, scharf, testton, start, eingabe, export, ende)) {
            aktionen.add(knopf);
        }
        var unten = new JPanel(new BorderLayout(4, 4));
        unten.add(stand, BorderLayout.NORTH);
        unten.add(aktionen, BorderLayout.CENTER);
        unten.add(meldung, BorderLayout.SOUTH);
        inhalt.add(unten, BorderLayout.SOUTH);
        setContentPane(inhalt);
        pack();
        setMinimumSize(getSize());
        setLocationByPlatform(true);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                schliessen();
            }
        });
        aktualisieren();
        anzeige.start();
    }

    private void netzStarten() {
        var einstellungen = JZeitnahmeNetz.einstellen(this);
        if (einstellungen == null) {
            return;
        }
        imHintergrund(() -> {
            try {
                var tls = ZeitnahmeDienst.tls(einstellungen.zertifikat(), einstellungen.passwort(), false);
                return new ZeitnahmeDienst(einstellungen.adresse(), tls, sitzung, (laufversuch, startaktion) -> false,
                        einstellungen.tonlatenz());
            } finally {
                Arrays.fill(einstellungen.passwort(), '\0');
            }
        }, gestartet -> {
            dienst = gestartet;
            try {
                adresse = new URI("https", null, einstellungen.adresse().getAddress().getHostAddress(),
                        dienst.port(), null, null, null).toASCIIString();
            } catch (URISyntaxException ex) {
                dienst.close();
                dienst = null;
                adresse = null;
                fehler("NetzFehler", ex);
                return;
            }
            meldung.setText(text("NetzAktiv", adresse));
        }, ex -> fehler("NetzFehler", ex));
    }

    private void koppeln() {
        int rolle = auswahl(aufstellung.handynamen(), "Koppeln");
        if (rolle < 0) {
            return;
        }
        String code = dienst.code(handys.get(rolle));
        if (code == null) {
            hinweis("Gekoppelt");
        } else {
            var anzeigeCode = new JTextArea(text("Code", adresse, code));
            anzeigeCode.setEditable(false);
            JOptionPane.showMessageDialog(this, anzeigeCode, text("Koppeln"), JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void scharfSchalten() {
        try {
            var eingestellterLauf = aufstellung.lesen();
            if (dienst == null && eingestellterLauf.bahnen().stream().anyMatch(bahn -> bahn.telefon() != null)) {
                hinweis("Konfiguration");
                return;
            }
            lauf = eingestellterLauf;
            var laufstand = new ZeitnahmeVersuch.Laufstand(wettkampf, UUID.randomUUID(), lauf.disziplin(), lauf.bahnen(), null);
            versuch = sitzung.anlegen(laufstand, lauf.sekunden() * 1_000_000L);
            if (dienst != null) {
                dienst.laufSetzen(versuch, lauf.horn(), lauf.countdown());
            }
            exportiert = false;
            meldung.setText(" ");
            aktualisieren();
        } catch (ParseException | IllegalArgumentException ex) {
            hinweis("Konfiguration");
        }
    }

    private void ton(boolean probe) {
        try {
            int millis = versuch == null ? aufstellung.horndauer() : lauf.horn();
            boolean countdown = versuch == null ? aufstellung.countdown() : lauf.countdown();
            var laufversuch = versuch;
            imHintergrund(() -> {
                try (var horn = Startsignal.vorbereiten(millis)) {
                    if (countdown) {
                        try (var vorlauf = Startsignal.countdownVorbereiten()) {
                            Startsignal.abspielen(vorlauf, zeit -> true);
                            Thread.sleep(Startsignal.COUNTDOWN_MS);
                        }
                    }
                    if (probe) {
                        Startsignal.abspielen(horn, zeit -> true);
                    } else {
                        startAbspielen(laufversuch, horn);
                    }
                    Thread.sleep(millis);
                }
                return probe;
            }, istProbe -> {
                if (istProbe) {
                    meldung.setText(text("TesttonFertig"));
                }
            }, ex -> fehler("AudioFehler", ex));
        } catch (ParseException ex) {
            hinweis("Konfiguration");
        }
    }

    private static void startAbspielen(ZeitnahmeVersuch laufversuch, javax.sound.sampled.Clip horn)
            throws javax.sound.sampled.LineUnavailableException {
        synchronized (laufversuch) {
            if (!laufversuch.tischstartReservieren(Startsignal.jetzt())) {
                throw new IllegalStateException();
            }
            try {
                long beginn = Startsignal.abspielen(horn, zeit -> true);
                var start = new ZeitnahmeEreignis.Start(UUID.randomUUID(), laufversuch.id(), null, beginn, null);
                if (!laufversuch.startErfassen(start)) {
                    throw new IllegalStateException();
                }
            } catch (javax.sound.sampled.LineUnavailableException | RuntimeException ex) {
                laufversuch.tischstartAbbrechen();
                throw ex;
            }
        }
    }

    private void stoppuhr() {
        var bahnen = lauf.bahnen().stream().filter(bahn -> !bahn.telefonzeit()).toList();
        int index = auswahl(bahnen.stream().map(ZeitnahmeVersuch.Bahn::teilnehmer).toList(), "Eingabe");
        if (index < 0) {
            return;
        }
        int bahn = bahnen.get(index).nummer();
        String eingabeZeit = JOptionPane.showInputDialog(this, text("EingabeBahn", bahn));
        if (eingabeZeit == null) {
            return;
        }
        var dauer = ManuelleZeitnahme.dauer(eingabeZeit);
        if (dauer.isEmpty() || !versuch.stoppuhrErfassen(
                new ZeitnahmeEreignis.Stoppuhr(UUID.randomUUID(), versuch.id(), null, bahn, dauer.getAsLong()))) {
            hinweis("UngueltigeZeit");
        }
        aktualisieren();
    }

    private void exportieren() {
        var zeiten = versuch.ergebnisse();
        if (zeiten.size() != lauf.bahnen().size() || zeiten.stream().anyMatch(zeit -> !exportierbar(zeit))) {
            hinweis("ExportFehlt");
            return;
        }
        if (!bestaetigen(text("ExportPruefen", lauf.wettbewerb(), lauf.nummer(), ergebnisText(zeiten)))) {
            return;
        }
        if (zeiten.stream().anyMatch(ZeitnahmeVersuch.Ergebnis::unsicher) && !bestaetigen(text("UnsicherHinweis"))) {
            return;
        }
        var auswahl = new JFileChooser();
        auswahl.setSelectedFile(Path.of("event-" + lauf.wettbewerb() + "-lauf-" + lauf.nummer() + ".txt").toFile());
        if (auswahl.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path datei = auswahl.getSelectedFile().toPath();
        int wettbewerb = lauf.wettbewerb();
        int laufnummer = lauf.nummer();
        var laufversuch = versuch;
        imHintergrund(() -> {
            AresExport.speichern(datei, wettbewerb, laufnummer, laufversuch, zeiten);
            return datei;
        }, gespeichert -> {
            exportiert = true;
            meldung.setText(text("ExportFertig", gespeichert.getFileName()));
        }, ex -> fehler(ex instanceof IOException ? "ExportFehler" : "ExportGeaendert", ex));
    }

    private void laufBeenden() {
        if (versuch != null && (exportiert || bestaetigen(text("EndePruefen")))) {
            versuch.schliessen();
            if (dienst != null) {
                dienst.laufSetzen(null, 0, false);
            }
            versuch = null;
            lauf = null;
            meldung.setText(" ");
            aktualisieren();
        }
    }

    private void schliessen() {
        if (arbeitet || versuch != null && !exportiert && !bestaetigen(text("SchliessenPruefen"))) {
            return;
        }
        anzeige.stop();
        if (dienst != null) {
            dienst.close();
        }
        dispose();
    }

    private void aktualisieren() {
        boolean aktiv = versuch != null;
        boolean laeuft = aktiv && versuch.zustand() == ZeitnahmeVersuch.Zustand.LAEUFT;
        boolean bereit = aktiv && versuch.zustand() == ZeitnahmeVersuch.Zustand.BEREIT
                && versuch.bereit(Startsignal.jetzt()) && lauf.bahnen().stream().allMatch(
                        bahn -> bahn.telefon() == null || dienst != null && dienst.gekoppelt(bahn.telefon()));
        netzStart.setEnabled(!arbeitet && !aktiv && dienst == null);
        koppeln.setEnabled(!arbeitet && dienst != null);
        scharf.setEnabled(!arbeitet && !aktiv);
        testton.setEnabled(!arbeitet);
        start.setEnabled(!arbeitet && bereit);
        eingabe.setEnabled(!arbeitet && laeuft && lauf.bahnen().stream().anyMatch(bahn -> !bahn.telefonzeit()));
        export.setEnabled(!arbeitet && (laeuft || exportiert && aktiv));
        ende.setEnabled(!arbeitet && aktiv);
        aufstellung.bearbeitbar(!arbeitet && !aktiv);
        String schluessel = !aktiv ? "Einrichten" : versuch.geschlossen() ? "Abschluss"
                : laeuft ? "Erfasst" : bereit ? "Bereit" : "Warten";
        stand.setText(text(schluessel));
        String neu = aktiv ? ergebnisText(versuch.ergebnisse()) : "";
        if (!ergebnisse.getText().equals(neu)) {
            ergebnisse.setText(neu);
        }
    }

    private String ergebnisText(List<ZeitnahmeVersuch.Ergebnis> zeiten) {
        var liste = new StringBuilder();
        for (var bahn : lauf.bahnen()) {
            var zeit = zeiten.stream().filter(ergebnis -> ergebnis.bahn() == bahn.nummer()).findFirst();
            liste.append(bahn.teilnehmer()).append("   ").append(zeit.map(ZeitnahmeTisch::formatieren).orElseGet(() -> text("ZeitFehlt")))
                    .append('\n');
        }
        return liste.toString();
    }

    private static String formatieren(ZeitnahmeVersuch.Ergebnis ergebnis) {
        if (!exportierbar(ergebnis)) {
            return text("UngueltigeZeit");
        }
        long hundertstel = AresExport.hundertstel(ergebnis.dauer());
        String quelle = switch (ergebnis.quelle()) {
            case ZIEL -> "Ziel";
            case STOPPUHR_HANDY -> "HandzeitHandy";
            case STOPPUHR_TISCH -> "HandzeitTisch";
        };
        return "%d:%02d,%02d · %s%s".formatted(hundertstel / 6000, hundertstel / 100 % 60, hundertstel % 100,
                text(quelle), ergebnis.unsicher() ? " · " + text("Unsicher") : "");
    }

    private static boolean exportierbar(ZeitnahmeVersuch.Ergebnis zeit) {
        try {
            AresExport.hundertstel(zeit.dauer());
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private <T> void imHintergrund(java.util.concurrent.Callable<T> aufgabe,
            java.util.function.Consumer<T> erfolg, java.util.function.Consumer<Throwable> fehler) {
        arbeitet = true;
        aktualisieren();
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return aufgabe.call();
            }

            @Override
            protected void done() {
                arbeitet = false;
                try {
                    erfolg.accept(get());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    fehler.accept(ex);
                } catch (ExecutionException ex) {
                    fehler.accept(ex.getCause());
                } finally {
                    aktualisieren();
                }
            }
        }.execute();
    }

    private int auswahl(List<String> namen, String titel) {
        Object ausgewaehlt = JOptionPane.showInputDialog(this, text(titel), text("Titel"), JOptionPane.QUESTION_MESSAGE,
                null, namen.toArray(), namen.getFirst());
        return ausgewaehlt == null ? -1 : namen.indexOf(ausgewaehlt);
    }

    private boolean bestaetigen(String frage) {
        return JOptionPane.showConfirmDialog(this, frage, text("Titel"), JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
    }

    private void fehler(String schluessel, Throwable fehler) {
        LOG.log(Level.WARNING, schluessel, fehler);
        hinweis(schluessel);
    }

    private void hinweis(String schluessel) {
        JOptionPane.showMessageDialog(this, text(schluessel), text("Titel"), JOptionPane.WARNING_MESSAGE);
    }

    private static JButton knopf(String schluessel, Runnable aktion) {
        JButton knopf = new JButton(text(schluessel));
        knopf.addActionListener(event -> aktion.run());
        return knopf;
    }
}
