package de.df.jauswertung.gui.plugins.zeitnahme;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiPredicate;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

final class ZeitnahmeDienst implements AutoCloseable {
    private static final int MAX_ANFRAGE = 8_192;
    private static final long CODE_GUELTIG = 120_000_000;
    private static final long PROBE_GUELTIG = 5_000_000;
    private static final int PILOTDRIFT = 20;
    private static final long BROWSER_UHRAUFLOESUNG = 1_000;
    private static final long TON_RESERVE = 120_000;

    private record Code(UUID rolle, long ablauf) { }
    private record Geraet(UUID rolle, String name) { }
    private record Lauf(ZeitnahmeVersuch versuch, int horndauer, boolean countdown) { }
    private record Testton(UUID starter, int horndauer, boolean countdown) { }
    private record Probe(UUID versuch, UUID epoche, UUID id, long empfangen, long gesendet) { }
    private record Antwort(int status, Object daten) { }

    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final HttpsServer server;
    private final ExecutorService worker;
    private final ZeitnahmeVersuch.Sitzung sitzung;
    private final BiPredicate<ZeitnahmeVersuch, Runnable> startValidieren;
    private final OptionalLong kalibrierteTonlatenz;
    private final UUID schreibtischEpoche = UUID.randomUUID();
    private final Map<String, Code> codes = new HashMap<>();
    private final Map<String, Geraet> zugaenge = new HashMap<>();
    private final Map<UUID, Probe> proben = new HashMap<>();
    private final Map<UUID, Uhrabgleich> uhren = new HashMap<>();
    private volatile Lauf aktuell;
    private volatile Testton testton = new Testton(null, Startsignal.STANDARD, false);
    private volatile boolean geschlossen;

    ZeitnahmeDienst(InetSocketAddress adresse, SSLContext tls, ZeitnahmeVersuch.Sitzung sitzung,
            BiPredicate<ZeitnahmeVersuch, Runnable> startValidieren) throws IOException {
        this(adresse, tls, sitzung, startValidieren, OptionalLong.empty());
    }

    ZeitnahmeDienst(InetSocketAddress adresse, SSLContext tls, ZeitnahmeVersuch.Sitzung sitzung,
            BiPredicate<ZeitnahmeVersuch, Runnable> startValidieren, OptionalLong kalibrierteTonlatenz) throws IOException {
        var ip = adresse.getAddress();
        if (ip == null || ip.isAnyLocalAddress() || (kalibrierteTonlatenz.isPresent()
                && (kalibrierteTonlatenz.getAsLong() < 0 || kalibrierteTonlatenz.getAsLong() > 500_000))
                || (!ip.isLoopbackAddress() && NetworkInterface.getByInetAddress(ip) == null)) {
            throw new IllegalArgumentException();
        }
        this.sitzung = sitzung;
        this.startValidieren = startValidieren;
        this.kalibrierteTonlatenz = kalibrierteTonlatenz;
        server = HttpsServer.create(adresse, 32);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        worker = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(worker);
        server.createContext("/", this::beantworten);
        try {
            server.start();
        } catch (RuntimeException ex) {
            server.stop(0);
            worker.shutdownNow();
            throw ex;
        }
    }

    static SSLContext tls(Path datei, char[] passwort, boolean testVertrauen) throws IOException, GeneralSecurityException {
        var speicher = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(datei)) {
            speicher.load(in, passwort);
        }
        var context = SSLContext.getInstance("TLS");
        if (testVertrauen) {
            var vertrauen = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            vertrauen.init(speicher);
            context.init(null, vertrauen.getTrustManagers(), null);
        } else {
            boolean schluessel = false;
            for (var namen = speicher.aliases(); namen.hasMoreElements();) {
                String name = namen.nextElement();
                if (speicher.isKeyEntry(name) && speicher.getCertificate(name) instanceof X509Certificate zertifikat) {
                    zertifikat.checkValidity();
                    schluessel = true;
                }
            }
            if (!schluessel) {
                throw new GeneralSecurityException();
            }
            var verwaltung = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            verwaltung.init(speicher, passwort);
            context.init(verwaltung.getKeyManagers(), null, null);
        }
        return context;
    }

    int port() {
        return server.getAddress().getPort();
    }

    synchronized String code(UUID rolle) {
        if (geschlossen || gekoppelt(rolle)) {
            return null;
        }
        codes.values().removeIf(code -> code.rolle().equals(rolle) || code.ablauf() <= Startsignal.jetzt());
        String code = UUID.randomUUID().toString();
        codes.put(code, new Code(rolle, Startsignal.jetzt() + CODE_GUELTIG));
        return code;
    }

    synchronized boolean gekoppelt(UUID rolle) {
        return zugaenge.values().stream().anyMatch(geraet -> geraet.rolle().equals(rolle));
    }

    synchronized void laufSetzen(ZeitnahmeVersuch versuch, int horndauer, boolean countdown) {
        aktuell = versuch == null ? null : new Lauf(versuch, horndauer, countdown);
        proben.clear();
        uhren.clear();
    }

    void testtonSetzen(UUID starter, int horndauer, boolean countdown) {
        testton = new Testton(starter, horndauer, countdown);
    }

    @Override
    public void close() {
        synchronized (this) {
            if (geschlossen) {
                return;
            }
            geschlossen = true;
            zugaenge.clear();
            codes.clear();
            laufSetzen(null, 0, false);
        }
        server.stop(0);
        worker.shutdownNow();
    }

    private void beantworten(HttpExchange exchange) throws IOException {
        long empfangen = Startsignal.jetzt();
        try (exchange) {
            var kopf = exchange.getResponseHeaders();
            kopf.set("Cache-Control", "no-store");
            kopf.set("X-Content-Type-Options", "nosniff");
            kopf.set("Referrer-Policy", "no-referrer");
            kopf.set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; "
                    + "connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'");
            String pfad = exchange.getRequestURI().getRawPath();
            if (exchange.getRequestURI().getRawQuery() != null) {
                senden(exchange, fehler(400, "ungueltig"));
            } else if (exchange.getRequestMethod().equals("GET") && !pfad.startsWith("/api/")) {
                ressource(exchange, pfad);
            } else {
                senden(exchange, api(exchange, pfad, empfangen));
            }
        }
    }

    private void ressource(HttpExchange exchange, String pfad) throws IOException {
        String datei = switch (pfad) {
        case "/" -> "index.html";
        case "/app.js" -> "app.js";
        case "/app.css" -> "app.css";
        default -> null;
        };
        if (datei == null) {
            senden(exchange, fehler(404, "unbekannt"));
            return;
        }
        try (var in = ZeitnahmeDienst.class.getResourceAsStream("/zeitnahme/" + datei)) {
            if (in == null) {
                senden(exchange, fehler(404, "unbekannt"));
                return;
            }
            String typ = switch (datei) {
            case "app.js" -> "text/javascript";
            case "app.css" -> "text/css";
            default -> "text/html";
            };
            senden(exchange, 200, typ, in.readAllBytes());
        }
    }

    private Antwort api(HttpExchange exchange, String pfad, long empfangen) throws IOException {
        var header = exchange.getRequestHeaders();
        String origin = header.getFirst("Origin");
        String contentType = header.getFirst("Content-Type");
        if (geschlossen || origin != null && !origin.equals("https://" + header.getFirst("Host"))) {
            return fehler(403, "verboten");
        }
        if (!exchange.getRequestMethod().equals("POST") || !pfad.startsWith("/api/")) {
            return fehler(405, "verboten");
        }
        if (contentType == null || !contentType.split(";", 2)[0].equalsIgnoreCase("application/json")) {
            return fehler(415, "ungueltig");
        }
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_ANFRAGE + 1);
        if (bytes.length > MAX_ANFRAGE) {
            return fehler(413, "ungueltig");
        }
        try {
            JsonNode daten = json.readTree(bytes);
            if (daten == null || !daten.isObject()) {
                return fehler(400, "ungueltig");
            }
            if (pfad.equals("/api/paaren")) {
                return paaren(text(daten, "code", 64), text(daten, "name", 60));
            }
            var geraet = authentifizieren(header.getFirst("Authorization"));
            if (geraet == null) {
                return fehler(401, "nichtGekoppelt");
            }
            return switch (pfad) {
            case "/api/lauf" -> laufLesen(geraet);
            case "/api/uhr" -> uhr(geraet, daten, empfangen);
            case "/api/probe" -> probe(geraet, daten);
            case "/api/start" -> freigabe(geraet, daten);
            case "/api/startmeldung" -> startMelden(geraet, daten);
            case "/api/ziel" -> ziel(geraet, daten);
            case "/api/stoppuhr" -> stoppuhr(geraet, daten);
            default -> fehler(404, "unbekannt");
            };
        } catch (IllegalArgumentException | ArithmeticException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            return fehler(400, "ungueltig");
        }
    }

    private synchronized Antwort paaren(String code, String name) {
        Code einladung = codes.remove(code);
        if (geschlossen || einladung == null || einladung.ablauf() <= Startsignal.jetzt()
                || gekoppelt(einladung.rolle())) {
            return fehler(403, "code");
        }
        String zugang = UUID.randomUUID().toString();
        zugaenge.put(zugang, new Geraet(einladung.rolle(), name));
        return ok(Map.of("zugang", zugang));
    }

    private synchronized Geraet authentifizieren(String auth) {
        return auth != null && auth.startsWith("Bearer ") ? zugaenge.get(auth.substring(7)) : null;
    }

    private Antwort laufLesen(Geraet geraet) {
        var lauf = aktuell;
        if (lauf == null) {
            var probe = testton;
            return ok(Map.of("name", geraet.name(), "starter", geraet.rolle().equals(probe.starter()),
                    "horndauer", probe.horndauer(), "countdown", probe.countdown()));
        }
        var v = lauf.versuch();
        long jetzt = Startsignal.jetzt();
        v.fristPruefen(jetzt);
        var stand = v.laufstand();
        var bahnen = stand.bahnen().stream().filter(bahn -> geraet.rolle().equals(bahn.telefon()))
                .map(bahn -> Map.of("nummer", bahn.nummer(), "teilnehmer", bahn.teilnehmer(), "telefonzeit", bahn.telefonzeit()))
                .toList();
        Map<String, Object> antwort = new HashMap<>(Map.ofEntries(
                Map.entry("name", geraet.name()), Map.entry("versuch", v.id()), Map.entry("lauf", stand.lauf()),
                Map.entry("disziplin", stand.disziplin()), Map.entry("bahnen", bahnen),
                Map.entry("starter", geraet.rolle().equals(stand.starttelefon())),
                Map.entry("abgleich", abgleich(v, geraet.rolle())), Map.entry("horndauer", lauf.horndauer()),
                Map.entry("countdown", lauf.countdown()), Map.entry("schreibtischEpoche", schreibtischEpoche)));
        synchronized (v) {
            antwort.put("bereit", v.bereit(jetzt));
            antwort.put("zustand", v.zustand().name());
            v.start().ifPresent(start -> antwort.put("startZeitUs", ZeitnahmeVersuch.schreibtischzeit(start)));
        }
        return ok(antwort);
    }

    private static boolean abgleich(ZeitnahmeVersuch v, UUID rolle) {
        return rolle.equals(v.laufstand().starttelefon()) || v.laufstand().bahnen().stream()
                .anyMatch(bahn -> bahn.telefonzeit() && rolle.equals(bahn.telefon()));
    }

    private synchronized Antwort uhr(Geraet geraet, JsonNode daten, long empfangen) {
        var lauf = aktuell;
        UUID versuch = uuid(daten, "versuch");
        if (geschlossen || lauf == null || !lauf.versuch().id().equals(versuch) || !abgleich(lauf.versuch(), geraet.rolle())) {
            return fehler(403, "rolle");
        }
        UUID id = UUID.randomUUID();
        long gesendet = Startsignal.jetzt();
        proben.put(geraet.rolle(), new Probe(versuch, uuid(daten, "epoche"), id, empfangen, gesendet));
        return ok(Map.of("probe", id, "schreibtischEpoche", schreibtischEpoche, "schreibtischEmpfangen", empfangen,
                "schreibtischGesendet", gesendet));
    }

    private synchronized Antwort probe(Geraet geraet, JsonNode daten) {
        var p = proben.get(geraet.rolle());
        var lauf = aktuell;
        if (geschlossen || p == null || lauf == null || !p.id().equals(uuid(daten, "probe"))
                || !p.versuch().equals(lauf.versuch().id()) || !p.epoche().equals(uuid(daten, "epoche"))
                || Startsignal.jetzt() - p.gesendet() > PROBE_GUELTIG) {
            return fehler(409, "probe");
        }
        proben.remove(geraet.rolle());
        var neu = Uhrabgleich.auswerten(new Uhrabgleich.Probe(p.epoche(), zahl(daten, "telefonGesendet"),
                p.empfangen(), p.gesendet(), zahl(daten, "telefonEmpfangen")), PILOTDRIFT, BROWSER_UHRAUFLOESUNG);
        var sammler = uhren.computeIfAbsent(geraet.rolle(), ignoriert -> new Uhrabgleich());
        sammler.probeEmpfangen(neu, lauf.versuch().abgleichhorizont(p.gesendet()), Uhrabgleich.BUDGET);
        var uhr = sammler.schaetzung();
        lauf.versuch().abgleich(geraet.rolle(), uhr);
        long horizont = lauf.versuch().abgleichhorizont(p.gesendet());
        long auffrischung = uhr.naechsteAuffrischung(p.gesendet(), horizont, 1_000_000, Uhrabgleich.BUDGET);
        return ok(Map.of("id", uhr.id(), "versatz", uhr.versatz(), "unsicherheit", uhr.unsicherheit(),
                "bezugszeit", uhr.bezugszeit(), "driftPpm", uhr.driftPpm(),
                "auffrischungMs", sammler.probensalveNoetig(horizont, Uhrabgleich.BUDGET)
                        ? 200 : Math.max(200, (auffrischung - p.gesendet()) / 1_000)));
    }

    private Antwort freigabe(Geraet geraet, JsonNode daten) {
        var lauf = aktuell;
        if (lauf == null || !lauf.versuch().id().equals(uuid(daten, "versuch"))
                || !geraet.rolle().equals(lauf.versuch().laufstand().starttelefon())) {
            return fehler(403, "rolle");
        }
        UUID anfrage = uuid(daten, "anfrage");
        UUID epoche = uuid(daten, "epoche");
        UUID schaetzung = uuid(daten, "schaetzung");
        Antwort[] antwort = { fehler(409, "nichtBereit") };
        boolean gueltig = startValidieren.test(lauf.versuch(), () -> {
            if (!geschlossen && aktuell == lauf) {
                lauf.versuch().startfreigabe(geraet.rolle(), anfrage, epoche, schaetzung, Startsignal.jetzt())
                        .ifPresent(grant -> antwort[0] = ok(Map.of("freigabe", grant.id(), "frist", grant.frist(),
                                "beginn", grant.beginn(), "horndauer", lauf.horndauer(),
                                "tonReserve", Math.max(TON_RESERVE, kalibrierteTonlatenz.orElse(0)),
                                "kalibriert", kalibrierteTonlatenz.isPresent())));
            }
        });
        return gueltig ? antwort[0] : fehler(409, "veraltet");
    }

    private Antwort startMelden(Geraet geraet, JsonNode daten) {
        var v = sitzung.versuch(uuid(daten, "versuch"));
        if (v == null || !geraet.rolle().equals(v.laufstand().starttelefon())) {
            return fehler(403, "rolle");
        }
        String audio = text(daten, "audio", 16);
        UUID freigabe = uuid(daten, "freigabe");
        if (audio.equals("AUSGEFALLEN")) {
            return quittung(v, v.startMelden(freigabe, geraet.rolle(), null, ZeitnahmeVersuch.Audio.AUSGEFALLEN, 0));
        }
        if (!audio.equals("UNBEKANNT") && !audio.equals("AUSGABE")) {
            return fehler(400, "ungueltig");
        }
        var uhr = v.schaetzung(geraet.rolle(), uuid(daten, "epoche"), uuid(daten, "schaetzung"));
        if (uhr == null) {
            return fehler(409, "schaetzung");
        }
        long zeit = zahl(daten, "zeit");
        var ereignis = new ZeitnahmeEreignis.Start(uuid(daten, "ereignis"), v.id(), geraet.rolle(), zeit, uhr);
        boolean belegt = audio.equals("AUSGABE") && kalibrierteTonlatenz.isPresent();
        long spaetesterTon = 0;
        if (belegt) {
            long ausgabe = uhr.schreibtischzeit(zahl(daten, "ausgabe"));
            long mapped = uhr.schreibtischzeit(zeit);
            if (ausgabe < mapped - 5_000 || ausgabe > Startsignal.jetzt()) {
                return fehler(400, "ungueltig");
            }
            spaetesterTon = Math.addExact(Math.max(ausgabe, mapped),
                    Math.addExact(uhr.grenze(ausgabe), kalibrierteTonlatenz.getAsLong()));
        }
        return quittung(v, v.startMelden(freigabe, geraet.rolle(), ereignis,
                belegt ? ZeitnahmeVersuch.Audio.PUENKTLICH : ZeitnahmeVersuch.Audio.UNBEKANNT, spaetesterTon));
    }

    private Antwort ziel(Geraet geraet, JsonNode daten) {
        var v = sitzung.versuch(uuid(daten, "versuch"));
        if (v == null) {
            return fehler(409, "geschlossen");
        }
        var uhr = v.schaetzung(geraet.rolle(), uuid(daten, "epoche"), uuid(daten, "schaetzung"));
        if (uhr == null) {
            return fehler(409, "schaetzung");
        }
        var ziel = new ZeitnahmeEreignis.Ziel(uuid(daten, "ereignis"), v.id(), geraet.rolle(),
                Math.toIntExact(zahl(daten, "bahn")), zahl(daten, "zeit"), uhr);
        return quittung(v, sitzung.zielEmpfangen(ziel));
    }

    private Antwort stoppuhr(Geraet geraet, JsonNode daten) {
        var v = sitzung.versuch(uuid(daten, "versuch"));
        if (v == null) {
            return fehler(409, "geschlossen");
        }
        var stoppuhr = new ZeitnahmeEreignis.Stoppuhr(uuid(daten, "ereignis"), v.id(), geraet.rolle(),
                Math.toIntExact(zahl(daten, "bahn")), zahl(daten, "dauer"));
        return quittung(v, sitzung.stoppuhrEmpfangen(stoppuhr));
    }

    private static Antwort quittung(ZeitnahmeVersuch v, boolean angenommen) {
        return angenommen ? ok(Map.of("angenommen", true))
                : fehler(409, v.geschlossen() ? "geschlossen" : "abgelehnt");
    }

    private static Antwort ok(Map<String, ?> daten) {
        return new Antwort(200, daten);
    }

    private static Antwort fehler(int code, String grund) {
        return new Antwort(code, Map.of("fehler", grund));
    }

    private static UUID uuid(JsonNode daten, String feld) {
        return UUID.fromString(text(daten, feld, 36));
    }

    private static String text(JsonNode daten, String feld, int limit) {
        var node = daten.path(feld);
        if (!node.isTextual() || node.textValue().isBlank() || node.textValue().length() > limit) {
            throw new IllegalArgumentException();
        }
        return node.textValue();
    }

    private static long zahl(JsonNode daten, String feld) {
        var node = daten.path(feld);
        if (!node.isIntegralNumber() || !node.canConvertToLong() || Math.absExact(node.longValue()) > 9_007_199_254_740_991L) {
            throw new IllegalArgumentException();
        }
        return node.longValue();
    }

    private void senden(HttpExchange exchange, Antwort antwort) throws IOException {
        senden(exchange, antwort.status(), "application/json", json.writeValueAsBytes(antwort.daten()));
    }

    private static void senden(HttpExchange exchange, int status, String typ, byte[] daten) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", typ + "; charset=UTF-8");
        exchange.sendResponseHeaders(status, daten.length);
        exchange.getResponseBody().write(daten);
    }
}
