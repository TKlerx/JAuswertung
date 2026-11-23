package de.df.jauswertung.zeitnahme;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class Uhrabgleich {
    static final long BUDGET = 10_000;
    private static final long MIKROSEKUNDEN_PRO_SEKUNDE = 1_000_000;

    record Probe(UUID epoche, long telefonGesendet, long schreibtischEmpfangen, long schreibtischGesendet,
            long telefonEmpfangen) {
        Probe {
            Objects.requireNonNull(epoche);
            if (telefonEmpfangen < telefonGesendet || schreibtischGesendet < schreibtischEmpfangen) {
                throw new IllegalArgumentException();
            }
        }
    }

    record Schaetzung(UUID id, UUID epoche, long bezugszeit, long versatz, long unsicherheit, int driftPpm) {
        Schaetzung {
            Objects.requireNonNull(id);
            Objects.requireNonNull(epoche);
            if (unsicherheit < 0 || driftPpm < 0 || driftPpm >= 1_000_000) {
                throw new IllegalArgumentException();
            }
        }

        long schreibtischzeit(long telefonzeit) {
            return Math.addExact(telefonzeit, versatz);
        }

        long grenze(long horizont) {
            long alter = Math.absExact(Math.subtractExact(horizont, bezugszeit));
            return Math.addExact(unsicherheit, driftgrenze(alter, driftPpm));
        }

        long naechsteAuffrischung(long jetzt, long horizont, long intervall, long budget) {
            if (grenze(horizont) > budget) {
                return jetzt;
            }
            if (driftPpm == 0) {
                return Math.addExact(jetzt, intervall);
            }
            long maximalesAlter = Math.multiplyExact(budget - unsicherheit, MIKROSEKUNDEN_PRO_SEKUNDE) / driftPpm;
            long rest = maximalesAlter - Math.max(0, horizont - bezugszeit);
            return Math.addExact(jetzt, Math.min(intervall, Math.max(0, rest / 2)));
        }
    }

    private Schaetzung schaetzung;
    private int ungueltigeProben;

    static Schaetzung auswerten(Probe probe, int driftPpm) {
        return auswerten(probe, driftPpm, 0);
    }

    static Schaetzung auswerten(Probe probe, int driftPpm, long aufloesung) {
        if (driftPpm < 0 || driftPpm >= MIKROSEKUNDEN_PRO_SEKUNDE || aufloesung < 0) {
            throw new IllegalArgumentException();
        }
        long telefonDauer = Math.subtractExact(probe.telefonEmpfangen(), probe.telefonGesendet());
        long schreibtischDauer = Math.subtractExact(probe.schreibtischGesendet(), probe.schreibtischEmpfangen());
        long laufzeit = Math.subtractExact(telefonDauer, schreibtischDauer);
        long rundungsgrenze = Math.multiplyExact(2, aufloesung);
        long maximaleDrift = Math.ceilDiv(Math.multiplyExact(Math.addExact(telefonDauer, rundungsgrenze), driftPpm),
                MIKROSEKUNDEN_PRO_SEKUNDE - driftPpm);
        if (laufzeit < -Math.addExact(maximaleDrift, rundungsgrenze)) {
            throw new IllegalArgumentException();
        }
        long hin = Math.subtractExact(probe.schreibtischEmpfangen(), probe.telefonGesendet());
        long rueck = Math.subtractExact(probe.schreibtischGesendet(), probe.telefonEmpfangen());
        long versatz = Math.addExact(hin, rueck) / 2;
        // Browser rounding can affect both the measured offset and the later event timestamp.
        long zuschlag = Math.addExact(Math.addExact(maximaleDrift, rundungsgrenze), 1);
        long unsicherheit = Math.addExact(Math.ceilDiv(Math.max(0, laufzeit), 2), zuschlag);
        return new Schaetzung(UUID.randomUUID(), probe.epoche(), probe.schreibtischGesendet(), versatz, unsicherheit, driftPpm);
    }

    static boolean bereit(boolean telefonstart, Schaetzung start, List<Schaetzung> bahnen, long horizont) {
        if (telefonstart && start == null) {
            return false;
        }
        long startgrenze = telefonstart ? start.grenze(horizont) : 0;
        return startgrenze <= BUDGET && bahnen.stream()
                .allMatch(bahn -> bahn != null && bahn.grenze(horizont) <= BUDGET - startgrenze);
    }

    void probeEmpfangen(Schaetzung neu, long horizont, long budget) {
        schaetzung = zusammenfuehren(schaetzung, neu, horizont);
        ungueltigeProben = neu.grenze(horizont) <= budget ? 0 : ungueltigeProben + 1;
    }

    private static Schaetzung zusammenfuehren(Schaetzung alt, Schaetzung neu, long horizont) {
        if (alt == null || !alt.epoche().equals(neu.epoche()) || alt.driftPpm() != neu.driftPpm()) {
            return neu;
        }
        long alteGrenze = alt.grenze(neu.bezugszeit());
        long unten = Math.max(Math.subtractExact(alt.versatz(), alteGrenze),
                Math.subtractExact(neu.versatz(), neu.unsicherheit()));
        long oben = Math.min(Math.addExact(alt.versatz(), alteGrenze),
                Math.addExact(neu.versatz(), neu.unsicherheit()));
        if (oben < unten) {
            return neu;
        }
        Schaetzung beste = neu.grenze(horizont) <= alt.grenze(horizont) ? neu : alt;
        long breite = Math.subtractExact(oben, unten);
        long unsicherheit = Math.ceilDiv(breite, 2);
        if (Math.addExact(unsicherheit, neu.grenze(horizont) - neu.unsicherheit()) >= beste.grenze(horizont)) {
            return beste;
        }
        return new Schaetzung(UUID.randomUUID(), neu.epoche(), neu.bezugszeit(), Math.addExact(unten, breite / 2),
                unsicherheit, neu.driftPpm());
    }

    void probeFehlgeschlagen() {
        ungueltigeProben++;
    }

    boolean probensalveNoetig(long horizont, long budget) {
        return ungueltigeProben >= 2 || schaetzung == null || schaetzung.grenze(horizont) > budget;
    }

    Schaetzung schaetzung() {
        return schaetzung;
    }

    private static long driftgrenze(long alter, int driftPpm) {
        long sekunden = alter / MIKROSEKUNDEN_PRO_SEKUNDE;
        long rest = alter % MIKROSEKUNDEN_PRO_SEKUNDE;
        return Math.addExact(Math.multiplyExact(sekunden, driftPpm),
                Math.ceilDiv(rest * driftPpm, MIKROSEKUNDEN_PRO_SEKUNDE));
    }
}
