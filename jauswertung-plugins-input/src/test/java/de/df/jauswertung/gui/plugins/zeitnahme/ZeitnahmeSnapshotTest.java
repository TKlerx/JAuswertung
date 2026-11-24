package de.df.jauswertung.gui.plugins.zeitnahme;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import de.df.jauswertung.gui.plugins.WettkampfZugriff;

class ZeitnahmeSnapshotTest {
    @Test
    void behaeltSnapshotBeiBelegtemWettkampfUndErholtSich() throws Exception {
        var zugriff = new WettkampfZugriff();
        var auswahl = new VirtuellerZeitnahmelauf.Auswahl(UUID.randomUUID(), UUID.randomUUID(), "Lauf 1", "Staffel",
                List.of(new VirtuellerZeitnahmelauf.Teilnehmer(3, "Team A")));
        var quelle = new AtomicReference<>(List.of(auswahl));
        var snapshot = new ZeitnahmeSnapshot(zugriff::versuchen, quelle::get);
        assertTrue(snapshot.aktualisieren());
        assertEquals(List.of(auswahl), snapshot.laeufe());

        var reservierung = zugriff.versuchen();
        assertNotNull(reservierung);
        try {
            quelle.set(List.of());
            assertFalse(snapshot.aktualisieren());
            assertTrue(snapshot.veraltet());
            assertEquals(List.of(auswahl), snapshot.laeufe());
            assertFalse(snapshot.validieren(auswahl));
        } finally {
            Thread.ofVirtual().start(reservierung).join();
        }

        assertTrue(snapshot.aktualisieren());
        assertFalse(snapshot.veraltet());
        assertTrue(snapshot.laeufe().isEmpty());
        assertFalse(snapshot.validieren(auswahl));
    }
}
