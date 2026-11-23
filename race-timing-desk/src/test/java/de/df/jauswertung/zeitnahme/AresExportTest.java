package de.df.jauswertung.zeitnahme;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AresExportTest {
    @TempDir
    Path ordner;

    @Test
    void schreibtOriginaleBahnenUndRundetAufHundertstel() throws Exception {
        var versuch = lauf();
        zeit(versuch, 2, 33_125_000);
        zeit(versuch, 7, 65_204_999);
        Path datei = ordner.resolve("lauf.txt");
        AresExport.speichern(datei, 17, 8, versuch, versuch.ergebnisse());

        var zeilen = Files.readAllLines(datei);
        assertEquals(List.of(AresExport.KOPF, "17;0;7;0;2;0;0;33130;;0;0;;0;0;;0",
                "17;0;7;0;7;0;0;65200;;0;0;;0;0;;0"), zeilen);
        assertTrue(versuch.geschlossen());
    }

    @Test
    void fehlendeOderGeaenderteZeitenWerdenNichtExportiert() {
        var versuch = lauf();
        zeit(versuch, 2, 33_120_000);
        var vorzeitig = versuch.ergebnisse();
        Path datei = ordner.resolve("lauf.txt");
        assertThrows(IllegalArgumentException.class, () -> AresExport.speichern(datei, 17, 8, versuch, vorzeitig));
        zeit(versuch, 7, 65_200_000);
        assertThrows(IllegalArgumentException.class, () -> AresExport.speichern(datei, 17, 8, versuch, vorzeitig));
        assertFalse(Files.exists(datei));
        assertFalse(versuch.geschlossen());
    }

    @Test
    void vorhandeneDateiUndErgebnisseBleibenBeiFehlerErhalten() throws Exception {
        var versuch = lauf();
        zeit(versuch, 2, 33_120_000);
        zeit(versuch, 7, 65_200_000);
        var ergebnisse = versuch.ergebnisse();
        Path datei = Files.writeString(ordner.resolve("lauf.txt"), "vorhanden");

        assertThrows(FileAlreadyExistsException.class, () -> AresExport.speichern(datei, 17, 8, versuch, ergebnisse));
        assertEquals("vorhanden", Files.readString(datei));
        assertEquals(ergebnisse, versuch.ergebnisse());
        assertFalse(versuch.geschlossen());
    }

    private static ZeitnahmeVersuch lauf() {
        var stand = new ZeitnahmeVersuch.Laufstand(UUID.randomUUID(), UUID.randomUUID(), "50 m",
                List.of(new ZeitnahmeVersuch.Bahn(7, "Bahn 7", null, false),
                        new ZeitnahmeVersuch.Bahn(2, "Bahn 2", null, false)), null);
        var versuch = new ZeitnahmeVersuch(stand, 120_000_000);
        long jetzt = Startsignal.jetzt();
        assertTrue(versuch.tischstartReservieren(jetzt));
        assertTrue(versuch.startErfassen(new ZeitnahmeEreignis.Start(UUID.randomUUID(), versuch.id(), null, jetzt, null)));
        return versuch;
    }

    private static void zeit(ZeitnahmeVersuch versuch, int bahn, long dauer) {
        assertTrue(versuch.stoppuhrErfassen(new ZeitnahmeEreignis.Stoppuhr(UUID.randomUUID(), versuch.id(), null, bahn, dauer)));
    }
}
