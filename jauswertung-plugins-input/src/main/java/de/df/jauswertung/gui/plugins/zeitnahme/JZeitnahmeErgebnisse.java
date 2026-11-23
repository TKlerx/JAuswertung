package de.df.jauswertung.gui.plugins.zeitnahme;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

import de.df.jauswertung.gui.plugins.zeitnahme.VirtuellerZeitnahmelauf.Auswahl;
import de.df.jauswertung.gui.plugins.zeitnahme.ZeitnahmeErgebnisUebernahme.Status;
import de.df.jauswertung.gui.plugins.zeitnahme.ZeitnahmeErgebnisUebernahme.Zeile;
import de.df.jauswertung.gui.util.I18n;
import de.df.jutils.util.StringTools;

final class JZeitnahmeErgebnisse extends JDialog {
    private final ZeitnahmeErgebnisUebernahme uebernahme;
    private final Auswahl auswahl;
    private final ZeitnahmeVersuch versuch;
    private final JPanel zeilen = new JPanel(new GridLayout(0, 5, 10, 5));
    private final Map<Zeile, JCheckBox> auswahlfelder = new LinkedHashMap<>();
    private final JLabel status = new JLabel();
    private final JButton anwenden = new JButton(JZeitnahmeLauf.text("Uebernehmen"));
    private final JButton neuLaden = new JButton(JZeitnahmeLauf.text("PruefungAktualisieren"));

    JZeitnahmeErgebnisse(JZeitnahmeLauf parent, ZeitnahmeErgebnisUebernahme uebernahme, Auswahl auswahl,
            ZeitnahmeVersuch versuch) {
        super(parent, JZeitnahmeLauf.text("Ergebnisse"), ModalityType.DOCUMENT_MODAL);
        this.uebernahme = uebernahme;
        this.auswahl = auswahl;
        this.versuch = versuch;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        JPanel aktionen = new JPanel();
        aktionen.add(neuLaden);
        aktionen.add(anwenden);
        JPanel fuss = new JPanel(new BorderLayout());
        fuss.add(status, BorderLayout.NORTH);
        fuss.add(aktionen, BorderLayout.SOUTH);
        JPanel inhalt = new JPanel(new BorderLayout(8, 10));
        inhalt.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        inhalt.add(new JLabel(auswahl.name()), BorderLayout.NORTH);
        inhalt.add(new JScrollPane(zeilen), BorderLayout.CENTER);
        inhalt.add(fuss, BorderLayout.SOUTH);
        setContentPane(inhalt);
        neuLaden.addActionListener(event -> aktualisieren());
        anwenden.addActionListener(event -> uebernehmen());
        anwenden.setEnabled(false);
        aktualisieren();
        pack();
        setLocationRelativeTo(parent);
    }

    private void aktualisieren() {
        var vorschau = uebernahme.pruefen(auswahl, versuch);
        status.setText(JZeitnahmeLauf.text("Uebernahme." + vorschau.status()));
        if (vorschau.status() != Status.OK) {
            return;
        }
        zeilen.removeAll();
        auswahlfelder.clear();
        for (String titel : List.of("Teilnehmer", "ErfassteZeit", "Zeitquelle", "BisherigeZeit", "Auswahl")) {
            zeilen.add(new JLabel(JZeitnahmeLauf.text(titel)));
        }
        for (Zeile zeile : vorschau.zeilen()) {
            var ergebnis = zeile.ergebnis();
            boolean gueltig = ergebnis != null && ergebnis.dauer() >= 10_000
                    && ergebnis.dauer() < (Integer.MAX_VALUE + 1L) * 10_000;
            String quelle = ergebnis == null ? "Fehlend" : ergebnis.quelle().name();
            if (ergebnis != null && ergebnis.unsicher()) {
                quelle += ".Unsicher";
            }
            zeilen.add(new JLabel(I18n.get("ZeitnahmeLauf.Bahn", zeile.bahn(), zeile.name())));
            zeilen.add(new JLabel(ergebnis == null ? "—" : gueltig ? JZeitnahmeLauf.zeit(ergebnis.dauer())
                    : JZeitnahmeLauf.text("UngueltigeZeit")));
            zeilen.add(new JLabel(JZeitnahmeLauf.text(quelle)));
            zeilen.add(new JLabel(zeile.bisher() > 0 ? StringTools.zeitString(zeile.bisher()) : "—"));
            JCheckBox freigabe = new JCheckBox();
            freigabe.getAccessibleContext().setAccessibleName(I18n.get("ZeitnahmeLauf.Bahn", zeile.bahn(), zeile.name()));
            freigabe.setEnabled(gueltig);
            freigabe.setSelected(gueltig && !ergebnis.unsicher());
            freigabe.addItemListener(event -> anwenden.setEnabled(auswahlfelder.values().stream().anyMatch(JCheckBox::isSelected)));
            auswahlfelder.put(zeile, freigabe);
            zeilen.add(freigabe);
        }
        anwenden.setEnabled(auswahlfelder.values().stream().anyMatch(JCheckBox::isSelected));
        zeilen.revalidate();
        zeilen.repaint();
    }

    private void uebernehmen() {
        var freigegeben = auswahlfelder.entrySet().stream().filter(eintrag -> eintrag.getValue().isSelected())
                .map(Map.Entry::getKey).toList();
        var unsichereBahnen = freigegeben.stream().filter(zeile -> zeile.ergebnis().unsicher())
                .map(Zeile::bahn).collect(Collectors.toSet());
        if (!unsichereBahnen.isEmpty() && !bestaetigen("UnsichereBestaetigen")) {
            return;
        }
        var rueckmeldung = uebernahme.uebernehmen(auswahl, versuch, freigegeben, false, unsichereBahnen);
        if (rueckmeldung.status() == Status.UEBERSCHREIBEN && bestaetigen("UeberschreibenBestaetigen")) {
            rueckmeldung = uebernahme.uebernehmen(auswahl, versuch, freigegeben, true, unsichereBahnen);
        }
        status.setText(I18n.get("ZeitnahmeLauf.Uebernahme." + rueckmeldung.status(), rueckmeldung.bahn()));
        if (rueckmeldung.status() == Status.OK) {
            dispose();
        }
    }

    private boolean bestaetigen(String schluessel) {
        return JOptionPane.showConfirmDialog(this, JZeitnahmeLauf.text(schluessel), JZeitnahmeLauf.text("Ergebnisse"),
                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
    }
}
