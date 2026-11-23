package de.df.jauswertung.gui.plugins.zeitnahme;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ManuelleZeitnahmeTest {
    @Test
    void stoppuhrBrauchtKeineGueltigeUhrUndBehaeltDieErsteTelefonmeldung() {
        UUID telefon = UUID.randomUUID();
        var sitzung = new ZeitnahmeVersuch.Sitzung();
        var v = sitzung.anlegen(new ZeitnahmeVersuch.Laufstand(UUID.randomUUID(), UUID.randomUUID(), "Staffel",
                List.of(new ZeitnahmeVersuch.Bahn(3, "Team A", telefon, false),
                        new ZeitnahmeVersuch.Bahn(4, "Team B", null, false),
                        new ZeitnahmeVersuch.Bahn(5, "Team C", telefon, false)), null), 90_000_000);
        assertTrue(v.tischstartReservieren(1));
        assertTrue(v.startErfassen(new ZeitnahmeEreignis.Start(UUID.randomUUID(), v.id(), null, 1, null)));
        long zeit = ManuelleZeitnahme.dauer("1:05,20").orElseThrow();
        var meldung = new ZeitnahmeEreignis.Stoppuhr(UUID.randomUUID(), v.id(), telefon, 3, zeit);
        assertFalse(sitzung.stoppuhrEmpfangen(new ZeitnahmeEreignis.Stoppuhr(UUID.randomUUID(), v.id(),
                UUID.randomUUID(), 3, zeit)));
        assertFalse(sitzung.stoppuhrEmpfangen(new ZeitnahmeEreignis.Stoppuhr(UUID.randomUUID(), v.id(),
                telefon, 4, zeit)));
        assertTrue(sitzung.stoppuhrEmpfangen(meldung));
        assertTrue(sitzung.stoppuhrEmpfangen(meldung));
        assertFalse(sitzung.stoppuhrEmpfangen(new ZeitnahmeEreignis.Stoppuhr(meldung.id(), v.id(), telefon, 3, zeit + 1)));
        v.abgleich(telefon, new Uhrabgleich.Schaetzung(UUID.randomUUID(), UUID.randomUUID(), 0, 0, 99_000, 20));
        assertTrue(sitzung.stoppuhrEmpfangen(meldung));
        assertTrue(sitzung.stoppuhrEmpfangen(new ZeitnahmeEreignis.Stoppuhr(UUID.randomUUID(), v.id(), telefon, 5, 60_000_000)));
        assertTrue(v.stoppuhrErfassen(new ZeitnahmeEreignis.Stoppuhr(UUID.randomUUID(), v.id(), null, 4, 70_010_000)));
        assertEquals(List.of(65_200_000L, 60_000_000L, 70_010_000L), v.ergebnisse().stream().map(ZeitnahmeVersuch.Ergebnis::dauer).toList());
        assertEquals(List.of(ZeitnahmeVersuch.Quelle.STOPPUHR_HANDY, ZeitnahmeVersuch.Quelle.STOPPUHR_HANDY,
                ZeitnahmeVersuch.Quelle.STOPPUHR_TISCH),
                v.ergebnisse().stream().map(ZeitnahmeVersuch.Ergebnis::quelle).toList());
    }

    @Test
    void akzeptiertPositiveDezimalzeitenUndWeistUngueltigeEingabeAb() {
        assertEquals(65_200_000L, ManuelleZeitnahme.dauer("65.20").orElseThrow());
        assertEquals(10_000L, ManuelleZeitnahme.dauer("0,01").orElseThrow());
        for (String ungueltig : List.of("", "0", "-4", "1:65", "NaN", "99999999999999999", "0,0000001")) {
            assertTrue(ManuelleZeitnahme.dauer(ungueltig).isEmpty(), ungueltig);
        }
    }
}
