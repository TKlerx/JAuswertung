package de.df.jauswertung.gui.plugins.zeitnahme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class UhrabgleichTest {
    private final UUID epoche = UUID.randomUUID();

    @Test
    void berechnetVersatzUndUnsicherheitAuchBeiAsymmetrie() {
        var symmetrisch = Uhrabgleich.auswerten(new Uhrabgleich.Probe(epoche, 500, 2_000, 2_500, 5_000), 0);
        var asymmetrisch = Uhrabgleich.auswerten(new Uhrabgleich.Probe(epoche, 500, 2_000, 2_500, 13_000), 0);

        assertEquals(4_500, symmetrisch.schreibtischzeit(5_000));
        assertEquals(2_001, symmetrisch.unsicherheit());
        assertEquals(8_500, asymmetrisch.schreibtischzeit(13_000));
        assertEquals(6_001, asymmetrisch.unsicherheit());
        assertThrows(IllegalArgumentException.class,
                () -> Uhrabgleich.auswerten(new Uhrabgleich.Probe(epoche, 0, 1, 8, 3), 0));
    }

    @Test
    void prueftStartBahnUndHorizontJeNachZeitnahme() {
        var start = schaetzung(4_000, 0);
        var ziel = schaetzung(6_000, 10);

        assertTrue(Uhrabgleich.bereit(true, start, List.of(ziel), 0));
        assertFalse(Uhrabgleich.bereit(true, start, List.of(ziel), 1_000_000));
        assertTrue(Uhrabgleich.bereit(false, null, List.of(ziel), 1_000_000));
        assertTrue(Uhrabgleich.bereit(false, null, List.of(), 1_000_000));
        assertFalse(Uhrabgleich.bereit(true, null, List.of(), 1_000_000));
        assertTrue(Uhrabgleich.bereit(true, schaetzung(9_990, 10), List.of(), 1_000_000));
        assertFalse(Uhrabgleich.bereit(true, schaetzung(9_991, 10), List.of(), 1_000_000));
    }

    @Test
    void schneidetZweiAsymmetrischeProbenMitDriftreserve() {
        var abgleich = new Uhrabgleich();
        var erste = new Uhrabgleich.Schaetzung(UUID.randomUUID(), epoche, 0, 4_000, 6_000, 20);
        var zweite = new Uhrabgleich.Schaetzung(UUID.randomUUID(), epoche, 1_000_000, -4_000, 6_000, 20);
        abgleich.probeEmpfangen(erste, 46_000_000, Uhrabgleich.BUDGET);
        abgleich.probeEmpfangen(zweite, 46_000_000, Uhrabgleich.BUDGET);

        var uhr = abgleich.schaetzung();
        assertEquals(2_010, uhr.unsicherheit());
        assertEquals(-10, uhr.versatz());
        assertTrue(Uhrabgleich.bereit(true, uhr, List.of(uhr), 46_000_000));
    }

    @Test
    void plantAuffrischungVorAblaufDesBudgets() {
        var schaetzung = schaetzung(2_000, 100);
        assertEquals(2_000_000, schaetzung.naechsteAuffrischung(0, 60_000_000, 30_000_000, 8_400));
        assertEquals(1_000_000, schaetzung.naechsteAuffrischung(0, 60_000_000, 1_000_000, 8_400));
        assertEquals(0, schaetzung.naechsteAuffrischung(0, 65_000_000, 30_000_000, 8_400));
    }

    @Test
    void beschleunigtNachZweiSchlechtenProbenOhneGueltigeSchaetzungZuVerwerfen() {
        var abgleich = new Uhrabgleich();
        var gut = schaetzung(2_000, 100);
        var schlecht = schaetzung(9_000, 100);
        abgleich.probeEmpfangen(gut, 1_000_000, 5_000);
        abgleich.probeEmpfangen(schlecht, 1_000_000, 5_000);
        assertFalse(abgleich.probensalveNoetig(1_000_000, 5_000));
        abgleich.probeFehlgeschlagen();
        assertTrue(abgleich.probensalveNoetig(1_000_000, 5_000));
        assertEquals(gut, abgleich.schaetzung());
        abgleich.probeEmpfangen(gut, 1_000_000, 5_000);
        assertFalse(abgleich.probensalveNoetig(1_000_000, 5_000));
        assertTrue(abgleich.probensalveNoetig(31_000_000, 5_000));
    }

    private Uhrabgleich.Schaetzung schaetzung(long unsicherheit, int driftPpm) {
        return new Uhrabgleich.Schaetzung(UUID.randomUUID(), epoche, 0, 0, unsicherheit, driftPpm);
    }
}
