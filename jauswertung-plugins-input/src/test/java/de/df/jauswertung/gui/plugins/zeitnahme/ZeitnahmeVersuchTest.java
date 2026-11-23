package de.df.jauswertung.gui.plugins.zeitnahme;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ZeitnahmeVersuchTest {
    private final UUID starter = UUID.randomUUID();
    private final UUID telefon = UUID.randomUUID();
    private final UUID epoche = UUID.randomUUID();
    private final Uhrabgleich.Schaetzung uhr = uhr(0);

    private Uhrabgleich.Schaetzung uhr(long unsicherheit) {
        return new Uhrabgleich.Schaetzung(UUID.randomUUID(), epoche, 0, 0, unsicherheit, 0);
    }

    private ZeitnahmeVersuch versuch(UUID starttelefon) {
        var lauf = new ZeitnahmeVersuch.Laufstand(UUID.randomUUID(), UUID.randomUUID(), "Staffel", List.of(
                new ZeitnahmeVersuch.Bahn(3, "Team A", telefon, true),
                new ZeitnahmeVersuch.Bahn(4, "Team B", telefon, true)), starttelefon);
        var versuch = new ZeitnahmeVersuch(lauf, 2_000_000);
        versuch.abgleich(starter, uhr);
        versuch.abgleich(telefon, uhr);
        return versuch;
    }

    private ZeitnahmeEreignis.Ziel ziel(ZeitnahmeVersuch v, UUID geraet, int bahn, long zeit, Uhrabgleich.Schaetzung uhr) {
        return new ZeitnahmeEreignis.Ziel(UUID.randomUUID(), v.id(), geraet, bahn, zeit, uhr);
    }

    @Test
    void verarbeitetVerspaeteteZieleEinmalUndJeBahn() {
        var v = versuch(starter);
        var anfrage = UUID.randomUUID();
        var freigabe = v.startfreigabe(starter, anfrage, epoche, uhr.id(), 0).orElseThrow();
        assertEquals(freigabe, v.startfreigabe(starter, anfrage, epoche, uhr.id(), 10).orElseThrow());
        assertTrue(v.startfreigabe(telefon, UUID.randomUUID(), epoche, uhr.id(), 0).isEmpty());
        var ziel = ziel(v, telefon, 3, 1_500_000, uhr);
        assertTrue(v.zielErfassen(ziel));
        assertTrue(v.zielErfassen(ziel));
        assertFalse(v.zielErfassen(new ZeitnahmeEreignis.Ziel(ziel.id(), v.id(), telefon, 3, 1_500_001, uhr)));
        assertFalse(v.zielErfassen(ziel(v, telefon, 3, 1_600_000, uhr)));
        assertFalse(v.zielErfassen(ziel(v, starter, 4, 1_550_000, uhr)));
        assertTrue(v.ergebnisse().isEmpty());
        var start = new ZeitnahmeEreignis.Start(UUID.randomUUID(), v.id(), starter, 100_000, uhr);
        assertTrue(v.startMelden(freigabe.id(), starter, start, ZeitnahmeVersuch.Audio.PUENKTLICH, 110_000));
        assertTrue(v.zielErfassen(ziel(v, telefon, 4, 1_750_000, uhr)));
        assertEquals(List.of(1_400_000L, 1_650_000L), v.ergebnisse().stream().map(ZeitnahmeVersuch.Ergebnis::dauer).toList());
        v.schliessen();
        assertTrue(v.zielErfassen(ziel));
        assertFalse(v.zielErfassen(ziel(v, telefon, 4, 1_750_000, uhr)));
    }

    @Test
    void verweigertZweiTelefoneFuerDieselbeBahn() {
        assertThrows(IllegalArgumentException.class, () -> new ZeitnahmeVersuch.Laufstand(
                UUID.randomUUID(), UUID.randomUUID(), "Staffel", List.of(
                        new ZeitnahmeVersuch.Bahn(3, "Team A", telefon, true),
                        new ZeitnahmeVersuch.Bahn(3, "Team A", starter, true)), null));
    }

    @Test
    void korrekteFreigabeUndBeobachtungSindVoraussetzungFuerBestaetigung() {
        var v = versuch(starter);
        var freigabe = v.startfreigabe(starter, UUID.randomUUID(), epoche, uhr.id(), 10).orElseThrow();
        var start = new ZeitnahmeEreignis.Start(UUID.randomUUID(), v.id(), starter, 100, uhr);
        assertTrue(v.startMelden(freigabe.id(), starter, start, ZeitnahmeVersuch.Audio.UNBEKANNT, 0));
        assertTrue(v.zielErfassen(ziel(v, telefon, 3, 1_500_000, uhr)));
        assertFalse(v.startBestaetigen(freigabe.frist() - 1, true));
        assertFalse(v.startBestaetigen(freigabe.frist(), false));
        assertTrue(v.startBestaetigen(freigabe.frist(), true));
        assertTrue(v.ergebnisse().getFirst().unsicher());
    }

    @Test
    void abgelaufenerStartKannVerworfenAberNichtWiederGeoeffnetWerden() {
        var v = versuch(starter);
        var freigabe = v.startfreigabe(starter, UUID.randomUUID(), epoche, uhr.id(), 10).orElseThrow();
        var vorgemerkt = ziel(v, telefon, 3, 2_000, uhr);
        assertTrue(v.zielErfassen(vorgemerkt));
        v.fristPruefen(freigabe.frist());
        assertFalse(v.startBestaetigen(freigabe.frist(), true));
        assertTrue(v.verwerfen(freigabe.frist()));
        var start = new ZeitnahmeEreignis.Start(UUID.randomUUID(), v.id(), starter, 100, uhr);
        assertFalse(v.startMelden(freigabe.id(), starter, start, ZeitnahmeVersuch.Audio.PUENKTLICH, 100));
        assertFalse(v.zielErfassen(ziel(v, telefon, 3, 2_000, uhr)));
        assertFalse(v.zielErfassen(vorgemerkt));
    }

    @Test
    void meldetAudiofehlerUndMarkiertHorizontOderSchlechtereSchaetzung() {
        var v = versuch(starter);
        var freigabe = v.startfreigabe(starter, UUID.randomUUID(), epoche, uhr.id(), 0).orElseThrow();
        assertTrue(v.startMelden(freigabe.id(), starter, null, ZeitnahmeVersuch.Audio.AUSGEFALLEN, 0));
        assertTrue(v.start().isEmpty());
        freigabe = v.startfreigabe(starter, UUID.randomUUID(), epoche, uhr.id(), 0).orElseThrow();
        var start = new ZeitnahmeEreignis.Start(UUID.randomUUID(), v.id(), starter, 100, uhr);
        assertTrue(v.startMelden(freigabe.id(), starter, start, ZeitnahmeVersuch.Audio.PUENKTLICH, 100));
        var schwach = uhr(10_001);
        v.abgleich(telefon, schwach);
        assertTrue(v.zielErfassen(ziel(v, telefon, 3, 1_100_000, schwach)));
        assertTrue(v.zielErfassen(ziel(v, telefon, 4, 3_000_001, uhr)));
        assertTrue(v.ergebnisse().stream().allMatch(ZeitnahmeVersuch.Ergebnis::unsicher));
        var tisch = versuch(null);
        assertTrue(tisch.tischstartReservieren(0));
        assertTrue(tisch.startErfassen(new ZeitnahmeEreignis.Start(UUID.randomUUID(), tisch.id(), null, 1, null)));
        assertTrue(tisch.zielErfassen(ziel(tisch, telefon, 3, 2_000_001, uhr)));
        assertFalse(tisch.ergebnisse().getFirst().unsicher());
        assertTrue(tisch.zielErfassen(ziel(tisch, telefon, 4, 2_000_002, uhr)));
        assertTrue(tisch.ergebnisse().getLast().unsicher());
    }

    @Test
    void meldetVerspaetetenNachweisUndWeistVeralteteSchaetzungAb() {
        var v = versuch(starter);
        var neu = uhr(0);
        v.abgleich(starter, neu);
        assertTrue(v.startfreigabe(starter, UUID.randomUUID(), epoche, uhr.id(), 0).isEmpty());
        var freigabe = v.startfreigabe(starter, UUID.randomUUID(), epoche, neu.id(), 0).orElseThrow();
        var start = new ZeitnahmeEreignis.Start(UUID.randomUUID(), v.id(), starter, 100_000, neu);
        assertTrue(v.startMelden(freigabe.id(), starter, start, ZeitnahmeVersuch.Audio.PUENKTLICH, freigabe.frist() + 1));
        v.fristPruefen(freigabe.frist());
        assertTrue(v.start().isEmpty());
        assertTrue(v.startMelden(freigabe.id(), starter, start, ZeitnahmeVersuch.Audio.PUENKTLICH, 100_000));
        assertEquals(start, v.start().orElseThrow());
    }

    @Test
    void startetVirtuellenProbelaufMitBeidenStarterrollen() {
        var auswahl = new VirtuellerZeitnahmelauf.Auswahl(UUID.randomUUID(), UUID.randomUUID(), "Lauf 1", "Staffel",
                List.of(new VirtuellerZeitnahmelauf.Teilnehmer(3, "Team A"),
                        new VirtuellerZeitnahmelauf.Teilnehmer(4, "Team B"),
                        new VirtuellerZeitnahmelauf.Teilnehmer(5, "Team C")));
        var zuordnung = Map.of(3, telefon, 4, telefon, 5, UUID.randomUUID());
        assertEquals(19_200, Startsignal.ton(200).length);
        assertEquals(38_400, Startsignal.ton(Startsignal.STANDARD).length);
        assertEquals(144_000, Startsignal.ton(1500).length);
        assertThrows(IllegalArgumentException.class, () -> Startsignal.ton(199));
        assertThrows(IllegalArgumentException.class, () -> Startsignal.ton(1501));
        var sitzung = new ZeitnahmeVersuch.Sitzung();
        for (boolean telefonstart : List.of(false, true)) {
            var lauf = new VirtuellerZeitnahmelauf(sitzung, auswahl, telefonstart ? starter : null, zuordnung, 90_000_000, 0);
            var v = lauf.versuch();
            assertTrue(v.start().isEmpty());
            long beginn = 50_000;
            if (telefonstart) {
                var freigabe = lauf.startAnfordern(0);
                lauf.startErfassen(freigabe, beginn, ZeitnahmeVersuch.Audio.PUENKTLICH);
                for (int bahn : List.of(3, 4, 5)) {
                    lauf.zielErfassen(bahn, beginn + bahn * 1_000_000);
                    assertTrue(lauf.zielSenden(bahn));
                }
                assertTrue(v.ergebnisse().isEmpty());
                assertTrue(lauf.startSenden());
                assertTrue(lauf.startSenden());
            } else {
                assertTrue(v.tischstartReservieren(0));
                assertTrue(v.startErfassen(new ZeitnahmeEreignis.Start(UUID.randomUUID(), v.id(), null, beginn, null)));
                for (int bahn : List.of(3, 4, 5)) {
                    lauf.zielErfassen(bahn, beginn + bahn * 1_000_000);
                    assertTrue(lauf.zielSenden(bahn));
                }
            }
            assertEquals(List.of(3_000_000L, 4_000_000L, 5_000_000L),
                    v.ergebnisse().stream().map(ZeitnahmeVersuch.Ergebnis::dauer).toList());
            v.schliessen();
            sitzung.anlegen(v.laufstand(), 90_000_000);
            assertTrue(lauf.zielSenden(3));
            assertFalse(sitzung.zielEmpfangen(ziel(v, telefon, 3, beginn + 4_000_000, uhr)));
        }
    }
}
