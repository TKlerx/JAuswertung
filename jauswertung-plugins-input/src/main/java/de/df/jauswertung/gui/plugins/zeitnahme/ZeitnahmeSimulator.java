package de.df.jauswertung.gui.plugins.zeitnahme;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;

final class ZeitnahmeSimulator {
    private static final long VORLAUF = 2_000_000;
    private static final long VERBINDUNGSPAUSE = 250_000;
    private static final long SALVENINTERVALL = 250_000;
    private static final long STARTFREIGABE = 1_000_000;

    record Netz(int laufzeit, int jitter, int asymmetrie) {
        Netz {
            if (laufzeit <= 0 || jitter < 0 || asymmetrie < 0 ||
                    (long) laufzeit + jitter + asymmetrie > 1_000_000) {
                throw new IllegalArgumentException();
            }
        }
    }

    record Einstellungen(Netz vorher, Netz nachher, boolean neuverbinden, boolean telefonstart, boolean stoppuhren,
            int driftPpm, long intervall, long timeout, long laufdauer) {
        Einstellungen {
            Objects.requireNonNull(vorher);
            Objects.requireNonNull(nachher);
            if (driftPpm < 0 || driftPpm > 1_000 || intervall < 1_000 || intervall > 120_000_000 ||
                    timeout <= 0 || timeout > 120_000_000 || laufdauer <= 0 || laufdauer > 900_000_000) {
                throw new IllegalArgumentException();
            }
        }

        long horizont(long jetzt) {
            long freigabe = telefonstart ? STARTFREIGABE : 0;
            return jetzt + freigabe + (stoppuhren ? 0 : laufdauer);
        }
    }

    enum Status {
        ABGLEICH, BEREIT, RESYNC, GETRENNT, TIMEOUT
    }

    record Statuswechsel(long zeit, Status text) {
    }

    record Lauf(boolean bereit, long wartezeit, List<Long> bahnfehler, List<Statuswechsel> verlauf) {
        Lauf {
            bahnfehler = List.copyOf(bahnfehler);
            verlauf = List.copyOf(verlauf);
        }
    }

    record Serie(int bereit, int timeouts, long median, long p95, long groessterFehler, Lauf ersterLauf) {
    }

    private static final class Telefon {
        private final int nummer;
        private final UUID epoche;
        private final long versatz;
        private final int drift;
        private final Uhrabgleich abgleich = new Uhrabgleich();
        private Uhrabgleich.Probe probe;
        private long ereignis;
        private boolean salve;

        Telefon(int nummer, int driftPpm, Random zufall) {
            this.nummer = nummer;
            epoche = new UUID(0, nummer);
            versatz = zufall.nextInt(-250_000, 250_001);
            drift = zufall.nextInt(-driftPpm, driftPpm + 1);
        }

        long uhrzeit(long schreibtisch) {
            return schreibtisch + versatz + Math.round(schreibtisch * (drift / 1_000_000.0));
        }

        void senden(long jetzt, Netz netz, Random zufall) {
            long hin = netz.laufzeit() + zufall.nextInt(netz.jitter() + 1);
            long rueck = netz.laufzeit() + zufall.nextInt(netz.jitter() + 1);
            if (nummer % 2 == 0) {
                hin += netz.asymmetrie();
            } else {
                rueck += netz.asymmetrie();
            }
            long empfang = jetzt + hin;
            long antwort = empfang + 1_000;
            ereignis = antwort + rueck;
            probe = new Uhrabgleich.Probe(epoche, uhrzeit(jetzt), empfang, antwort, uhrzeit(ereignis));
        }

        void empfangen(long jetzt, long horizont, Einstellungen einstellungen, long budget) {
            var neu = Uhrabgleich.auswerten(probe, einstellungen.driftPpm());
            probe = null;
            abgleich.probeEmpfangen(neu, horizont, budget);
            salve = abgleich.probensalveNoetig(horizont, budget) || salve && neu.grenze(horizont) > budget;
            if (salve) {
                ereignis = jetzt + Math.min(SALVENINTERVALL, einstellungen.intervall());
            } else {
                long naechste = abgleich.schaetzung().naechsteAuffrischung(jetzt, horizont, einstellungen.intervall(), budget);
                ereignis = Math.max(jetzt + 1, naechste);
            }
        }

        void neuverbinden(long zeit) {
            if (probe != null) {
                abgleich.probeFehlgeschlagen();
            }
            probe = null;
            salve = true;
            ereignis = zeit;
        }
    }

    private ZeitnahmeSimulator() {
    }

    static Serie simulieren(Einstellungen einstellungen, long seed, int anzahl) {
        if (anzahl < 1 || anzahl > 1_000) {
            throw new IllegalArgumentException();
        }
        Random zufall = new Random(seed);
        List<Long> wartezeiten = new ArrayList<>();
        Lauf erster = null;
        long groessterFehler = 0;
        for (int i = 0; i < anzahl; i++) {
            Lauf lauf = laufSimulieren(einstellungen, zufall);
            if (i == 0) {
                erster = lauf;
            }
            if (lauf.bereit()) {
                wartezeiten.add(lauf.wartezeit());
                for (long fehler : lauf.bahnfehler()) {
                    groessterFehler = Math.max(groessterFehler, Math.abs(fehler));
                }
            }
        }
        wartezeiten.sort(Long::compareTo);
        return new Serie(wartezeiten.size(), anzahl - wartezeiten.size(),
                perzentil(wartezeiten, 0.5), perzentil(wartezeiten, 0.95), groessterFehler, erster);
    }

    private static Lauf laufSimulieren(Einstellungen einstellungen, Random zufall) {
        Telefon starter = einstellungen.telefonstart() ? new Telefon(0, einstellungen.driftPpm(), zufall) : null;
        List<Telefon> bahntelefone = einstellungen.stoppuhren() ? List.of()
                : List.of(new Telefon(1, einstellungen.driftPpm(), zufall),
                        new Telefon(2, einstellungen.driftPpm(), zufall), new Telefon(3, einstellungen.driftPpm(), zufall));
        List<Telefon> telefone = new ArrayList<>(bahntelefone);
        if (starter != null) {
            telefone.addFirst(starter);
        }
        boolean netzwechsel = einstellungen.neuverbinden() || !einstellungen.vorher().equals(einstellungen.nachher());
        long messbeginn = netzwechsel ? VORLAUF : 0;
        long ende = messbeginn + einstellungen.timeout();
        long wechsel = netzwechsel ? messbeginn : Long.MAX_VALUE;
        long verbindet = -1;
        Netz netz = einstellungen.vorher();
        List<Statuswechsel> verlauf = new ArrayList<>();
        long jetzt = 0;
        while (true) {
            if (jetzt == wechsel) {
                netz = einstellungen.nachher();
                wechsel = Long.MAX_VALUE;
                if (einstellungen.neuverbinden() && !telefone.isEmpty()) {
                    verbindet = jetzt + VERBINDUNGSPAUSE;
                    for (Telefon telefon : telefone) {
                        telefon.neuverbinden(verbindet);
                    }
                }
            }
            long horizont = einstellungen.horizont(jetzt);
            for (Telefon telefon : telefone) {
                if (telefon.ereignis == jetzt && telefon.probe != null) {
                    telefon.empfangen(jetzt, horizont, einstellungen, restbudget(telefon, starter, bahntelefone, horizont));
                }
            }
            boolean getrennt = jetzt < verbindet;
            boolean bereit = !getrennt && Uhrabgleich.bereit(starter != null,
                    starter == null ? null : starter.abgleich.schaetzung(),
                    bahntelefone.stream().map(telefon -> telefon.abgleich.schaetzung()).toList(), horizont);
            Status status = bereit ? Status.BEREIT : getrennt ? Status.GETRENNT
                    : verlauf.stream().anyMatch(eintrag -> eintrag.text() == Status.BEREIT) || jetzt >= messbeginn && netzwechsel
                            ? Status.RESYNC : Status.ABGLEICH;
            statusEintragen(verlauf, jetzt, status);
            if (bereit && jetzt >= messbeginn) {
                return new Lauf(true, jetzt - messbeginn, bahnfehler(starter, bahntelefone, jetzt, einstellungen.laufdauer()), verlauf);
            }
            if (jetzt == ende) {
                statusEintragen(verlauf, jetzt, Status.TIMEOUT);
                return new Lauf(false, einstellungen.timeout(), List.of(), verlauf);
            }
            long naechstesEreignis = Math.min(wechsel, ende);
            for (Telefon telefon : telefone) {
                if (telefon.ereignis == jetzt && telefon.probe == null) {
                    telefon.senden(jetzt, netz, zufall);
                }
                naechstesEreignis = Math.min(naechstesEreignis, telefon.ereignis);
            }
            jetzt = naechstesEreignis;
        }
    }

    private static long restbudget(Telefon telefon, Telefon starter, List<Telefon> bahnen, long horizont) {
        if (telefon == starter) {
            long maximum = bahnen.stream().mapToLong(bahn -> unsicherheit(bahn, horizont)).max().orElse(0);
            return Uhrabgleich.BUDGET - maximum;
        }
        return Uhrabgleich.BUDGET - unsicherheit(starter, horizont);
    }

    private static long unsicherheit(Telefon telefon, long horizont) {
        return telefon == null || telefon.abgleich.schaetzung() == null ? 0
                : telefon.abgleich.schaetzung().grenze(horizont);
    }

    private static List<Long> bahnfehler(Telefon starter, List<Telefon> telefone, long start, long dauer) {
        if (telefone.isEmpty()) {
            return List.of();
        }
        long startzeit = starter == null ? start : starter.abgleich.schaetzung().schreibtischzeit(starter.uhrzeit(start));
        long ziel = start + dauer;
        List<Long> fehler = new ArrayList<>();
        // The last official covers two lanes, so their captures use the same device clock.
        for (Telefon telefon : List.of(telefone.get(0), telefone.get(1), telefone.get(2), telefone.get(2))) {
            long zielzeit = telefon.abgleich.schaetzung().schreibtischzeit(telefon.uhrzeit(ziel));
            fehler.add(zielzeit - startzeit - dauer);
        }
        return fehler;
    }

    private static void statusEintragen(List<Statuswechsel> verlauf, long zeit, Status status) {
        if (verlauf.isEmpty() || verlauf.getLast().text() != status) {
            verlauf.add(new Statuswechsel(zeit, status));
        }
    }

    private static long perzentil(List<Long> sortiert, double anteil) {
        return sortiert.isEmpty() ? -1 : sortiert.get((int) Math.ceil(sortiert.size() * anteil) - 1);
    }
}
