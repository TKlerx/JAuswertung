package de.df.jauswertung.gui.plugins.zeitnahme;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class ZeitnahmeDienstTest {
    @TempDir
    Path verzeichnis;
    private final ObjectMapper json = new ObjectMapper();
    private final UUID telefon = UUID.randomUUID();

    @Test
    void startfreigabeUndZielWiederholungNachEpochenwechselLaufenUeberHttps() throws Exception {
        var schluessel = zertifikat();
        var sitzung = new ZeitnahmeVersuch.Sitzung();
        UUID starter = UUID.randomUUID();
        var stand = new ZeitnahmeVersuch.Laufstand(UUID.randomUUID(), UUID.randomUUID(), "Staffel", List.of(
                new ZeitnahmeVersuch.Bahn(3, "Team A", telefon, true),
                new ZeitnahmeVersuch.Bahn(4, "Team B", telefon, true)), starter);
        var versuch = sitzung.anlegen(stand, 45_000_000);
        var adresse = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0);
        var tls = ZeitnahmeDienst.tls(schluessel, "testpass".toCharArray(), false);
        String alterZugang;
        try (var dienst = new ZeitnahmeDienst(adresse, tls, sitzung, (lauf, aktion) -> {
            aktion.run();
            return true;
        }); var client = HttpClient.newBuilder()
                .sslContext(ZeitnahmeDienst.tls(schluessel, "testpass".toCharArray(), true)).build()) {
            dienst.laufSetzen(versuch, 400, false);
            String url = "https://127.0.0.1:" + dienst.port();
            String startzugang = paaren(dienst, starter, url, client);
            String zielzugang = paaren(dienst, telefon, url, client);
            alterZugang = zielzugang;
            UUID startepoch = UUID.randomUUID();
            UUID zielepoch = UUID.randomUUID();
            var startuhr = abgleich(client, url, startzugang, versuch.id(), startepoch);
            var zieluhr = abgleich(client, url, zielzugang, versuch.id(), zielepoch);
            var anfrage = Map.of("versuch", versuch.id(), "epoche", startepoch, "schaetzung", startuhr.get("id").asText(),
                    "anfrage", UUID.randomUUID());
            assertEquals(403, post(client, url, "start", zielzugang, anfrage).statusCode());
            var grant = json.readTree(post(client, url, "start", startzugang, anfrage).body());
            assertEquals(400, grant.get("horndauer").asInt());
            assertEquals(grant, json.readTree(post(client, url, "start", startzugang, anfrage).body()));
            var fehler = Map.of("versuch", versuch.id(), "freigabe", grant.get("freigabe").asText(), "audio", "AUSGEFALLEN");
            assertEquals(200, post(client, url, "startmeldung", startzugang, fehler).statusCode());
            assertEquals(ZeitnahmeVersuch.Zustand.BEREIT, versuch.zustand());

            var neu = Map.of("versuch", versuch.id(), "epoche", startepoch, "schaetzung", startuhr.get("id").asText(),
                    "anfrage", UUID.randomUUID());
            grant = json.readTree(post(client, url, "start", startzugang, neu).body());
            long startzeit = grant.get("beginn").asLong() + 20_000 - startuhr.get("versatz").asLong();
            var start = Map.of("versuch", versuch.id(), "freigabe", grant.get("freigabe").asText(), "audio", "UNBEKANNT",
                    "ereignis", UUID.randomUUID(), "zeit", startzeit, "epoche", startepoch,
                    "schaetzung", startuhr.get("id").asText());
            assertEquals(200, post(client, url, "startmeldung", startzugang, start).statusCode());
            assertEquals(ZeitnahmeVersuch.Zustand.START_UNSICHER, versuch.zustand());
            assertFalse(json.readTree(post(client, url, "lauf", zielzugang, Map.of()).body()).has("startZeitUs"));
            long frist = grant.get("frist").asLong();
            assertFalse(versuch.startBestaetigen(frist - 1, true));
            assertTrue(versuch.startBestaetigen(frist, true));
            var gestartet = json.readTree(post(client, url, "lauf", zielzugang, Map.of()).body());
            assertEquals(startzeit + startuhr.get("versatz").asLong(),
                    gestartet.path("startZeitUs").asLong(Long.MIN_VALUE));
            var tischuhr = json.readTree(post(client, url, "uhr", zielzugang,
                    Map.of("versuch", versuch.id(), "epoche", zielepoch)).body());
            assertEquals(tischuhr.path("schreibtischEpoche"), gestartet.path("schreibtischEpoche"));
            long zielzeit = startzeit + startuhr.get("versatz").asLong() + 5_000_000 - zieluhr.get("versatz").asLong();
            var ziel = Map.of("versuch", versuch.id(), "ereignis", UUID.randomUUID(), "bahn", 3, "zeit", zielzeit,
                    "epoche", zielepoch, "schaetzung", zieluhr.get("id").asText());
            assertEquals(409, post(client, url, "ziel", startzugang, ziel).statusCode());
            assertEquals(200, post(client, url, "ziel", zielzugang, ziel).statusCode());
            abgleich(client, url, zielzugang, versuch.id(), UUID.randomUUID());
            versuch.alsUebernommenSchliessen();
            dienst.laufSetzen(sitzung.anlegen(stand, 45_000_000), 400, false);
            assertFalse(json.readTree(post(client, url, "lauf", zielzugang, Map.of()).body()).has("startZeitUs"));
            assertEquals(200, post(client, url, "ziel", zielzugang, ziel).statusCode());
            assertEquals(5_000_000, versuch.ergebnisse().getFirst().dauer());
            assertTrue(versuch.ergebnisse().getFirst().unsicher());
        }
        try (var neu = new ZeitnahmeDienst(adresse, tls, new ZeitnahmeVersuch.Sitzung(), (lauf, aktion) -> false);
                var client = HttpClient.newBuilder()
                        .sslContext(ZeitnahmeDienst.tls(schluessel, "testpass".toCharArray(), true)).build()) {
            assertEquals(401, post(client, "https://127.0.0.1:" + neu.port(), "lauf", alterZugang, Map.of()).statusCode());
        }
    }

    private String paaren(ZeitnahmeDienst dienst, UUID rolle, String url, HttpClient client) throws Exception {
        var antwort = post(client, url, "paaren", null, Map.of("code", dienst.code(rolle), "name", "Testgerät"));
        assertEquals(200, antwort.statusCode());
        return json.readTree(antwort.body()).get("zugang").asText();
    }

    private JsonNode abgleich(HttpClient client, String url, String zugang, UUID versuch, UUID epoche) throws Exception {
        var austausch = json.readTree(post(client, url, "uhr", zugang, Map.of("versuch", versuch, "epoche", epoche)).body());
        assertTrue(austausch.has("schreibtischEpoche"));
        var probe = post(client, url, "probe", zugang, Map.of("probe", austausch.get("probe").asText(), "epoche", epoche,
                "telefonGesendet", austausch.get("schreibtischEmpfangen").asLong() - 10_000,
                "telefonEmpfangen", austausch.get("schreibtischGesendet").asLong() - 8_000));
        assertEquals(200, probe.statusCode());
        return json.readTree(probe.body());
    }

    @Test
    void erlaubtNurVertrauenswuerdigesHttpsUndEinmaligeKopplung() throws Exception {
        var schluessel = zertifikat();
        var sitzung = new ZeitnahmeVersuch.Sitzung();
        var stand = new ZeitnahmeVersuch.Laufstand(UUID.randomUUID(), UUID.randomUUID(), "Staffel", List.of(
                new ZeitnahmeVersuch.Bahn(4, "Team A", telefon, false),
                new ZeitnahmeVersuch.Bahn(5, "Team B", null, false)), null);
        var versuch = sitzung.anlegen(stand, 90_000_000);
        var dienst = new ZeitnahmeDienst(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0),
                ZeitnahmeDienst.tls(schluessel, "testpass".toCharArray(), false), sitzung,
                (lauf, aktion) -> false);
        try (dienst; var client = HttpClient.newBuilder()
                .sslContext(ZeitnahmeDienst.tls(schluessel, "testpass".toCharArray(), true)).build();
                var ohneVertrauen = HttpClient.newHttpClient()) {
            dienst.laufSetzen(versuch, 400, false);
            String url = "https://127.0.0.1:" + dienst.port();
            assertThrows(javax.net.ssl.SSLHandshakeException.class,
                    () -> post(ohneVertrauen, url, "paaren", null, Map.of()));
            assertThrows(java.io.IOException.class,
                    () -> post(client, url.replace("https:", "http:"), "paaren", null, Map.of()));
            String code = dienst.code(telefon);
            var paarung = Map.of("code", code, "name", "Bahn 4");
            var antwort = post(client, url, "paaren", null, paarung);
            assertEquals(200, antwort.statusCode());
            String zugang = json.readTree(antwort.body()).get("zugang").asText();
            assertEquals(403, post(client, url, "paaren", null, paarung).statusCode());
            assertEquals(401, post(client, url, "lauf", null, Map.of()).statusCode());
            JsonNode lauf = json.readTree(post(client, url, "lauf", zugang, Map.of()).body());
            assertEquals(4, lauf.get("bahnen").get(0).get("nummer").asInt());
            assertFalse(lauf.get("abgleich").asBoolean());
            assertTrue(versuch.tischstartReservieren(50));
            assertTrue(versuch.startErfassen(new ZeitnahmeEreignis.Start(UUID.randomUUID(), versuch.id(), null, 50, null)));
            var messung = Map.of("versuch", versuch.id(), "ereignis", UUID.randomUUID(), "bahn", 4, "dauer", 65_200_000L);
            assertEquals(200, post(client, url, "stoppuhr", zugang, messung).statusCode());
            versuch.alsUebernommenSchliessen();
            assertEquals(200, post(client, url, "stoppuhr", zugang, messung).statusCode());
            assertEquals(409, post(client, url, "stoppuhr", zugang, Map.of("versuch", versuch.id(),
                    "ereignis", UUID.randomUUID(), "bahn", 4, "dauer", 65_300_000L)).statusCode());
            assertEquals(65_200_000, versuch.ergebnisse().getFirst().dauer());
        }
    }

    private HttpResponse<String> post(HttpClient client, String url, String pfad, String zugang, Map<String, ?> wert)
            throws Exception {
        var anfrage = HttpRequest.newBuilder(URI.create(url + "/api/" + pfad))
                .timeout(java.time.Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(wert)));
        if (zugang != null) {
            anfrage.header("Authorization", "Bearer " + zugang);
        }
        return client.send(anfrage.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Path zertifikat() throws Exception {
        Path datei = verzeichnis.resolve("lokal.p12");
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        var prozess = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "lokal",
                "-dname", "CN=localhost", "-ext", "SAN=ip:127.0.0.1,dns:localhost", "-keyalg", "EC", "-validity", "1",
                "-storetype", "PKCS12", "-keystore", datei.toString(), "-storepass", "testpass")
                .redirectErrorStream(true).start();
        String ausgabe = new String(prozess.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, prozess.waitFor(), ausgabe);
        return datei;
    }
}
