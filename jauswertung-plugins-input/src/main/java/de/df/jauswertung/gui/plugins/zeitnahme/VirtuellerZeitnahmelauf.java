package de.df.jauswertung.gui.plugins.zeitnahme;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class VirtuellerZeitnahmelauf {
    record Teilnehmer(int bahn, String name, UUID id, int disziplin) {
        Teilnehmer(int bahn, String name) {
            this(bahn, name, null, -1);
        }
    }

    record Auswahl(UUID wettkampf, UUID lauf, String name, String disziplin, List<Teilnehmer> bahnen, String freiwasser) {
        Auswahl(UUID wettkampf, UUID lauf, String name, String disziplin, List<Teilnehmer> bahnen) {
            this(wettkampf, lauf, name, disziplin, bahnen, null);
        }

        Auswahl {
            bahnen = List.copyOf(bahnen);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private final Map<UUID, Uhrabgleich.Schaetzung> uhren = new LinkedHashMap<>();
    private final Map<Integer, ZeitnahmeEreignis.Ziel> letzteZiele = new LinkedHashMap<>();
    private final Map<Integer, ZeitnahmeEreignis.Stoppuhr> letzteStoppuhren = new LinkedHashMap<>();
    private final ZeitnahmeVersuch.Sitzung sitzung;
    private final ZeitnahmeVersuch versuch;
    private ZeitnahmeVersuch.Startfreigabe letzteFreigabe;
    private ZeitnahmeEreignis.Start letzteStartmeldung;
    private ZeitnahmeVersuch.Audio letztesAudio;

    VirtuellerZeitnahmelauf(ZeitnahmeVersuch.Sitzung sitzung, Auswahl auswahl, UUID starter,
            Map<Integer, UUID> zuordnung, long laufdauer, long jetzt) {
        this(sitzung, auswahl, starter, zuordnung, Set.of(), laufdauer, jetzt);
    }

    VirtuellerZeitnahmelauf(ZeitnahmeVersuch.Sitzung sitzung, Auswahl auswahl, UUID starter,
            Map<Integer, UUID> zuordnung, Set<Integer> stoppuhr, long laufdauer, long jetzt) {
        this(sitzung, auswahl, starter, zuordnung, stoppuhr, laufdauer, jetzt, true);
    }

    VirtuellerZeitnahmelauf(ZeitnahmeVersuch.Sitzung sitzung, Auswahl auswahl, UUID starter,
            Map<Integer, UUID> zuordnung, Set<Integer> stoppuhr, long laufdauer, long jetzt, boolean virtuelleUhren) {
        this.sitzung = sitzung;
        List<ZeitnahmeVersuch.Bahn> bahnen = new ArrayList<>();
        for (Teilnehmer teilnehmer : auswahl.bahnen()) {
            UUID telefon = zuordnung.get(teilnehmer.bahn());
            bahnen.add(new ZeitnahmeVersuch.Bahn(teilnehmer.bahn(), teilnehmer.name(), telefon,
                    telefon != null && !stoppuhr.contains(teilnehmer.bahn())));
        }
        versuch = sitzung.anlegen(new ZeitnahmeVersuch.Laufstand(auswahl.wettkampf(), auswahl.lauf(),
                auswahl.disziplin(), bahnen, starter), laufdauer);
        if (virtuelleUhren) {
            if (starter != null) {
                uhrKoppeln(starter, jetzt);
            }
            bahnen.stream().filter(ZeitnahmeVersuch.Bahn::telefonzeit)
                    .forEach(bahn -> uhrKoppeln(bahn.telefon(), jetzt));
        }
    }

    private void uhrKoppeln(UUID telefon, long jetzt) {
        versuch.abgleich(telefon, uhren.computeIfAbsent(telefon, ignoriert -> new Uhrabgleich.Schaetzung(
                UUID.randomUUID(), UUID.randomUUID(), jetzt, 0, 0, 0)));
    }

    ZeitnahmeVersuch versuch() {
        return versuch;
    }

    ZeitnahmeVersuch.Startfreigabe startAnfordern(long jetzt) {
        UUID starter = versuch.laufstand().starttelefon();
        var uhr = uhren.get(starter);
        return versuch.startfreigabe(starter, UUID.randomUUID(), uhr.epoche(), uhr.id(), jetzt).orElse(null);
    }

    void startErfassen(ZeitnahmeVersuch.Startfreigabe freigabe, Long signalbeginn, ZeitnahmeVersuch.Audio audio) {
        UUID starter = versuch.laufstand().starttelefon();
        letzteFreigabe = freigabe;
        letzteStartmeldung = signalbeginn == null ? null : new ZeitnahmeEreignis.Start(
                UUID.randomUUID(), versuch.id(), starter, signalbeginn, freigabe.uhr());
        letztesAudio = audio;
    }

    boolean startSenden() {
        if (letzteFreigabe == null) {
            return false;
        }
        long spaetesterTon = letzteStartmeldung == null ? 0 : letzteStartmeldung.zeit();
        return versuch.startMelden(letzteFreigabe.id(), versuch.laufstand().starttelefon(),
                letzteStartmeldung, letztesAudio, spaetesterTon);
    }

    ZeitnahmeEreignis.Ziel zielErfassen(int nummer, long zeit) {
        for (var bahn : versuch.laufstand().bahnen()) {
            if (bahn.nummer() == nummer && bahn.telefonzeit()) {
                return letzteZiele.computeIfAbsent(nummer, ignoriert -> new ZeitnahmeEreignis.Ziel(UUID.randomUUID(),
                        versuch.id(), bahn.telefon(), nummer, zeit, uhren.get(bahn.telefon())));
            }
        }
        throw new IllegalArgumentException();
    }

    boolean zielSenden(int bahn) {
        var ziel = letzteZiele.get(bahn);
        if (ziel != null) {
            return sitzung.zielEmpfangen(ziel);
        }
        var stoppuhr = letzteStoppuhren.get(bahn);
        return stoppuhr != null && sitzung.stoppuhrEmpfangen(stoppuhr);
    }

    boolean zielVorhanden(int bahn) {
        return letzteZiele.containsKey(bahn) || letzteStoppuhren.containsKey(bahn);
    }

    void stoppuhrAmHandy(int nummer, long dauer) {
        var bahn = versuch.laufstand().bahnen().stream().filter(b -> b.nummer() == nummer).findFirst().orElseThrow();
        letzteStoppuhren.putIfAbsent(nummer,
                new ZeitnahmeEreignis.Stoppuhr(UUID.randomUUID(), versuch.id(), bahn.telefon(), nummer, dauer));
    }

    boolean stoppuhrAmTisch(int bahn, long dauer) {
        return versuch.stoppuhrErfassen(new ZeitnahmeEreignis.Stoppuhr(UUID.randomUUID(), versuch.id(), null, bahn, dauer));
    }
}
