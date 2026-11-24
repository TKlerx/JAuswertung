package de.df.jauswertung.gui.plugins.zeitnahme;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.awt.Window;
import java.text.NumberFormat;
import java.text.ParseException;
import java.util.concurrent.ExecutionException;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;

import de.df.jauswertung.gui.util.I18n;
import de.df.jauswertung.gui.plugins.WettkampfZugriff;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class JZeitnahmeSimulator extends JDialog {
    private static final String I18N = "ZeitnahmeSimulator.";

    private final JPanel felder = new JPanel(new GridLayout(0, 2, 8, 4));
    private final JSpinner laufzeit = zahl("Laufzeit", 2, 1, 100);
    private final JSpinner jitter = zahl("Jitter", 8, 0, 200);
    private final JSpinner asymmetrie = zahl("Asymmetrie", 0, 0, 200);
    private final JCheckBox netzwechsel = auswahl("Netzwechsel");
    private final JSpinner neueLaufzeit = zahl("NeueLaufzeit", 2, 1, 100);
    private final JSpinner neuerJitter = zahl("NeuerJitter", 30, 0, 200);
    private final JSpinner neueAsymmetrie = zahl("NeueAsymmetrie", 0, 0, 200);
    private final JCheckBox neuverbinden = auswahl("Neuverbindung");
    private final JCheckBox telefonstart = auswahl("Telefonstart");
    private final JCheckBox stoppuhren = auswahl("Stoppuhren");
    private final JSpinner drift = zahl("Drift", 20, 0, 1_000);
    private final JSpinner intervall = zahl("Intervall", 250, 10, 30_000);
    private final JSpinner timeout = zahl("Timeout", 15, 1, 60);
    private final JSpinner laufdauer = zahl("Laufdauer", 60, 1, 900);
    private final JSpinner startwert = zahl("Startwert", 1, 0, Integer.MAX_VALUE);
    private final JSpinner anzahl = zahl("Anzahl", 100, 1, 1_000);
    private final JButton simulieren = new JButton(I18n.get(I18N + "Simulieren"));
    private final JTextArea ergebnis = new JTextArea(24, 48);
    private final NumberFormat format = NumberFormat.getNumberInstance();

    JZeitnahmeSimulator(Window parent) {
        super(parent, I18n.get(I18N + "Titel"));
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        format.setMaximumFractionDigits(3);
        telefonstart.setSelected(true);
        netzwechsel.addItemListener(event -> netzfelderAktualisieren());
        netzfelderAktualisieren();
        ergebnis.setEditable(false);
        ergebnis.setLineWrap(true);
        ergebnis.setWrapStyleWord(true);
        ergebnis.getAccessibleContext().setAccessibleName(I18n.get(I18N + "Ergebnis"));
        simulieren.addActionListener(event -> simulieren());

        JPanel eingabe = new JPanel(new BorderLayout(0, 8));
        eingabe.add(felder, BorderLayout.CENTER);
        JPanel aktionen = new JPanel(new GridLayout(0, 1, 0, 4));
        aktionen.add(simulieren);
        JButton probelauf = new JButton(JZeitnahmeLauf.text("Probelauf"));
        probelauf.addActionListener(event -> probelaufOeffnen());
        aktionen.add(probelauf);
        eingabe.add(aktionen, BorderLayout.SOUTH);
        JPanel inhalt = new JPanel(new BorderLayout(12, 0));
        inhalt.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        inhalt.add(eingabe, BorderLayout.WEST);
        inhalt.add(new JScrollPane(ergebnis), BorderLayout.CENTER);
        setContentPane(inhalt);
        getRootPane().setDefaultButton(simulieren);
        pack();
        setLocationRelativeTo(parent);
    }

    private void probelaufOeffnen() {
        var probe = ZeitnahmeLaufquelle.probewettkampf();
        var quelle = new ZeitnahmeLaufquelle(() -> probe);
        var zugriff = new WettkampfZugriff();
        var uebernahme = new ZeitnahmeErgebnisUebernahme(zugriff::versuchen, quelle::lauf, () -> {});
        new JZeitnahmeLauf(this, new ZeitnahmeSnapshot(zugriff::versuchen, quelle::laeufe), uebernahme).setVisible(true);
    }

    private JSpinner zahl(String name, int wert, int min, int max) {
        JSpinner feld = new JSpinner(new SpinnerNumberModel(wert, min, max, 1));
        JLabel label = new JLabel(I18n.get(I18N + name));
        label.setLabelFor(feld);
        felder.add(label);
        felder.add(feld);
        return feld;
    }

    private JCheckBox auswahl(String name) {
        JCheckBox feld = new JCheckBox();
        JLabel label = new JLabel(I18n.get(I18N + name));
        label.setLabelFor(feld);
        felder.add(label);
        felder.add(feld);
        return feld;
    }

    private void netzfelderAktualisieren() {
        boolean aktiv = netzwechsel.isEnabled() && netzwechsel.isSelected();
        neueLaufzeit.setEnabled(aktiv);
        neuerJitter.setEnabled(aktiv);
        neueAsymmetrie.setEnabled(aktiv);
        neuverbinden.setEnabled(aktiv);
    }

    private void simulieren() {
        ZeitnahmeSimulator.Einstellungen einstellungen;
        int laeufe;
        int seed;
        try {
            var vorher = new ZeitnahmeSimulator.Netz(wert(laufzeit) * 1_000, wert(jitter) * 1_000, wert(asymmetrie) * 1_000);
            var nachher = netzwechsel.isSelected()
                    ? new ZeitnahmeSimulator.Netz(wert(neueLaufzeit) * 1_000, wert(neuerJitter) * 1_000, wert(neueAsymmetrie) * 1_000)
                    : vorher;
            einstellungen = new ZeitnahmeSimulator.Einstellungen(vorher, nachher,
                    netzwechsel.isSelected() && neuverbinden.isSelected(), telefonstart.isSelected(), stoppuhren.isSelected(),
                    wert(drift), wert(intervall) * 1_000L, wert(timeout) * 1_000_000L, wert(laufdauer) * 1_000_000L);
            laeufe = wert(anzahl);
            seed = wert(startwert);
        } catch (ParseException ex) {
            ergebnis.setText(I18n.get(I18N + "UngueltigeEingabe"));
            return;
        }
        eingabeAktivieren(false);
        ergebnis.setText(I18n.get(I18N + "Laeuft"));
        new SwingWorker<ZeitnahmeSimulator.Serie, Void>() {
            @Override
            protected ZeitnahmeSimulator.Serie doInBackground() {
                return ZeitnahmeSimulator.simulieren(einstellungen, seed, laeufe);
            }

            @Override
            protected void done() {
                try {
                    anzeigen(get(), laeufe, einstellungen.stoppuhren());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    ergebnis.setText(I18n.get(I18N + "Fehler"));
                } catch (ExecutionException ex) {
                    log.error("Zeitnahme-Simulation fehlgeschlagen", ex.getCause());
                    ergebnis.setText(I18n.get(I18N + "Fehler"));
                } finally {
                    eingabeAktivieren(true);
                }
            }
        }.execute();
    }

    private void eingabeAktivieren(boolean aktiv) {
        simulieren.setEnabled(aktiv);
        for (var feld : felder.getComponents()) {
            feld.setEnabled(aktiv);
        }
        netzfelderAktualisieren();
    }

    private void anzeigen(ZeitnahmeSimulator.Serie serie, int laeufe, boolean manuell) {
        String nichtVerfuegbar = I18n.get(I18N + "NichtVerfuegbar");
        StringBuilder text = new StringBuilder()
                .append(I18n.get(I18N + "Statistik", serie.bereit(), laeufe,
                        format.format(100.0 * serie.bereit() / laeufe), serie.timeouts()))
                .append('\n').append(I18n.get(I18N + "Wartezeit",
                        serie.bereit() == 0 ? nichtVerfuegbar : millisekunden(serie.median()),
                        serie.bereit() == 0 ? nichtVerfuegbar : millisekunden(serie.p95())))
                .append('\n').append(I18n.get(I18N + "Maximalfehler",
                        manuell || serie.bereit() == 0 ? nichtVerfuegbar : millisekunden(serie.groessterFehler())))
                .append("\n\n").append(I18n.get(I18N + "ErsterLauf")).append('\n');
        for (var status : serie.ersterLauf().verlauf()) {
            text.append(millisekunden(status.zeit())).append("  ")
                    .append(I18n.get(I18N + "Status." + status.text().name())).append('\n');
        }
        var fehler = serie.ersterLauf().bahnfehler();
        for (int i = 0; i < fehler.size(); i++) {
            text.append(I18n.get(I18N + "Bahnfehler", i + 1, millisekunden(fehler.get(i)))).append('\n');
        }
        ergebnis.setText(text.toString());
        ergebnis.setCaretPosition(0);
    }

    private String millisekunden(long mikrosekunden) {
        return I18n.get(I18N + "Millisekunden", format.format(mikrosekunden / 1_000.0));
    }

    private static int wert(JSpinner feld) throws ParseException {
        feld.commitEdit();
        return ((Number) feld.getValue()).intValue();
    }
}
