package de.df.jauswertung.gui.plugins.zeitnahme;

import java.util.OptionalLong;
import java.util.regex.Pattern;

final class ManuelleZeitnahme {
    private static final Pattern ZEIT = Pattern.compile("(?:(\\d+):([0-5]\\d)|(\\d+))(?:[,.](\\d{1,6}))?");

    private ManuelleZeitnahme() {
    }

    static OptionalLong dauer(String eingabe) {
        var treffer = ZEIT.matcher(eingabe.strip());
        if (!treffer.matches()) {
            return OptionalLong.empty();
        }
        try {
            long sekunden = treffer.group(3) != null ? Long.parseLong(treffer.group(3))
                    : Math.addExact(Math.multiplyExact(Long.parseLong(treffer.group(1)), 60),
                            Long.parseLong(treffer.group(2)));
            String bruchteil = treffer.group(4);
            long mikrosekunden = bruchteil == null ? 0 : Long.parseLong((bruchteil + "000000").substring(0, 6));
            long dauer = Math.addExact(Math.multiplyExact(sekunden, 1_000_000), mikrosekunden);
            return dauer > 0 ? OptionalLong.of(dauer) : OptionalLong.empty();
        } catch (NumberFormatException | ArithmeticException ex) {
            return OptionalLong.empty();
        }
    }
}
