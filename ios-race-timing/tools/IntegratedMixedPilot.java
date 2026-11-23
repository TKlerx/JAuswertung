package de.df.jauswertung.gui.plugins.zeitnahme;

import de.df.jauswertung.gui.JAuswertungLauncher;
import de.df.jauswertung.gui.util.I18n;
import de.df.jauswertung.io.OutputManager;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.Preferences;
import java.util.prefs.PreferencesFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

public final class IntegratedMixedPilot {
    private static final AtomicReference<Throwable> failure = new AtomicReference<>();

    public static final class IsolatedPreferences implements PreferencesFactory {
        private final Preferences preferences = new Node(null, "");
        public Preferences userRoot() { return preferences; }
        public Preferences systemRoot() { return preferences; }
    }

    private static final class Node extends AbstractPreferences {
        private final Map<String, String> values = new HashMap<>();
        private final Map<String, Node> children = new HashMap<>();
        Node(Node parent, String name) { super(parent, name); }
        protected void putSpi(String key, String value) { values.put(key, value); }
        protected String getSpi(String key) { return values.get(key); }
        protected void removeSpi(String key) { values.remove(key); }
        protected void removeNodeSpi() {
            if (parent() instanceof Node n) n.children.remove(name());
        }
        protected String[] keysSpi() { return values.keySet().toArray(String[]::new); }
        protected String[] childrenNamesSpi() { return children.keySet().toArray(String[]::new); }
        protected AbstractPreferences childSpi(String name) {
            return children.computeIfAbsent(name, key -> new Node(this, key));
        }
        protected void syncSpi() { }
        protected void flushSpi() { }
    }

    private static <T> List<T> descendants(Component root, Class<T> type) {
        List<T> result = new ArrayList<>();
        if (type.isInstance(root)) result.add(type.cast(root));
        if (root instanceof Container container) {
            for (var child : container.getComponents()) result.addAll(descendants(child, type));
        }
        return result;
    }

    private static JButton button(Container container, String name) {
        return descendants(container, JButton.class).stream().filter(a -> name.equals(a.getText())).findFirst()
                .orElseThrow(() -> new AssertionError("Missing button " + name));
    }

    private static JDialog dialog(String title) {
        for (Window window : Window.getWindows()) {
            if (window instanceof JDialog d && d.isShowing() && d.getTitle().equals(title)) return d;
        }
        throw new AssertionError("Missing dialog " + title);
    }

    private static JMenuItem timingItem() {
        for (Window window : Window.getWindows()) {
            if (!(window instanceof JFrame frame) || !frame.isShowing() || frame.getJMenuBar() == null) continue;
            var menus = frame.getJMenuBar();
            for (int i = 0; i < menus.getMenuCount(); i++) {
                JMenu menu = menus.getMenu(i);
                if (menu == null || !menu.getText().equals(I18n.get("Information"))) continue;
                for (Component child : menu.getMenuComponents()) {
                    if (child instanceof JMenuItem entry && entry.getText().equals(JZeitnahmeLauf.text("Titel"))) return entry;
                }
            }
        }
        return null;
    }

    private static void later(Runnable action) {
        Timer timer = new Timer(350, event -> action.run());
        timer.setRepeats(false);
        timer.start();
    }

    private static HttpResponse<String> call(HttpClient client, String desk, String endpoint, String data, String token)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(desk + "/api/" + endpoint)).timeout(Duration.ofSeconds(4))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(data));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static ZeitnahmeVersuch attempt(JDialog dialog) throws ReflectiveOperationException {
        var field = JZeitnahmeLauf.class.getDeclaredField("virtuell");
        field.setAccessible(true);
        return ((VirtuellerZeitnahmelauf) field.get(dialog)).versuch();
    }

    private static de.df.jauswertung.daten.AWettkampf<?> loadedCompetition(JMenuItem item) {
        try {
            for (var action : item.getActionListeners()) {
                for (var captured : action.getClass().getDeclaredFields()) {
                    if (captured.getType() != MZeitnahmePlugin.class) continue;
                    captured.setAccessible(true);
                    var core = MZeitnahmePlugin.class.getDeclaredField("core");
                    core.setAccessible(true);
                    return ((de.df.jauswertung.gui.plugins.CorePlugin) core.get(captured.get(action))).getWettkampf();
                }
            }
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
        throw new AssertionError("Could not locate the model provided to the real plugin");
    }

    public static void main(String[] args) throws Exception {
        var result = Path.of(args[4]);
        try {
            Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> failure.compareAndSet(null, ex));
            Locale.setDefault(Locale.GERMANY);
            var fixture = ZeitnahmeLaufquelle.probewettkampf();
            var existing = fixture.createTeilnehmer("", "Kontrollbahn", "", 2000, false,
                    I18n.get("ZeitnahmeLauf.Probelauf"), 0, "");
            fixture.addSchwimmer(existing);
            existing.setZeit(0, 7890);
            fixture.getLaufliste().getLaufliste().getFirst().setSchwimmer(existing, 0, 5);
            if (!OutputManager.speichereWettkampf(args[0], fixture)) {
                throw new AssertionError("Could not create synthetic competition");
            }
            JAuswertungLauncher.main(new String[] { "-open", args[0] });
            var item = new AtomicReference<JMenuItem>();
            for (int i = 0; i < 100 && item.get() == null; i++) {
                SwingUtilities.invokeAndWait(() -> item.set(timingItem()));
                if (failure.get() != null) throw new IllegalStateException(failure.get());
                if (item.get() == null) Thread.sleep(200);
            }
            if (item.get() == null) throw new AssertionError("Zeitnahme menu was never loaded");
            SwingUtilities.invokeAndWait(() -> item.get().doClick());
            var timing = new AtomicReference<JDialog>();
            SwingUtilities.invokeAndWait(() -> {
                timing.set(dialog("Zeitnahme"));
                var roles = descendants(timing.get(), JComboBox.class);
                roles.get(1).setSelectedIndex(0);
                roles.get(2).setSelectedIndex(0);
                roles.get(3).setSelectedIndex(2);
                roles.get(4).setSelectedIndex(0);
                roles.get(5).setSelectedIndex(4);
                descendants(timing.get(), javax.swing.JSpinner.class).getFirst().setValue(45);
                descendants(timing.get(), JCheckBox.class).stream()
                        .filter(box -> box.getText().startsWith("Countdown")).findFirst().orElseThrow().doClick();
                later(() -> {
                    var config = dialog("Lokales HTTPS starten");
                    var fields = descendants(config, JTextField.class);
                    fields.get(1).setText(args[3]);
                    fields.get(2).setText(args[1]);
                    fields.get(3).setText("pilot-test-password");
                    button(config, "OK").doClick();
                });
                button(timing.get(), "Lokales HTTPS starten").doClick();
            });
            var ready = new AtomicReference<>(false);
            for (int i = 0; i < 50 && !ready.get(); i++) {
                SwingUtilities.invokeAndWait(() -> ready.set(button(timing.get(), "Handy koppeln").isEnabled()));
                if (failure.get() != null) throw new IllegalStateException(failure.get());
                if (!ready.get()) Thread.sleep(100);
            }
            if (!ready.get()) throw new AssertionError("HTTPS server did not start");
            var invitation = new AtomicReference<String>();
            SwingUtilities.invokeAndWait(() -> {
                later(() -> {
                    var roles = dialog("Handy koppeln");
                    later(() -> {
                        var message = dialog("Handy koppeln");
                        invitation.set(descendants(message, JTextField.class).get(1).getText());
                        button(message, "OK").doClick();
                    });
                    button(roles, "Handy A").doClick();
                });
                button(timing.get(), "Handy koppeln").doClick();
            });
            if (invitation.get() == null || invitation.get().isBlank()) throw new AssertionError("No pairing code");
            Files.writeString(Path.of(args[5]), invitation.get());
            try (var stdin = new java.io.BufferedReader(new java.io.InputStreamReader(System.in))) {
                for (String command; (command = stdin.readLine()) != null;) {
                    if (command.equals("invite")) {
                        var dienst = JZeitnahmeLauf.class.getDeclaredField("dienst");
                        var telefon = JZeitnahmeLauf.class.getDeclaredField("telefonA");
                        dienst.setAccessible(true);
                        telefon.setAccessible(true);
                        var code = ((ZeitnahmeDienst) dienst.get(timing.get()))
                                .code((java.util.UUID) telefon.get(timing.get()));
                        if (code == null) throw new AssertionError("No fresh pairing code");
                        Files.writeString(Path.of(args[5]), code);
                    } else if (command.equals("arm")) {
                        SwingUtilities.invokeAndWait(() -> button(timing.get(), "Lauf scharfschalten").doClick());
                    } else if (command.equals("start")) {
                        var started = new java.util.concurrent.atomic.AtomicBoolean();
                        for (int i = 0; i < 15 && !started.get(); i++) {
                            SwingUtilities.invokeAndWait(() -> {
                                var start = button(timing.get(), "Startsignal auslösen");
                                if (start.isEnabled()) {
                                    start.doClick(0);
                                    started.set(true);
                                }
                            });
                            if (!started.get()) Thread.sleep(100);
                        }
                        if (!started.get()) throw new AssertionError("Desk not ready to start");
                        var countdown = JZeitnahmeLauf.class.getDeclaredField("laufenderCountdown");
                        countdown.setAccessible(true);
                        for (int i = 0; i < 60; i++) {
                            var active = new AtomicReference<>(false);
                            SwingUtilities.invokeAndWait(() -> {
                                try {
                                    active.set(countdown.get(timing.get()) != null);
                                } catch (IllegalAccessException ex) {
                                    throw new IllegalStateException(ex);
                                }
                            });
                            if (active.get()) {
                                if (attempt(timing.get()).start().isPresent()) {
                                    throw new AssertionError("Clock started during the short tones");
                                }
                                Files.writeString(Path.of(args[6] + ".countdown"), "active");
                                break;
                            }
                            Thread.sleep(50);
                        }
                    } else if (command.equals("apply")) {
                        var run = attempt(timing.get());
                        var entries = run.ergebnisse();
                        if (entries.size() != 3 || !entries.stream().map(ZeitnahmeVersuch.Ergebnis::bahn)
                                .collect(java.util.stream.Collectors.toSet()).equals(java.util.Set.of(3, 4, 5))) {
                            throw new AssertionError("Expected exactly one result on each of lanes 3, 4 and 5");
                        }
                        if (entries.stream().noneMatch(e -> e.bahn() == 4 && e.dauer() == 65_200_000
                                && e.quelle() == ZeitnahmeVersuch.Quelle.STOPPUHR_HANDY)) {
                            throw new AssertionError("Unexpected stopwatch result");
                        }
                        var report = new AtomicReference<String>();
                        SwingUtilities.invokeAndWait(() -> {
                            var model = loadedCompetition(item.get());
                            var heat = model.getLaufliste().getLaufliste().getFirst();
                            if (heat.getSchwimmer(5).getZeit(0) != 7890) {
                                throw new AssertionError("Control lane changed before Apply");
                            }
                            for (int lane : new int[] {3, 4, 5}) {
                                if (heat.getSchwimmer(lane - 1).getZeit(0) != 0) {
                                    throw new AssertionError("Lane changed before Apply");
                                }
                            }
                            later(() -> {
                                var review = dialog("Ergebnisse prüfen");
                                for (JCheckBox box : descendants(review, JCheckBox.class)) {
                                    if (box.isEnabled() && !box.isSelected()) box.doClick();
                                }
                                if (entries.stream().anyMatch(ZeitnahmeVersuch.Ergebnis::unsicher)) {
                                    later(() -> {
                                        for (Window w : Window.getWindows()) {
                                            if (w instanceof JDialog confirmation && confirmation.isShowing() && w != review
                                                    && confirmation.getTitle().equals(review.getTitle())) {
                                                button(confirmation, "Ja").doClick();
                                                return;
                                            }
                                        }
                                        throw new AssertionError("Missing uncertainty confirmation");
                                    });
                                }
                                button(review, "Zeiten übernehmen").doClick();
                            });
                            button(timing.get(), "Ergebnisse prüfen").doClick();
                            if (heat.getSchwimmer(5).getZeit(0) != 7890 || run.zustand() != ZeitnahmeVersuch.Zustand.UEBERNOMMEN) {
                                throw new AssertionError("Apply failed or changed the control lane");
                            }
                            var rows = new ArrayList<String>();
                            for (var entry : entries) {
                                int centiseconds = heat.getSchwimmer(entry.bahn() - 1).getZeit(0);
                                if (centiseconds != entry.dauer() / 10_000) {
                                    throw new AssertionError("Incorrect stored time on lane " + entry.bahn());
                                }
                                rows.add("%d,%d,%s,%s,%d".formatted(entry.bahn(), entry.dauer(), entry.quelle(),
                                        entry.unsicher(), centiseconds));
                            }
                            rows.add("6,0,KONTROLLE,false,7890");
                            report.set(String.join("\n", rows));
                        });
                        Files.writeString(Path.of(args[6]), report.get());
                    } else if (command.equals("report")) {
                        var field = JZeitnahmeLauf.class.getDeclaredField("virtuell");
                        field.setAccessible(true);
                        var run = (VirtuellerZeitnahmelauf) field.get(timing.get());
                        var report = run.versuch().ergebnisse().stream()
                                .map(e -> "%d,%d,%s,%s".formatted(e.bahn(), e.dauer(), e.quelle(), e.unsicher()))
                                .collect(java.util.stream.Collectors.joining("\n"));
                        Files.writeString(Path.of(args[6]), report);
                    } else if (command.equals("end")) {
                        SwingUtilities.invokeAndWait(() -> button(timing.get(), "Lokales HTTPS beenden").doClick());
                        SwingUtilities.invokeAndWait(() -> button(timing.get(), "Lokales HTTPS starten").isEnabled());
                    } else if (command.equals("expire")) {
                        var field = JZeitnahmeLauf.class.getDeclaredField("dienst");
                        field.setAccessible(true);
                        var service = (ZeitnahmeDienst) field.get(timing.get());
                        var tokens = ZeitnahmeDienst.class.getDeclaredField("zugaenge");
                        tokens.setAccessible(true);
                        synchronized (service) {
                            ((java.util.Map<?, ?>) tokens.get(service)).clear();
                        }
                    } else if (command.equals("stop")) {
                        break;
                    }
                }
            }
            Files.writeString(result, "JAuswertungLauncher: Information > Zeitnahme loaded; local trusted HTTPS and pairing passed.\n");
        } catch (Throwable ex) {
            try (var stream = new java.io.PrintStream(Files.newOutputStream(result))) {
                ex.printStackTrace(stream);
            }
            System.exit(1);
        }
        System.exit(0);
    }
}
