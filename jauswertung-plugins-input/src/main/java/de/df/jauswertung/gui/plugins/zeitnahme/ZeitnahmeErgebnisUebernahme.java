package de.df.jauswertung.gui.plugins.zeitnahme;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.swing.SwingUtilities;

import de.df.jauswertung.daten.ASchwimmer;
import de.df.jauswertung.gui.plugins.zeitnahme.VirtuellerZeitnahmelauf.Auswahl;

final class ZeitnahmeErgebnisUebernahme {
    enum Status { OK, BELEGT, VERALTET, GESCHLOSSEN, LEER, UNGUELTIG, UEBERSCHREIBEN, UNSICHER }

    record Rueckmeldung(Status status, int bahn) {
        Rueckmeldung(Status status) {
            this(status, 0);
        }
    }

    record Bahnziel(int bahn, ASchwimmer teilnehmer, int disziplin, String freiwasser) {
        int zeit() {
            return freiwasser == null ? teilnehmer.getZeit(disziplin) : teilnehmer.getZeit(freiwasser);
        }

        void setzen(int zeit) {
            if (freiwasser == null) {
                teilnehmer.setZeit(disziplin, zeit);
            } else {
                teilnehmer.setZeit(freiwasser, zeit);
            }
        }
    }

    record Laufdaten(Auswahl auswahl, List<Bahnziel> ziele) {
        Laufdaten {
            ziele = List.copyOf(ziele);
        }
    }

    record Zeile(int bahn, String name, ZeitnahmeVersuch.Ergebnis ergebnis, int bisher) { }

    record Vorschau(Status status, List<Zeile> zeilen) { }

    private record Eintrag(Bahnziel ziel, int wert) { }

    private record Zielschluessel(ASchwimmer teilnehmer, int disziplin, String freiwasser) { }

    private final Supplier<Runnable> zugriff;
    private final Function<Auswahl, Optional<Laufdaten>> lauf;
    private final Runnable punkteGeaendert;

    ZeitnahmeErgebnisUebernahme(Supplier<Runnable> zugriff, Function<Auswahl, Optional<Laufdaten>> lauf,
            Runnable punkteGeaendert) {
        this.zugriff = zugriff;
        this.lauf = lauf;
        this.punkteGeaendert = punkteGeaendert;
    }

    Vorschau pruefen(Auswahl auswahl, ZeitnahmeVersuch versuch) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException();
        }
        Runnable freigabe = zugriff.get();
        if (freigabe == null) {
            return new Vorschau(Status.BELEGT, List.of());
        }
        try {
            return lauf.apply(auswahl).filter(daten -> daten.auswahl().equals(auswahl) && zutreffend(daten, versuch))
                    .map(daten -> vorschau(daten, versuch)).orElseGet(() -> new Vorschau(Status.VERALTET, List.of()));
        } finally {
            freigabe.run();
        }
    }

    private static Vorschau vorschau(Laufdaten daten, ZeitnahmeVersuch versuch) {
        Map<Integer, ZeitnahmeVersuch.Ergebnis> ergebnisse = ergebnisse(versuch);
        List<Zeile> zeilen = daten.ziele().stream()
                .map(ziel -> new Zeile(ziel.bahn(), ziel.teilnehmer().getName(), ergebnisse.get(ziel.bahn()), ziel.zeit()))
                .toList();
        return new Vorschau(Status.OK, zeilen);
    }

    Rueckmeldung uebernehmen(Auswahl auswahl, ZeitnahmeVersuch versuch, List<Zeile> freigegeben,
            boolean ueberschreiben) {
        return uebernehmen(auswahl, versuch, freigegeben, ueberschreiben, Set.of());
    }

    Rueckmeldung uebernehmen(Auswahl auswahl, ZeitnahmeVersuch versuch, List<Zeile> freigegeben,
            boolean ueberschreiben, Set<Integer> unsicherBestaetigt) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException();
        }
        Runnable freigabe = zugriff.get();
        if (freigabe == null) {
            return new Rueckmeldung(Status.BELEGT);
        }
        try {
            Rueckmeldung rueckmeldung;
            synchronized (versuch) {
                var daten = lauf.apply(auswahl).filter(aktuell -> aktuell.auswahl().equals(auswahl)
                        && zutreffend(aktuell, versuch));
                rueckmeldung = daten.isEmpty() ? new Rueckmeldung(Status.VERALTET)
                        : schreiben(daten.orElseThrow(), versuch, freigegeben, ueberschreiben, unsicherBestaetigt);
            }
            if (rueckmeldung.status() == Status.OK) {
                punkteGeaendert.run();
            }
            return rueckmeldung;
        } finally {
            freigabe.run();
        }
    }

    private static Rueckmeldung schreiben(Laufdaten daten, ZeitnahmeVersuch versuch, List<Zeile> freigegeben,
            boolean ueberschreiben, Set<Integer> unsicherBestaetigt) {
        if (versuch.geschlossen()) {
            return new Rueckmeldung(Status.GESCHLOSSEN);
        }
        if (freigegeben.isEmpty()) {
            return new Rueckmeldung(Status.LEER);
        }
        Map<Integer, ZeitnahmeVersuch.Ergebnis> ergebnisse = ergebnisse(versuch);
        Map<Integer, Bahnziel> ziele = daten.ziele().stream().collect(Collectors.toMap(Bahnziel::bahn, ziel -> ziel));
        List<Eintrag> eintraege = new ArrayList<>();
        Set<Zielschluessel> eindeutigeZiele = new HashSet<>();
        for (Zeile zeile : freigegeben) {
            int bahn = zeile.bahn();
            var ziel = ziele.get(bahn);
            var ergebnis = zeile.ergebnis();
            if (ziel == null || ergebnis == null || !Objects.equals(ergebnisse.get(bahn), ergebnis)
                    || ziel.zeit() != zeile.bisher()) {
                return new Rueckmeldung(Status.VERALTET, bahn);
            }
            long hundertstel = ergebnis.dauer() / 10_000;
            if (hundertstel <= 0 || hundertstel > Integer.MAX_VALUE
                    || !eindeutigeZiele.add(new Zielschluessel(ziel.teilnehmer(), ziel.disziplin(), ziel.freiwasser()))) {
                return new Rueckmeldung(Status.UNGUELTIG, bahn);
            }
            if (ergebnis.unsicher() && !unsicherBestaetigt.contains(bahn)) {
                return new Rueckmeldung(Status.UNSICHER, bahn);
            }
            if (zeile.bisher() > 0 && zeile.bisher() != hundertstel && !ueberschreiben) {
                return new Rueckmeldung(Status.UEBERSCHREIBEN, bahn);
            }
            eintraege.add(new Eintrag(ziel, (int) hundertstel));
        }
        eintraege.forEach(eintrag -> eintrag.ziel().setzen(eintrag.wert()));
        versuch.alsUebernommenSchliessen();
        return new Rueckmeldung(Status.OK);
    }

    private static boolean zutreffend(Laufdaten daten, ZeitnahmeVersuch versuch) {
        var stand = versuch.laufstand();
        return daten.auswahl().wettkampf().equals(stand.wettkampf()) && daten.auswahl().lauf().equals(stand.lauf())
                && daten.auswahl().disziplin().equals(stand.disziplin());
    }

    private static Map<Integer, ZeitnahmeVersuch.Ergebnis> ergebnisse(ZeitnahmeVersuch versuch) {
        return versuch.ergebnisse().stream().collect(Collectors.toMap(ZeitnahmeVersuch.Ergebnis::bahn, ergebnis -> ergebnis));
    }
}
