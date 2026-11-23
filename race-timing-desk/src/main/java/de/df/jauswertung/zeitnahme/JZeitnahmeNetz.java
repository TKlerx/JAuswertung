package de.df.jauswertung.zeitnahme;

import java.awt.Component;
import java.awt.GridLayout;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.OptionalLong;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;

final class JZeitnahmeNetz {
    record Einstellungen(InetSocketAddress adresse, Path zertifikat, char[] passwort, OptionalLong tonlatenz) { }

    private JZeitnahmeNetz() {
    }

    static Einstellungen einstellen(Component parent) {
        JTextField adresse = new JTextField("127.0.0.1", 26);
        JTextField port = new JTextField("8443");
        JTextField zertifikat = new JTextField();
        JPasswordField passwort = new JPasswordField();
        JTextField tonlatenz = new JTextField();
        JPanel felder = new JPanel(new GridLayout(0, 2, 6, 6));
        feld(felder, "NetzAdresse", adresse);
        feld(felder, "NetzPort", port);
        feld(felder, "NetzZertifikat", zertifikat);
        feld(felder, "NetzPasswort", passwort);
        feld(felder, "NetzTonlatenz", tonlatenz);
        if (JOptionPane.showConfirmDialog(parent, felder, ZeitnahmeTexte.text("NetzStart"),
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return null;
        }
        char[] geheim = passwort.getPassword();
        passwort.setText("");
        try {
            String ip = adresse.getText().strip();
            if (!ip.matches("[0-9.]+|[0-9a-fA-F:]+:[0-9a-fA-F:]+") || zertifikat.getText().isBlank()) {
                throw new IllegalArgumentException();
            }
            int nummer = Integer.parseInt(port.getText().strip());
            if (nummer < 1 || nummer > 65535) {
                throw new IllegalArgumentException();
            }
            OptionalLong grenze = tonlatenz.getText().isBlank() ? OptionalLong.empty()
                    : OptionalLong.of(Math.multiplyExact(Long.parseLong(tonlatenz.getText().strip()), 1_000));
            return new Einstellungen(new InetSocketAddress(InetAddress.getByName(ip), nummer),
                    Path.of(zertifikat.getText().strip()), geheim, grenze);
        } catch (IllegalArgumentException | ArithmeticException | java.net.UnknownHostException ex) {
            Arrays.fill(geheim, '\0');
            JOptionPane.showMessageDialog(parent, ZeitnahmeTexte.text("NetzUngueltig"));
            return null;
        }
    }

    private static void feld(JPanel panel, String text, JTextField feld) {
        JLabel label = new JLabel(ZeitnahmeTexte.text(text));
        label.setLabelFor(feld);
        panel.add(label);
        panel.add(feld);
    }
}
