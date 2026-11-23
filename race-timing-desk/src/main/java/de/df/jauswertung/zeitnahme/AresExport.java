package de.df.jauswertung.zeitnahme;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class AresExport {
    static final String KOPF = "event;round;heat;lap;lane;idStatus;rank;time;result;mod;btime;bresult;bmod;dtime;dresult;dmod";

    private AresExport() {
    }

    static long hundertstel(long mikrosekunden) {
        if (mikrosekunden < 5_000) {
            throw new IllegalArgumentException();
        }
        long gerundet = (mikrosekunden - 5_000) / 10_000 + 1;
        if (gerundet > Integer.MAX_VALUE / 10) {
            throw new IllegalArgumentException();
        }
        return gerundet;
    }

    static void speichern(Path datei, int wettbewerb, int lauf, ZeitnahmeVersuch versuch,
            List<ZeitnahmeVersuch.Ergebnis> bestaetigt) throws IOException {
        if (wettbewerb < 1 || wettbewerb > 999 || lauf < 1 || lauf > 999) {
            throw new IllegalArgumentException();
        }
        Map<Integer, Long> zeiten = new HashMap<>();
        for (var ergebnis : bestaetigt) {
            if (zeiten.putIfAbsent(ergebnis.bahn(), hundertstel(ergebnis.dauer()) * 10L) != null) {
                throw new IllegalArgumentException();
            }
        }
        var bahnen = versuch.laufstand().bahnen().stream().map(ZeitnahmeVersuch.Bahn::nummer).sorted().toList();
        if (zeiten.size() != bahnen.size() || !zeiten.keySet().containsAll(bahnen)
                || bahnen.stream().anyMatch(bahn -> bahn < 1 || bahn > 10)) {
            throw new IllegalArgumentException();
        }
        var daten = new StringBuilder(KOPF).append("\r\n");
        for (int bahn : bahnen) {
            daten.append(wettbewerb).append(";0;").append(lauf - 1).append(";0;").append(bahn)
                    .append(";0;0;").append(zeiten.get(bahn)).append(";;0;0;;0;0;;0\r\n");
        }

        Path ziel = datei.toAbsolutePath();
        Path temporaer = Files.createTempFile(ziel.getParent(), ".zeitnahme-", ".tmp");
        try {
            Files.writeString(temporaer, daten, StandardCharsets.US_ASCII);
            synchronized (versuch) {
                if (versuch.start().isEmpty() || !versuch.ergebnisse().equals(bestaetigt)) {
                    throw new IllegalArgumentException();
                }
                Files.move(temporaer, ziel);
                versuch.alsUebernommenSchliessen();
            }
        } catch (IOException | RuntimeException ex) {
            try {
                Files.deleteIfExists(temporaer);
            } catch (IOException loeschen) {
                ex.addSuppressed(loeschen);
            }
            throw ex;
        }
    }
}
