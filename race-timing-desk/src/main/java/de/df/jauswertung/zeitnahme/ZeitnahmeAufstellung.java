package de.df.jauswertung.zeitnahme;

import static de.df.jauswertung.zeitnahme.ZeitnahmeTexte.text;

import java.awt.Component;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.text.PlainDocument;

final class ZeitnahmeAufstellung extends JPanel {
    record Lauf(int wettbewerb, int nummer, String disziplin, int sekunden, int horn, boolean countdown,
            List<ZeitnahmeVersuch.Bahn> bahnen) { }

    private record Zuweisung(String anzeige, UUID handy, boolean zeit) {
        @Override
        public String toString() {
            return anzeige;
        }
    }

    private final JSpinner wettbewerb = zahl(1, 1, 999);
    private final JSpinner lauf = zahl(1, 1, 999);
    private final JTextField disziplin = new JTextField(22);
    private final JSpinner dauer = zahl(120, 1, 3600);
    private final JSpinner horn = zahl(Startsignal.STANDARD, Startsignal.MINIMUM, Startsignal.MAXIMUM);
    private final JCheckBox countdown = new JCheckBox(text("Countdown"));
    private final List<JComboBox<Zuweisung>> zuweisungen = new ArrayList<>();
    private final List<UUID> handys;

    ZeitnahmeAufstellung(List<UUID> handys) {
        super(new GridLayout(0, 2, 8, 4));
        this.handys = handys;
        var dokument = (PlainDocument) disziplin.getDocument();
        dokument.putProperty("filterNewlines", Boolean.TRUE);
        feld("Wettbewerb", wettbewerb);
        feld("Lauf", lauf);
        feld("Disziplin", disziplin);
        feld("Dauer", dauer);
        feld("Horn", horn);
        add(new JLabel());
        add(countdown);
        countdown.addActionListener(event -> horn.setValue(countdown() ? Startsignal.COUNTDOWN_STANDARD : Startsignal.STANDARD));

        List<Zuweisung> auswahl = new ArrayList<>();
        auswahl.add(new Zuweisung(text("Frei"), null, false));
        auswahl.add(new Zuweisung(text("Tisch"), null, false));
        for (int i = 0; i < handys.size(); i++) {
            auswahl.add(new Zuweisung(text("HandyZeit", i + 1), handys.get(i), true));
            auswahl.add(new Zuweisung(text("HandyStoppuhr", i + 1), handys.get(i), false));
        }
        for (int i = 1; i <= 10; i++) {
            var zuweisung = new JComboBox<>(auswahl.toArray(Zuweisung[]::new));
            var label = new JLabel(text("Bahn", i));
            label.setLabelFor(zuweisung);
            add(label);
            add(zuweisung);
            zuweisungen.add(zuweisung);
        }
    }

    Lauf lesen() throws java.text.ParseException {
        String name = disziplin.getText().strip();
        List<ZeitnahmeVersuch.Bahn> bahnen = new ArrayList<>();
        for (int i = 0; i < zuweisungen.size(); i++) {
            var feld = zuweisungen.get(i);
            if (feld.getSelectedIndex() > 0) {
                var zuweisung = feld.getItemAt(feld.getSelectedIndex());
                bahnen.add(new ZeitnahmeVersuch.Bahn(i + 1, text("Bahn", i + 1), zuweisung.handy(), zuweisung.zeit()));
            }
        }
        if (name.isEmpty() || name.length() > 100 || bahnen.isEmpty()) {
            throw new IllegalArgumentException();
        }
        return new Lauf(wert(wettbewerb), wert(lauf), name, wert(dauer), horndauer(), countdown(), List.copyOf(bahnen));
    }

    boolean countdown() {
        return countdown.isSelected();
    }

    int horndauer() throws java.text.ParseException {
        return wert(horn);
    }

    List<String> handynamen() {
        List<String> namen = new ArrayList<>();
        for (int i = 0; i < handys.size(); i++) {
            namen.add(text("Rolle", i + 1));
        }
        return namen;
    }

    void bearbeitbar(boolean aktiv) {
        for (Component feld : getComponents()) {
            feld.setEnabled(aktiv);
        }
    }

    private void feld(String schluessel, Component eingabe) {
        JLabel label = new JLabel(text(schluessel));
        label.setLabelFor(eingabe);
        add(label);
        add(eingabe);
    }

    private static JSpinner zahl(int wert, int minimum, int maximum) {
        return new JSpinner(new SpinnerNumberModel(wert, minimum, maximum, 1));
    }

    private static int wert(JSpinner feld) throws java.text.ParseException {
        feld.commitEdit();
        return (int) feld.getValue();
    }
}
