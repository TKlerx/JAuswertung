package de.df.jauswertung.gui.plugins.zeitnahme;

import java.util.List;
import java.util.function.Supplier;

final class ZeitnahmeSnapshot {
    private final Supplier<Runnable> zugriff;
    private final Supplier<List<VirtuellerZeitnahmelauf.Auswahl>> quelle;
    private List<VirtuellerZeitnahmelauf.Auswahl> laeufe = List.of();
    private boolean veraltet;

    ZeitnahmeSnapshot(Supplier<Runnable> zugriff, Supplier<List<VirtuellerZeitnahmelauf.Auswahl>> quelle) {
        this.zugriff = zugriff;
        this.quelle = quelle;
    }

    boolean aktualisieren() {
        Runnable freigabe = zugriff.get();
        if (freigabe == null) {
            veraltet = true;
            return false;
        }
        try {
            laeufe = List.copyOf(quelle.get());
            veraltet = false;
            return true;
        } finally {
            freigabe.run();
        }
    }

    boolean validieren(VirtuellerZeitnahmelauf.Auswahl auswahl) {
        return beiGueltigemLauf(auswahl, () -> {});
    }

    boolean beiGueltigemLauf(VirtuellerZeitnahmelauf.Auswahl auswahl, Runnable aktion) {
        Runnable freigabe = zugriff.get();
        if (freigabe == null) {
            veraltet = true;
            return false;
        }
        try {
            veraltet = !quelle.get().contains(auswahl);
            if (veraltet) {
                return false;
            }
            aktion.run();
            return true;
        } finally {
            freigabe.run();
        }
    }

    List<VirtuellerZeitnahmelauf.Auswahl> laeufe() {
        return laeufe;
    }

    boolean veraltet() {
        return veraltet;
    }
}
