package de.df.jauswertung.zeitnahme;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

final class ZeitnahmeVersuch {
    private static final long FREIGABEDAUER = 1_000_000;

    enum Audio { PUENKTLICH, AUSGEFALLEN, UNBEKANNT }

    enum Zustand { BEREIT, TISCHSTART, FREIGEGEBEN, START_UNSICHER, LAEUFT, GESCHLOSSEN, UEBERNOMMEN }

    record Startfreigabe(UUID id, long beginn, long frist, Uhrabgleich.Schaetzung uhr) { }

    enum Quelle { ZIEL, STOPPUHR_HANDY, STOPPUHR_TISCH }

    record Ergebnis(int bahn, long dauer, boolean unsicher, Quelle quelle, UUID ereignis) { }

    static final class Sitzung {
        private final Map<UUID, ZeitnahmeVersuch> versuche = new HashMap<>();

        synchronized ZeitnahmeVersuch anlegen(Laufstand laufstand, long laufdauer) {
            var versuch = new ZeitnahmeVersuch(laufstand, laufdauer);
            versuche.put(versuch.id(), versuch);
            return versuch;
        }

        synchronized boolean zielEmpfangen(ZeitnahmeEreignis.Ziel ereignis) {
            var versuch = versuche.get(ereignis.versuch());
            return versuch != null && versuch.zielErfassen(ereignis);
        }

        synchronized ZeitnahmeVersuch versuch(UUID id) {
            return versuche.get(id);
        }

        synchronized boolean stoppuhrEmpfangen(ZeitnahmeEreignis.Stoppuhr ereignis) {
            var versuch = versuche.get(ereignis.versuch());
            return ereignis.geraet() != null && versuch != null && versuch.stoppuhrErfassen(ereignis);
        }
    }

    private record AnfrageId(UUID geraet, UUID id) { }

    private record Anfrage(UUID epoche, UUID schaetzung, Optional<Startfreigabe> antwort) { }

    private record Startmeldung(UUID geraet, ZeitnahmeEreignis.Start ereignis, Audio audio, long spaetesterTon) { }

    record Bahn(int nummer, String teilnehmer, UUID telefon, boolean telefonzeit) {
        Bahn {
            Objects.requireNonNull(teilnehmer);
            if (nummer < 1 || telefonzeit && telefon == null) {
                throw new IllegalArgumentException();
            }
        }
    }

    record Laufstand(UUID wettkampf, UUID lauf, String disziplin, List<Bahn> bahnen, UUID starttelefon) {
        Laufstand {
            Objects.requireNonNull(wettkampf);
            Objects.requireNonNull(lauf);
            Objects.requireNonNull(disziplin);
            bahnen = List.copyOf(bahnen);
            Set<Integer> nummern = new HashSet<>();
            if (bahnen.isEmpty() || bahnen.stream().anyMatch(bahn -> !nummern.add(bahn.nummer()))) {
                throw new IllegalArgumentException();
            }
        }
    }

    private final UUID id = UUID.randomUUID();
    private final Laufstand laufstand;
    private final long laufdauer;
    private final Map<UUID, Uhrabgleich.Schaetzung> aktuelleUhren = new HashMap<>();
    private final Map<UUID, Map<UUID, Uhrabgleich.Schaetzung>> bekannteUhren = new HashMap<>();
    private final Map<AnfrageId, Anfrage> anfragen = new HashMap<>();
    private final Map<UUID, Startmeldung> meldungen = new HashMap<>();
    private final Map<Integer, ZeitnahmeEreignis.Ziel> ziele = new LinkedHashMap<>();
    private final Map<UUID, ZeitnahmeEreignis.Ziel> zielIds = new HashMap<>();
    private final Map<Integer, ZeitnahmeEreignis.Stoppuhr> handyZeiten = new LinkedHashMap<>();
    private final Map<Integer, ZeitnahmeEreignis.Stoppuhr> tischZeiten = new LinkedHashMap<>();
    private final Map<UUID, ZeitnahmeEreignis.Stoppuhr> stoppuhrIds = new HashMap<>();
    private Zustand zustand = Zustand.BEREIT;
    private Startfreigabe freigabe;
    private ZeitnahmeEreignis.Start unsichererStart;
    private ZeitnahmeEreignis.Start start;
    private boolean startUnsicher;
    private long horizont;

    ZeitnahmeVersuch(Laufstand laufstand, long laufdauer) {
        this.laufstand = Objects.requireNonNull(laufstand);
        if (laufdauer <= 0) {
            throw new IllegalArgumentException();
        }
        this.laufdauer = laufdauer;
    }

    UUID id() {
        return id;
    }

    Laufstand laufstand() {
        return laufstand;
    }

    synchronized void abgleich(UUID geraet, Uhrabgleich.Schaetzung uhr) {
        Objects.requireNonNull(geraet);
        Objects.requireNonNull(uhr);
        var gespeichert = bekannteUhren.computeIfAbsent(geraet, ignoriert -> new HashMap<>()).putIfAbsent(uhr.id(), uhr);
        if (gespeichert != null && !gespeichert.equals(uhr)) {
            throw new IllegalArgumentException();
        }
        aktuelleUhren.put(geraet, uhr);
    }

    synchronized Optional<Startfreigabe> startfreigabe(UUID geraet, UUID anfrage, UUID epoche, UUID schaetzung,
            long jetzt) {
        Objects.requireNonNull(geraet);
        Objects.requireNonNull(anfrage);
        Objects.requireNonNull(epoche);
        Objects.requireNonNull(schaetzung);
        fristPruefen(jetzt);
        var id = new AnfrageId(geraet, anfrage);
        var gespeicherteAnfrage = anfragen.computeIfAbsent(id,
                ignoriert -> new Anfrage(epoche, schaetzung, freigeben(geraet, epoche, schaetzung, jetzt)));
        return gespeicherteAnfrage.epoche().equals(epoche) && gespeicherteAnfrage.schaetzung().equals(schaetzung)
                ? gespeicherteAnfrage.antwort() : Optional.empty();
    }

    private Optional<Startfreigabe> freigeben(UUID geraet, UUID epoche, UUID schaetzung, long jetzt) {
        if (zustand != Zustand.BEREIT || !geraet.equals(laufstand.starttelefon())) {
            return Optional.empty();
        }
        var uhr = aktuelleUhren.get(geraet);
        long frist = Math.addExact(jetzt, FREIGABEDAUER);
        if (uhr == null || !uhr.epoche().equals(epoche) || !uhr.id().equals(schaetzung)
                || !uhrenBereit(frist, uhr)) {
            return Optional.empty();
        }
        horizont = Math.addExact(frist, laufdauer);
        freigabe = new Startfreigabe(UUID.randomUUID(), jetzt, frist, uhr);
        zustand = Zustand.FREIGEGEBEN;
        return Optional.of(freigabe);
    }

    private boolean uhrenBereit(long spaetesterStart, Uhrabgleich.Schaetzung startuhr) {
        var bahnuhren = laufstand.bahnen().stream().filter(Bahn::telefonzeit)
                .map(bahn -> aktuelleUhren.get(bahn.telefon())).toList();
        long pruefzeit = bahnuhren.isEmpty() ? spaetesterStart : Math.addExact(spaetesterStart, laufdauer);
        return Uhrabgleich.bereit(laufstand.starttelefon() != null, startuhr, bahnuhren, pruefzeit);
    }

    synchronized boolean bereit(long jetzt) {
        boolean telefonstart = laufstand.starttelefon() != null;
        return zustand == Zustand.BEREIT && uhrenBereit(
                telefonstart ? Math.addExact(jetzt, FREIGABEDAUER) : jetzt,
                aktuelleUhren.get(laufstand.starttelefon()));
    }

    long abgleichhorizont(long jetzt) {
        return Math.addExact(Math.addExact(jetzt, laufdauer), FREIGABEDAUER);
    }

    synchronized Uhrabgleich.Schaetzung schaetzung(UUID geraet, UUID epoche, UUID id) {
        var uhren = bekannteUhren.get(geraet);
        var uhr = uhren == null ? null : uhren.get(id);
        return uhr != null && uhr.epoche().equals(epoche) ? uhr : null;
    }

    synchronized boolean tischstartReservieren(long jetzt) {
        if (zustand != Zustand.BEREIT || laufstand.starttelefon() != null || !uhrenBereit(jetzt, null)) {
            return false;
        }
        zustand = Zustand.TISCHSTART;
        return true;
    }

    synchronized void tischstartAbbrechen() {
        if (zustand == Zustand.TISCHSTART) {
            zustand = Zustand.BEREIT;
        }
    }

    synchronized boolean startErfassen(ZeitnahmeEreignis.Start ereignis) {
        if (zustand != Zustand.TISCHSTART || !id.equals(ereignis.versuch()) || ereignis.geraet() != null) {
            return false;
        }
        horizont = Math.addExact(ereignis.zeit(), laufdauer);
        starten(ereignis, false);
        return true;
    }

    synchronized boolean startMelden(UUID freigabeId, UUID geraet, ZeitnahmeEreignis.Start ereignis, Audio audio,
            long spaetesterTon) {
        if (geraet == null || !geraet.equals(laufstand.starttelefon())) {
            return false;
        }
        var meldung = new Startmeldung(geraet, ereignis, Objects.requireNonNull(audio), spaetesterTon);
        var bisher = meldungen.get(freigabeId);
        if (meldung.equals(bisher)) {
            return true;
        }
        if (freigabe == null || !freigabe.id().equals(freigabeId)
                || (zustand != Zustand.FREIGEGEBEN && zustand != Zustand.START_UNSICHER)
                || (bisher != null && bisher.ereignis() != null && !bisher.ereignis().equals(ereignis))) {
            return false;
        }
        if (audio == Audio.AUSGEFALLEN) {
            if (ereignis != null || bisher != null) {
                return false;
            }
            meldungen.put(freigabeId, meldung);
            freigabe = null;
            ziele.clear();
            zielIds.clear();
            stoppuhrenLeeren();
            zustand = Zustand.BEREIT;
            return true;
        }
        if (ereignis != null && !passtInFreigabe(ereignis)) {
            return false;
        }
        meldungen.put(freigabeId, meldung);
        if (ereignis != null && audio == Audio.PUENKTLICH && spaetesterTon >= freigabe.beginn()
                && spaetesterTon >= schreibtischzeit(ereignis) && spaetesterTon <= freigabe.frist()) {
            starten(ereignis, false);
        } else {
            unsichererStart = ereignis;
            zustand = Zustand.START_UNSICHER;
        }
        return true;
    }

    private boolean passtInFreigabe(ZeitnahmeEreignis.Start ereignis) {
        if (!id.equals(ereignis.versuch()) || !laufstand.starttelefon().equals(ereignis.geraet())
                || !freigabe.uhr().equals(ereignis.schaetzung())) {
            return false;
        }
        long zeit = schreibtischzeit(ereignis);
        long grenze = ereignis.schaetzung().grenze(zeit);
        return Math.subtractExact(zeit, grenze) >= freigabe.beginn() && Math.addExact(zeit, grenze) <= freigabe.frist();
    }

    synchronized void fristPruefen(long jetzt) {
        if (zustand == Zustand.FREIGEGEBEN && jetzt >= freigabe.frist()) {
            zustand = Zustand.START_UNSICHER;
        }
    }

    synchronized boolean bestaetigungMoeglich(long jetzt) {
        return aufloesungMoeglich(jetzt) && unsichererStart != null;
    }

    synchronized boolean aufloesungMoeglich(long jetzt) {
        fristPruefen(jetzt);
        return zustand == Zustand.START_UNSICHER && jetzt >= freigabe.frist();
    }

    synchronized boolean startBestaetigen(long jetzt, boolean hornGehoert) {
        if (!hornGehoert || !bestaetigungMoeglich(jetzt)) {
            return false;
        }
        starten(unsichererStart, true);
        return true;
    }

    synchronized boolean verwerfen(long jetzt) {
        if (!aufloesungMoeglich(jetzt)) {
            return false;
        }
        ziele.clear();
        zielIds.clear();
        meldungen.clear();
        stoppuhrenLeeren();
        schliessen();
        return true;
    }

    private void starten(ZeitnahmeEreignis.Start ereignis, boolean unsicher) {
        start = ereignis;
        startUnsicher = unsicher;
        zustand = Zustand.LAEUFT;
    }

    synchronized boolean zielErfassen(ZeitnahmeEreignis.Ziel ereignis) {
        var bisher = zielIds.get(ereignis.id());
        if (bisher != null) {
            return bisher.equals(ereignis);
        }
        if (zustand == Zustand.BEREIT || geschlossen() || !id.equals(ereignis.versuch())
                || stoppuhrIds.containsKey(ereignis.id()) || ziele.containsKey(ereignis.bahn())
                || !berechtigteBahn(ereignis)) {
            return false;
        }
        try {
            long zeit = ereignis.schaetzung().schreibtischzeit(ereignis.zeit());
            Math.addExact(zeit, ereignis.schaetzung().grenze(zeit));
            if (start != null && dauer(zeit) <= 0) {
                return false;
            }
        } catch (ArithmeticException ex) {
            return false;
        }
        ziele.put(ereignis.bahn(), ereignis);
        zielIds.put(ereignis.id(), ereignis);
        return true;
    }

    private boolean berechtigteBahn(ZeitnahmeEreignis.Ziel ziel) {
        var uhren = bekannteUhren.get(ziel.geraet());
        return uhren != null && ziel.schaetzung().equals(uhren.get(ziel.schaetzung().id()))
                && laufstand.bahnen().stream().anyMatch(bahn -> bahn.nummer() == ziel.bahn()
                        && bahn.telefonzeit() && ziel.geraet().equals(bahn.telefon()));
    }

    synchronized List<Ergebnis> ergebnisse() {
        Stream<Ergebnis> erfasst = start == null ? Stream.empty() : ziele.values().stream().map(this::ergebnis);
        Stream<Ergebnis> manuell = Stream.concat(
                handyZeiten.values().stream().filter(zeit -> !tischZeiten.containsKey(zeit.bahn())), tischZeiten.values().stream())
                .map(zeit -> new Ergebnis(zeit.bahn(), zeit.dauer(), false,
                        zeit.geraet() == null ? Quelle.STOPPUHR_TISCH : Quelle.STOPPUHR_HANDY, zeit.id()));
        return Stream.concat(erfasst, manuell).toList();
    }

    synchronized boolean stoppuhrErfassen(ZeitnahmeEreignis.Stoppuhr ereignis) {
        var bisher = stoppuhrIds.get(ereignis.id());
        if (bisher != null) {
            return bisher.equals(ereignis);
        }
        if (zustand == Zustand.BEREIT || geschlossen() || !id.equals(ereignis.versuch())
                || zielIds.containsKey(ereignis.id()) || laufstand.bahnen().stream().noneMatch(bahn ->
                        bahn.nummer() == ereignis.bahn() && !bahn.telefonzeit()
                                && (ereignis.geraet() == null || ereignis.geraet().equals(bahn.telefon())))) {
            return false;
        }
        if (ereignis.geraet() != null && handyZeiten.putIfAbsent(ereignis.bahn(), ereignis) != null) {
            return false;
        }
        if (ereignis.geraet() == null) {
            tischZeiten.put(ereignis.bahn(), ereignis);
        }
        stoppuhrIds.put(ereignis.id(), ereignis);
        return true;
    }

    private void stoppuhrenLeeren() {
        handyZeiten.clear();
        tischZeiten.clear();
        stoppuhrIds.clear();
    }

    private Ergebnis ergebnis(ZeitnahmeEreignis.Ziel ziel) {
        long startzeit = schreibtischzeit(start);
        long zielzeit = ziel.schaetzung().schreibtischzeit(ziel.zeit());
        long zielgrenze = ziel.schaetzung().grenze(zielzeit);
        long startgrenze = start.schaetzung() == null ? 0 : start.schaetzung().grenze(startzeit);
        long dauer = dauer(zielzeit);
        if (dauer <= 0) {
            return new Ergebnis(ziel.bahn(), dauer, true, Quelle.ZIEL, ziel.id());
        }
        boolean unsicher = startUnsicher || Math.addExact(zielzeit, zielgrenze) > horizont
                || startgrenze > Uhrabgleich.BUDGET || zielgrenze > Uhrabgleich.BUDGET - startgrenze;
        return new Ergebnis(ziel.bahn(), dauer, unsicher, Quelle.ZIEL, ziel.id());
    }

    private long dauer(long zielzeit) {
        try {
            return Math.subtractExact(zielzeit, schreibtischzeit(start));
        } catch (ArithmeticException ex) {
            return -1;
        }
    }

    private static long schreibtischzeit(ZeitnahmeEreignis.Start start) {
        return start.schaetzung() == null ? start.zeit() : start.schaetzung().schreibtischzeit(start.zeit());
    }

    synchronized void schliessen() {
        if (!geschlossen()) {
            zustand = Zustand.GESCHLOSSEN;
        }
    }

    synchronized void alsUebernommenSchliessen() {
        zustand = Zustand.UEBERNOMMEN;
    }

    synchronized boolean geschlossen() {
        return zustand == Zustand.GESCHLOSSEN || zustand == Zustand.UEBERNOMMEN;
    }

    synchronized Zustand zustand() {
        return zustand;
    }

    synchronized Optional<ZeitnahmeEreignis.Start> start() {
        return Optional.ofNullable(start);
    }
}
