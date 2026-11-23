package de.df.jauswertung.gui.plugins.zeitnahme;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.text.ParseException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.sound.sampled.Clip;
import javax.sound.sampled.LineUnavailableException;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;

import org.lisasp.swing.filechooser.FileChooserUtils;
import org.lisasp.swing.filechooser.filefilter.SimpleFileFilter;

import de.df.jauswertung.gui.util.I18n;
import de.df.jutils.gui.util.DialogUtils;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class JZeitnahmeLauf extends JDialog {
    private record Bahnfelder(JComboBox<String> telefon, JButton ziel, JButton tisch, JButton erneut, JLabel zeit) { }
    private record Tonfolge(Clip countdown, Clip horn) { }

    private final ZeitnahmeSnapshot snapshot;
    private final ZeitnahmeErgebnisUebernahme uebernahme;
    private final ZeitnahmeVersuch.Sitzung sitzung = new ZeitnahmeVersuch.Sitzung();
    private final UUID starter = UUID.randomUUID();
    private final UUID telefonA = UUID.randomUUID();
    private final UUID telefonB = UUID.randomUUID();
    private final JComboBox<VirtuellerZeitnahmelauf.Auswahl> lauf = new JComboBox<>();
    private final JComboBox<String> startrolle = new JComboBox<>(new String[] { text("Tisch"), text("Starttelefon") });
    private final JSpinner dauer = new JSpinner(new SpinnerNumberModel(120, 1, 3600, 1));
    private final SpinnerNumberModel einzelhornDauer = new SpinnerNumberModel(Startsignal.STANDARD,
            Startsignal.MINIMUM, Startsignal.MAXIMUM, 100);
    private final SpinnerNumberModel countdownHornDauer = new SpinnerNumberModel(Startsignal.COUNTDOWN_STANDARD,
            Startsignal.MINIMUM, Startsignal.MAXIMUM, 100);
    private final JSpinner horn = new JSpinner(einzelhornDauer);
    private final JCheckBox countdown = new JCheckBox(text("Countdown"));
    private final JSpinner verzoegerung = new JSpinner(new SpinnerNumberModel(0, 0, 5000, 100));
    private final JCheckBox tonUnbekannt = new JCheckBox(text("TonUnbekannt"));
    private final JPanel bahnen = new JPanel(new GridLayout(0, 6, 6, 6));
    private final Map<Integer, Bahnfelder> bahnfelder = new LinkedHashMap<>();
    private final JLabel status = new JLabel();
    private final JButton probe = new JButton(text("Testton"));
    private final JButton aktualisieren = new JButton(text("Aktualisieren"));
    private final JButton scharf = new JButton(text("Scharf"));
    private final JButton start = new JButton(text("Start"));
    private final JButton startErneut = new JButton(text("StartErneut"));
    private final JButton bestaetigen = new JButton(text("Bestaetigen"));
    private final JButton verwerfen = new JButton(text("Verwerfen"));
    private final JButton neuerVersuch = new JButton(text("NeuerVersuch"));
    private final JButton ergebnisPruefen = new JButton(text("Ergebnisse"));
    private final JButton rohdaten = new JButton(text("Rohdaten"));
    private final JButton netz = new JButton(text("NetzStart"));
    private final JButton koppeln = new JButton(text("NetzKoppeln"));
    private final JLabel netzStatus = new JLabel();
    private final Timer anzeige = new Timer(200, event -> anzeigen());
    private VirtuellerZeitnahmelauf.Auswahl auswahl;
    private VirtuellerZeitnahmelauf virtuell;
    private List<VirtuellerZeitnahmelauf.Auswahl> angezeigteLaeufe = List.of();
    private boolean audioAktiv;
    private Tonfolge laufenderCountdown;
    private Timer countdownTimer;
    private boolean hornGesendet;
    private boolean tonFehler;
    private boolean auswahlAktualisiert;
    private boolean netzStartet;
    private ZeitnahmeDienst dienst;
    private String netzAdresse;

    JZeitnahmeLauf(Window parent, ZeitnahmeSnapshot snapshot, ZeitnahmeErgebnisUebernahme uebernahme) {
        super(parent, text("Titel"));
        this.snapshot = snapshot;
        this.uebernahme = uebernahme;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        JPanel einstellung = new JPanel(new GridLayout(0, 2, 6, 4));
        feld(einstellung, "Lauf", lauf);
        feld(einstellung, "Starter", startrolle);
        feld(einstellung, "Laufdauer", dauer);
        feld(einstellung, "Horndauer", horn);
        einstellung.add(countdown);
        einstellung.add(new JLabel());
        feld(einstellung, "Verzoegerung", verzoegerung);
        einstellung.add(tonUnbekannt);
        einstellung.add(probe);
        einstellung.add(netz);
        einstellung.add(koppeln);
        einstellung.add(netzStatus);
        einstellung.add(new JLabel());
        JPanel aktionen = new JPanel();
        for (JButton taste : List.of(aktualisieren, scharf, start, startErneut, bestaetigen, verwerfen, ergebnisPruefen,
                rohdaten, neuerVersuch)) {
            aktionen.add(taste);
        }
        JPanel fuss = new JPanel(new BorderLayout());
        fuss.add(status, BorderLayout.NORTH);
        fuss.add(aktionen, BorderLayout.SOUTH);
        JPanel inhalt = new JPanel(new BorderLayout(8, 12));
        inhalt.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        inhalt.add(einstellung, BorderLayout.NORTH);
        inhalt.add(new JScrollPane(bahnen), BorderLayout.CENTER);
        inhalt.add(fuss, BorderLayout.SOUTH);
        setContentPane(inhalt);
        lauf.addActionListener(event -> {
            if (!auswahlAktualisiert) {
                bahnenAnzeigen((VirtuellerZeitnahmelauf.Auswahl) lauf.getSelectedItem());
            }
        });
        probe.addActionListener(event -> tonVorbereiten(true));
        netz.addActionListener(event -> netzUmschalten());
        koppeln.addActionListener(event -> einladungAnzeigen());
        startrolle.addActionListener(event -> testtonEinstellungSenden());
        horn.addChangeListener(event -> testtonEinstellungSenden());
        countdown.addActionListener(event -> {
            horn.setModel(countdown.isSelected() ? countdownHornDauer : einzelhornDauer);
            testtonEinstellungSenden();
        });
        aktualisieren.addActionListener(event -> wettkampfGeaendert());
        scharf.addActionListener(event -> scharfSchalten());
        start.addActionListener(event -> tonVorbereiten(false));
        startErneut.addActionListener(event -> virtuell.startSenden());
        bestaetigen.addActionListener(event -> {
            if (JOptionPane.showConfirmDialog(this, text("HornGehoert"), text("Titel"),
                    JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                virtuell.versuch().startBestaetigen(Startsignal.jetzt(), true);
            }
        });
        verwerfen.addActionListener(event -> virtuell.versuch().verwerfen(Startsignal.jetzt()));
        ergebnisPruefen.addActionListener(event ->
                new JZeitnahmeErgebnisse(this, uebernahme, auswahl, virtuell.versuch()).setVisible(true));
        rohdaten.addActionListener(event -> rohdatenSpeichern());
        neuerVersuch.addActionListener(event -> {
            virtuell.versuch().schliessen();
            virtuell = null;
            if (dienst != null) {
                dienst.laufSetzen(null, 0, false);
                testtonEinstellungSenden();
            }
            hornGesendet = false;
            tonFehler = false;
            wettkampfGeaendert();
        });
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                anzeige.stop();
                if (laufenderCountdown != null) {
                    countdownTimer.stop();
                    laufenderCountdown.countdown().close();
                    laufenderCountdown.horn().close();
                    laufenderCountdown = null;
                }
                netzBeenden();
            }
        });
        wettkampfGeaendert();
        pack();
        setLocationRelativeTo(parent);
        anzeige.start();
    }

    private void netzUmschalten() {
        if (dienst != null) {
            netzBeenden();
            rollenNamenAnpassen();
            anzeigen();
            return;
        }
        var einstellungen = JZeitnahmeNetz.einstellen(this);
        if (einstellungen == null) {
            return;
        }
        netzStartet = true;
        anzeigen();
        new SwingWorker<ZeitnahmeDienst, Void>() {
            @Override
            protected ZeitnahmeDienst doInBackground() throws Exception {
                try {
                    var tls = ZeitnahmeDienst.tls(einstellungen.zertifikat(), einstellungen.passwort(), false);
                    return new ZeitnahmeDienst(einstellungen.adresse(), tls, sitzung,
                            JZeitnahmeLauf.this::telefonstartValidieren, einstellungen.tonlatenz());
                } finally {
                    Arrays.fill(einstellungen.passwort(), '\0');
                }
            }

            @Override
            protected void done() {
                try {
                    var gestartet = get();
                    if (!isDisplayable()) {
                        gestartet.close();
                        return;
                    }
                    dienst = gestartet;
                    String ip = einstellungen.adresse().getAddress().getHostAddress();
                    netzAdresse = "https://" + (ip.contains(":") ? "[" + ip + "]" : ip) + ":" + dienst.port();
                    rollenNamenAnpassen();
                    testtonEinstellungSenden();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ex) {
                    log.warn("Lokale Zeitnahme konnte nicht gestartet werden", ex.getCause());
                    JOptionPane.showMessageDialog(JZeitnahmeLauf.this, text("NetzFehler"));
                } finally {
                    netzStartet = false;
                    anzeigen();
                }
            }
        }.execute();
    }

    private void netzBeenden() {
        if (dienst != null) {
            dienst.close();
            dienst = null;
            netzAdresse = null;
        }
    }

    private boolean telefonstartValidieren(ZeitnahmeVersuch erwarteterVersuch, Runnable freigeben) {
        var pruefung = new FutureTask<>(() -> isDisplayable() && dienst != null && virtuell != null
                && virtuell.versuch() == erwarteterVersuch && snapshot.beiGueltigemLauf(auswahl, freigeben));
        SwingUtilities.invokeLater(pruefung);
        try {
            return pruefung.get(2, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            pruefung.cancel(false);
            return false;
        } catch (ExecutionException | TimeoutException ex) {
            pruefung.cancel(false);
            return false;
        }
    }

    private void testtonEinstellungSenden() {
        if (dienst != null) {
            dienst.testtonSetzen(startrolle.getSelectedIndex() == 1 ? starter : null, (int) horn.getValue(),
                    countdown.isSelected());
        }
    }

    private void rollenNamenAnpassen() {
        int rolle = startrolle.getSelectedIndex();
        startrolle.setModel(new DefaultComboBoxModel<>(
                new String[] { text("Tisch"), text(dienst == null ? "Starttelefon" : "StarttelefonNetz") }));
        startrolle.setSelectedIndex(rolle);
        for (var felder : bahnfelder.values()) {
            int modus = felder.telefon().getSelectedIndex();
            felder.telefon().setModel(new DefaultComboBoxModel<>(bahnRollen()));
            felder.telefon().setSelectedIndex(modus);
        }
    }

    private String[] bahnRollen() {
        return new String[] { text(dienst == null ? "HandyA" : "HandyANetz"),
                text(dienst == null ? "HandyB" : "HandyBNetz"), text("HandyStoppuhrA"), text("HandyStoppuhrB"),
                text("Stoppuhr") };
    }

    private void einladungAnzeigen() {
        String[] rollen = { text("StarttelefonNetz"), text("HandyANetz"), text("HandyBNetz") };
        int index = JOptionPane.showOptionDialog(this, text("NetzRolle"), text("NetzKoppeln"),
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, rollen, rollen[0]);
        if (index < 0 || dienst == null) {
            return;
        }
        String code = dienst.code(List.of(starter, telefonA, telefonB).get(index));
        if (code == null) {
            JOptionPane.showMessageDialog(this, text("NetzSchonGekoppelt"));
            return;
        }
        JTextField adresse = new JTextField(netzAdresse);
        JTextField einladung = new JTextField(code);
        adresse.setEditable(false);
        einladung.setEditable(false);
        JPanel felder = new JPanel(new GridLayout(0, 1, 4, 4));
        felder.add(new JLabel(text("NetzVertrauen")));
        felder.add(adresse);
        felder.add(new JLabel(text("NetzCode")));
        felder.add(einladung);
        JOptionPane.showMessageDialog(this, felder, text("NetzKoppeln"), JOptionPane.PLAIN_MESSAGE);
    }

    void wettkampfGeaendert() {
        if (virtuell == null) {
            laeufeAktualisieren();
        } else {
            snapshot.validieren(auswahl);
        }
        anzeigen();
    }

    private static void feld(JPanel felder, String name, JComponent feld) {
        JLabel label = new JLabel(text(name));
        label.setLabelFor(feld);
        felder.add(label);
        felder.add(feld);
    }

    private void laeufeAktualisieren() {
        if (!snapshot.aktualisieren() || angezeigteLaeufe.equals(snapshot.laeufe())) {
            return;
        }
        angezeigteLaeufe = snapshot.laeufe();
        auswahlAktualisiert = true;
        lauf.setModel(new DefaultComboBoxModel<>(angezeigteLaeufe.toArray(VirtuellerZeitnahmelauf.Auswahl[]::new)));
        if (auswahl != null && angezeigteLaeufe.contains(auswahl)) {
            lauf.setSelectedItem(auswahl);
        } else {
            bahnenAnzeigen((VirtuellerZeitnahmelauf.Auswahl) lauf.getSelectedItem());
        }
        auswahlAktualisiert = false;
    }

    private void bahnenAnzeigen(VirtuellerZeitnahmelauf.Auswahl ausgewaehlt) {
        auswahl = ausgewaehlt;
        bahnen.removeAll();
        bahnfelder.clear();
        if (auswahl != null) {
            for (var bahn : auswahl.bahnen()) {
                JComboBox<String> telefon = new JComboBox<>(bahnRollen());
                JButton ziel = new JButton(text("Ziel"));
                JButton tisch = new JButton(text("TischEingabe"));
                JButton erneut = new JButton(text("Erneut"));
                JLabel zeit = new JLabel("—");
                JLabel teilnehmer = new JLabel(I18n.get("ZeitnahmeLauf.Bahn", bahn.bahn(), bahn.name()));
                teilnehmer.setLabelFor(telefon);
                ziel.addActionListener(event -> {
                    if (telefon.getSelectedIndex() < 2) {
                        zielErfassen(bahn.bahn());
                    } else {
                        stoppuhrEingeben(bahn.bahn(), true);
                    }
                });
                tisch.addActionListener(event -> stoppuhrEingeben(bahn.bahn(), false));
                erneut.addActionListener(event -> virtuell.zielSenden(bahn.bahn()));
                bahnfelder.put(bahn.bahn(), new Bahnfelder(telefon, ziel, tisch, erneut, zeit));
                for (JComponent feld : List.of(teilnehmer, telefon, ziel, tisch, erneut, zeit)) {
                    bahnen.add(feld);
                }
            }
        }
        bahnen.revalidate();
        bahnen.repaint();
    }

    private void scharfSchalten() {
        Integer sekunden = zahl(dauer);
        Integer millisekunden = zahl(horn);
        if (sekunden == null || millisekunden == null) {
            return;
        }
        snapshot.beiGueltigemLauf(auswahl, () -> {
            Map<Integer, UUID> zuordnung = new LinkedHashMap<>();
            Set<Integer> stoppuhr = new HashSet<>();
            bahnfelder.forEach((nummer, felder) -> {
                switch (felder.telefon().getSelectedIndex()) {
                case 0, 2 -> zuordnung.put(nummer, telefonA);
                case 1, 3 -> zuordnung.put(nummer, telefonB);
                default -> { }
                }
                if (felder.telefon().getSelectedIndex() >= 2) {
                    stoppuhr.add(nummer);
                }
            });
            virtuell = new VirtuellerZeitnahmelauf(sitzung, auswahl, startrolle.getSelectedIndex() == 1 ? starter : null,
                    zuordnung, stoppuhr, sekunden * 1_000_000L, Startsignal.jetzt(), dienst == null);
            if (dienst != null) {
                dienst.laufSetzen(virtuell.versuch(), millisekunden, countdown.isSelected());
            }
        });
        anzeigen();
    }

    private void tonVorbereiten(boolean testton) {
        Integer millisekunden = zahl(horn);
        if (millisekunden == null) {
            return;
        }
        var versuch = virtuell;
        boolean mitCountdown = countdown.isSelected();
        audioAktiv = true;
        tonFehler = false;
        anzeigen();
        new SwingWorker<Tonfolge, Void>() {
            @Override
            protected Tonfolge doInBackground() throws LineUnavailableException {
                Clip signal = Startsignal.vorbereiten(millisekunden);
                try {
                    return new Tonfolge(mitCountdown ? Startsignal.countdownVorbereiten() : null, signal);
                } catch (LineUnavailableException | RuntimeException ex) {
                    signal.close();
                    throw ex;
                }
            }

            @Override
            protected void done() {
                try {
                    Tonfolge toene = get();
                    if (toene.countdown() != null) {
                        if (isDisplayable() && (testton || !snapshot.veraltet()) && testtonStarten(toene.countdown())) {
                            laufenderCountdown = toene;
                            countdownTimer = new Timer(Startsignal.COUNTDOWN_MS, event -> {
                                laufenderCountdown = null;
                                horntonStarten(toene.horn(), testton, versuch, millisekunden);
                            });
                            countdownTimer.setRepeats(false);
                            countdownTimer.start();
                            anzeigen();
                            return;
                        }
                        toene.countdown().close();
                        toene.horn().close();
                    } else {
                        horntonStarten(toene.horn(), testton, versuch, millisekunden);
                        return;
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    tonFehler = true;
                } catch (ExecutionException ex) {
                    log.warn("Startsignal konnte nicht vorbereitet werden", ex.getCause());
                    tonFehler = true;
                }
                audioAktiv = false;
                anzeigen();
            }
        }.execute();
    }

    private void horntonStarten(Clip clip, boolean testton, VirtuellerZeitnahmelauf versuch, int millisekunden) {
        boolean gestartet = false;
        try {
            gestartet = isDisplayable() && (testton ? testtonStarten(clip) : laufStarten(clip, versuch));
            if (gestartet) {
                spaeter(millisekunden, () -> audioAktiv = false);
            }
        } finally {
            if (!gestartet) {
                clip.close();
                audioAktiv = false;
            }
            anzeigen();
        }
    }

    private boolean testtonStarten(Clip clip) {
        try {
            Startsignal.abspielen(clip, zeit -> true);
            return true;
        } catch (LineUnavailableException | RuntimeException ex) {
            log.warn("Testton konnte nicht abgespielt werden", ex);
            tonFehler = true;
            return false;
        }
    }

    private boolean laufStarten(Clip clip, VirtuellerZeitnahmelauf laufversuch) {
        if (!snapshot.beiGueltigemLauf(auswahl, () -> {
            var versuch = laufversuch.versuch();
            boolean telefonstart = versuch.laufstand().starttelefon() != null;
            long jetzt = Startsignal.jetzt();
            var freigabe = telefonstart ? laufversuch.startAnfordern(jetzt) : null;
            if (telefonstart ? freigabe == null : !versuch.tischstartReservieren(jetzt)) {
                return;
            }
            try {
                long signalbeginn = Startsignal.abspielen(clip, zeit -> freigabe == null || zeit < freigabe.frist());
                hornGesendet = true;
                if (freigabe == null) {
                    versuch.startErfassen(new ZeitnahmeEreignis.Start(UUID.randomUUID(), versuch.id(), null, signalbeginn, null));
                } else {
                    laufversuch.startErfassen(freigabe, signalbeginn, tonUnbekannt.isSelected()
                            ? ZeitnahmeVersuch.Audio.UNBEKANNT : ZeitnahmeVersuch.Audio.PUENKTLICH);
                    spaeter((int) verzoegerung.getValue(), laufversuch::startSenden);
                }
            } catch (LineUnavailableException | RuntimeException ex) {
                log.warn("Startsignal konnte nicht abgespielt werden", ex);
                tonFehler = true;
                if (freigabe == null) {
                    versuch.tischstartAbbrechen();
                } else {
                    laufversuch.startErfassen(freigabe, null, ZeitnahmeVersuch.Audio.AUSGEFALLEN);
                    laufversuch.startSenden();
                }
            }
        })) {
            return false;
        }
        return hornGesendet;
    }

    private void zielErfassen(int bahn) {
        var laufversuch = virtuell;
        laufversuch.zielErfassen(bahn, Startsignal.jetzt());
        spaeter((int) verzoegerung.getValue(), () -> laufversuch.zielSenden(bahn));
        anzeigen();
    }

    private void stoppuhrEingeben(int bahn, boolean handy) {
        String eingabe = JOptionPane.showInputDialog(this, I18n.get("ZeitnahmeLauf.StoppuhrFormat", bahn),
                text(handy ? "HandyEingabe" : "TischEingabe"), JOptionPane.QUESTION_MESSAGE);
        if (eingabe == null) {
            return;
        }
        var dauer = ManuelleZeitnahme.dauer(eingabe);
        if (dauer.isEmpty()) {
            JOptionPane.showMessageDialog(this, text("UngueltigeZeit"), text("Titel"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        var laufversuch = virtuell;
        if (handy) {
            laufversuch.stoppuhrAmHandy(bahn, dauer.getAsLong());
            spaeter((int) verzoegerung.getValue(), () -> laufversuch.zielSenden(bahn));
        } else if (!laufversuch.stoppuhrAmTisch(bahn, dauer.getAsLong())) {
            JOptionPane.showMessageDialog(this, text("ZeitAbgelehnt"), text("Titel"), JOptionPane.WARNING_MESSAGE);
        }
        anzeigen();
    }

    private void spaeter(int millis, Runnable aktion) {
        Timer timer = new Timer(millis, event -> {
            if (isDisplayable()) {
                aktion.run();
                anzeigen();
            }
        });
        timer.setRepeats(false);
        timer.start();
    }

    private void anzeigen() {
        if (snapshot.veraltet()) {
            if (virtuell == null) {
                laeufeAktualisieren();
            } else {
                snapshot.validieren(auswahl);
            }
        }
        long jetzt = Startsignal.jetzt();
        var versuch = virtuell == null ? null : virtuell.versuch();
        if (versuch != null) {
            versuch.fristPruefen(jetzt);
        }
        var zustand = versuch == null ? null : versuch.zustand();
        boolean einstellbar = versuch == null && !audioAktiv && !netzStartet;
        boolean offen = versuch != null && !versuch.geschlossen();
        boolean netzbetrieb = dienst != null;
        boolean netzStarter = netzbetrieb && startrolle.getSelectedIndex() == 1;
        boolean erfasstStart = hornGesendet || zustand == ZeitnahmeVersuch.Zustand.LAEUFT
                || zustand == ZeitnahmeVersuch.Zustand.START_UNSICHER;
        boolean bereit = versuch != null && versuch.bereit(jetzt);
        lauf.setEnabled(einstellbar);
        startrolle.setEnabled(einstellbar);
        dauer.setEnabled(einstellbar);
        horn.setEnabled(einstellbar);
        countdown.setEnabled(einstellbar);
        tonUnbekannt.setEnabled(einstellbar && !netzbetrieb && startrolle.getSelectedIndex() == 1);
        probe.setEnabled(einstellbar && !netzStarter);
        netz.setEnabled(einstellbar);
        netz.setText(text(netzbetrieb ? "NetzStop" : "NetzStart"));
        koppeln.setEnabled(netzbetrieb);
        netzStatus.setText(netzbetrieb ? netzAdresse : "");
        scharf.setEnabled(einstellbar && auswahl != null && !snapshot.veraltet());
        start.setEnabled(bereit && !netzStarter && !snapshot.veraltet() && !audioAktiv);
        startErneut.setEnabled(!netzbetrieb && offen && hornGesendet && versuch.laufstand().starttelefon() != null);
        bestaetigen.setEnabled(offen && versuch.bestaetigungMoeglich(jetzt));
        verwerfen.setEnabled(offen && versuch.aufloesungMoeglich(jetzt));
        neuerVersuch.setEnabled(versuch != null && !audioAktiv && (versuch.geschlossen()
                || zustand == ZeitnahmeVersuch.Zustand.BEREIT || zustand == ZeitnahmeVersuch.Zustand.LAEUFT));
        List<ZeitnahmeVersuch.Ergebnis> ergebnisse = versuch == null ? List.of() : versuch.ergebnisse();
        ergebnisPruefen.setEnabled(offen && erfasstStart);
        rohdaten.setEnabled(versuch != null);
        bahnfelder.forEach((nummer, felder) -> {
            boolean erfasst = virtuell != null && virtuell.zielVorhanden(nummer);
            int modus = felder.telefon().getSelectedIndex();
            felder.telefon().setEnabled(einstellbar);
            felder.ziel().setText(text(modus < 2 ? "Ziel" : "HandyEingabe"));
            felder.ziel().setEnabled(!netzbetrieb && offen && hornGesendet && !erfasst && modus < 4);
            felder.tisch().setEnabled(offen && erfasstStart && modus >= 2);
            felder.erneut().setEnabled(!netzbetrieb && offen && erfasst);
            String wert = ergebnisse.stream().filter(ergebnis -> ergebnis.bahn() == nummer).findFirst()
                    .map(ergebnis -> (ergebnis.dauer() > 0 ? zeit(ergebnis.dauer()) : text("UngueltigeZeit"))
                            + (ergebnis.unsicher() ? " ⚠" : ""))
                    .orElse(erfasst ? text("Ausstehend") : "—");
            felder.zeit().setText(wert);
        });
        status.setText(snapshot.veraltet() ? text("Veraltet") : tonFehler ? text("TonFehler")
                : laufenderCountdown != null ? text("CountdownAktiv")
                : zustand == null ? text(auswahl == null ? "KeinLauf" : "Vorbereiten")
                : zustand == ZeitnahmeVersuch.Zustand.BEREIT && !bereit ? text("NetzWartet") : text(zustand.name()));
    }

    private void rohdatenSpeichern() {
        String csv = virtuell.versuch().rohdatenCsv();
        String datei = FileChooserUtils.saveFile(this, new SimpleFileFilter(text("RohdatenDatei"), "csv"));
        if (datei == null) return;
        Path ziel = Path.of(datei);
        if (Files.exists(ziel) && !DialogUtils.ask(this, I18n.get("OverwriteFileQuestion", datei),
                I18n.get("OverwriteFileQuestion.Note", datei))) return;
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws IOException {
                Files.writeString(ziel, csv);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ex) {
                    log.warn("Zeitnahme-Rohdaten konnten nicht gespeichert werden", ex.getCause());
                    JOptionPane.showMessageDialog(JZeitnahmeLauf.this, text("RohdatenFehler"), text("Titel"),
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private Integer zahl(JSpinner feld) {
        try {
            feld.commitEdit();
            return (Integer) feld.getValue();
        } catch (ParseException ex) {
            JOptionPane.showMessageDialog(this, text("UngueltigeZahl"), text("Titel"), JOptionPane.WARNING_MESSAGE);
            return null;
        }
    }

    static String text(String name) {
        return I18n.get("ZeitnahmeLauf." + name);
    }

    static String zeit(long dauer) {
        return I18n.get("ZeitnahmeLauf.Zeit", NumberFormat.getNumberInstance().format(dauer / 1_000_000.0));
    }
}
