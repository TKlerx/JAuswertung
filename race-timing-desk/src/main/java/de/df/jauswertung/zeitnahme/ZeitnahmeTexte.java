package de.df.jauswertung.zeitnahme;

import java.text.MessageFormat;
import java.util.ResourceBundle;

final class ZeitnahmeTexte {
    private static final ResourceBundle TEXTE = ResourceBundle.getBundle("zeitnahme-tisch");

    private ZeitnahmeTexte() {
    }

    static String text(String schluessel, Object... argumente) {
        return MessageFormat.format(TEXTE.getString(schluessel), argumente);
    }
}
