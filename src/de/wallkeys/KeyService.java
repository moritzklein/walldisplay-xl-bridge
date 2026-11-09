package de.wallkeys;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Zentrale von WallKeys:
 *  - Hardware-Tasten F1..F4 (kurz / lang / losgelassen) → KNX, MQTT, Webhook, Relais
 *  - Sensoren (Temperatur, Feuchte, Helligkeit, Näherung, CPU) → MQTT, KNX
 *  - Relais ← MQTT (&lt;base&gt;/relay/set), Taste, Webinterface; Status → MQTT, KNX
 *
 * Als AccessibilityService wird der Dienst vom System nach jedem Boot automatisch gestartet,
 * sobald er einmal in den Bedienungshilfen aktiviert wurde.
 */
public class KeyService extends AccessibilityService
        implements WebServer.Handler, MqttClient.Listener, Sensors.Listener, Display.Listener {
    private static final int FIRST_KEY = 131; // KEYCODE_F1
    private static final long START = SystemClock.elapsedRealtime();
    static final String VERSION = "1.3";

    /** Main-Thread-Handler, der Fehler abfängt (siehe SafeHandler). */
    private final Handler main = new SafeHandler();

    /** Für den Watchdog: läuft der Dienst in diesem Prozess? */
    static volatile boolean running;
    private final Runnable[] longTimers = new Runnable[Config.BUTTONS];
    private final boolean[] longFired = new boolean[Config.BUTTONS];
    private final boolean[] isDown = new boolean[Config.BUTTONS];

    private Config config;
    private MqttClient mqtt;
    private WebServer web;
    private Sensors sensors;
    private Display display;
    private final Relay relay = new Relay();
    private volatile JSONObject cfg;
    private int webPort = -1;

    private Boolean lastRelay;
    private final Map<String, Long> lastKnxSensor = new HashMap<>();
    private final Runnable relayPoll = this::pollRelay;

    /**
     * Die Shelly/Smatek-Firmware fängt F1..F4 im PhoneWindowManager ab, bevor Apps oder
     * Accessibility-Filter sie sehen, und sendet stattdessen diesen Broadcast
     * (Extras: "keycode" = 131..134, "keydown" = true/false).
     */
    static final String SMATEK_KEY_ACTION = "com.smatek.keycode_action";

    // Statisch statt innerer Klasse: JDK 25 erzeugt sonst ein namenloses MethodParameters-Attribut,
    // an dem d8 (build-tools r34) abstürzt. Aus demselben Grund keine anonymen Klassen hier.
    static final class KeyReceiver extends BroadcastReceiver {
        private final KeyService service;

        KeyReceiver(KeyService service) {
            this.service = service;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            service.onKey(intent.getIntExtra("keycode", -1), intent.getBooleanExtra("keydown", false));
        }
    }

    private final KeyReceiver keyReceiver = new KeyReceiver(this);

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        config = new Config(this);
        mqtt = new MqttClient();
        mqtt.setListener(this);
        sensors = new Sensors(this, main, this);
        display = new Display(main, this);
        web = new WebServer(getAssets(), config, this);
        try {
            apply();
        } catch (RuntimeException e) {
            Log.add("Fehler beim Anwenden der Konfiguration: " + e);
        }
        registerReceiver(keyReceiver, new IntentFilter(SMATEK_KEY_ACTION), null, main);
        main.postDelayed(relayPoll, 3000);
        running = true;
        Watchdog.schedule(this);
        Log.add("WallKeys " + VERSION + " gestartet");
    }

    private void apply() {
        cfg = config.get();
        JSONObject r = obj(cfg, "relay");
        relay.configure(r.optString("path"), r.optBoolean("inverted", true));

        JSONObject m = obj(cfg, "mqtt");
        List<String> subs = new ArrayList<>();
        if (r.optBoolean("enabled", true)) subs.add(base() + "/relay/set");
        JSONObject d = obj(cfg, "display");
        if (d.optBoolean("enabled", true)) {
            subs.add(base() + "/display/set");
            subs.add(base() + "/display/brightness/set");
        }
        mqtt.setSubscriptions(subs);
        display.configure(d.optBoolean("enabled", true), d.optBoolean("autoBrightness", true),
                d.optInt("minBrightness", 5), d.optInt("maxBrightness", 100),
                d.optDouble("luxLow", 5), d.optDouble("luxHigh", 500), d.optInt("manualBrightness", 80),
                d.optBoolean("proximityWake", true), d.optInt("offAfterSec", 120),
                d.optInt("standbyBrightness", 0), d.optString("path"));
        mqtt.configure(m.optBoolean("enabled"), m.optString("host"), m.optInt("port", 1883),
                m.optString("user"), m.optString("pass"), m.optString("clientId", "wallkeys"),
                m.optString("baseTopic", "walldisplay"), m.optInt("keepAlive", 30));

        JSONObject s = obj(cfg, "sensors");
        sensors.configure(s.optBoolean("enabled", true), s.optInt("intervalSec", 60),
                s.optDouble("tempOffset", 0), s.optDouble("humOffset", 0));
        lastKnxSensor.clear();

        int port = cfg.optInt("webPort", 8080);
        if (port != webPort) {
            web.start(port);
            webPort = port;
        }
    }

    private String base() {
        return obj(cfg, "mqtt").optString("baseTopic", "walldisplay").replaceAll("/+$", "");
    }

    private static JSONObject obj(JSONObject c, String key) {
        JSONObject o = c != null ? c.optJSONObject(key) : null;
        return o != null ? o : new JSONObject();
    }

    // ================================================================= Tasten

    /** Verarbeitet Drücken/Loslassen (läuft auf dem Main-Thread). */
    void onKey(int keyCode, boolean down) {
        int idx = keyCode - FIRST_KEY;
        JSONObject c = cfg;
        if (idx < 0 || idx >= Config.BUTTONS || c == null) return;

        if (down) {
            if (isDown[idx]) return; // doppeltes DOWN ignorieren
            isDown[idx] = true;
            longFired[idx] = false;
            final int b = idx;
            longTimers[idx] = () -> {
                if (isDown[b] && !longFired[b]) {
                    longFired[b] = true;
                    trigger(b + 1, "long");
                }
            };
            main.postDelayed(longTimers[idx], Math.max(200, c.optInt("longPressMs", 800)));
            display.onActivity();
            if (c.optBoolean("wakeScreen", false)) wakeScreen();
        } else {
            if (longTimers[idx] != null) main.removeCallbacks(longTimers[idx]);
            if (isDown[idx]) trigger(idx + 1, longFired[idx] ? "release" : "short");
            isDown[idx] = false;
        }
    }

    /** Löst alle konfigurierten Aktionen für Taste (1..4) und Ereignis aus. */
    private void trigger(int button, String event) {
        JSONObject c = cfg;
        JSONArray buttons = c.optJSONArray("buttons");
        JSONObject btn = buttons != null ? buttons.optJSONObject(button - 1) : null;
        String name = btn != null ? btn.optString("name", "Taste " + button) : "Taste " + button;
        Log.add(name + " (" + button + "): " + eventName(event));

        JSONObject m = obj(c, "mqtt");
        JSONObject h = obj(c, "http");
        JSONObject k = obj(c, "knx");
        JSONObject a = btn != null ? btn.optJSONObject(event) : null;

        // Relais lokal – funktioniert auch ganz ohne Netzwerk
        if (a != null && !a.optString("relay").trim().isEmpty()) {
            relayCommand(a.optString("relay"), name + " " + eventName(event));
        }

        // KNX – direkter Weg auf den Bus, unabhängig vom Smarthome-Server
        if (k.optBoolean("enabled") && a != null && !a.optString("knx").trim().isEmpty()) {
            try {
                Knx.send(knxSettings(k), Knx.parse(a.optString("knx")), name + " " + eventName(event));
            } catch (IllegalArgumentException ex) {
                Log.add("KNX Konfigurationsfehler: " + ex.getMessage());
            }
        }

        if (m.optBoolean("enabled")) {
            int qos = m.optInt("qos", 0);
            boolean retain = m.optBoolean("retain", false);
            if (m.optBoolean("publishDefault", true)) {
                mqtt.publish(base() + "/button/" + button, event, qos, retain);
            }
            if (a != null && !a.optString("mqttTopic").trim().isEmpty()) {
                String payload = a.optString("mqttPayload");
                mqtt.publish(fill(a.optString("mqttTopic").trim(), button, name, event),
                        fill(payload.isEmpty() ? event : payload, button, name, event), qos, retain);
            }
        }

        int timeout = h.optInt("timeoutMs", 4000);
        boolean insecure = h.optBoolean("insecureTls", true);
        String global = h.optString("globalUrl").trim();
        if (!global.isEmpty()) {
            HttpAction.fire(fill(global, button, name, event), "GET", null, timeout, insecure);
        }
        if (a != null && !a.optString("httpUrl").trim().isEmpty()) {
            HttpAction.fire(fill(a.optString("httpUrl"), button, name, event), a.optString("httpMethod", "GET"),
                    fill(a.optString("httpBody"), button, name, event), timeout, insecure);
        }
    }

    // ================================================================= Näherung (gpio_keys)

    /**
     * Der Näherungssensor des XL meldet sich nicht als Android-Sensor, sondern als
     * gpio_keys-Taste: KEYCODE_F5 = Objekt nähert sich, KEYCODE_F6 = Objekt entfernt.
     * Diese Keys fängt die Firmware nicht ab, sie kommen über den Accessibility-Key-Filter.
     */
    @Override
    protected boolean onKeyEvent(android.view.KeyEvent e) {
        try {
            int code = e.getKeyCode();
            if ((code == 135 || code == 136) && e.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                sensors.setProximity(code == 135);
            }
        } catch (RuntimeException ex) {
            Log.add("Fehler bei Näherung: " + ex);
        }
        return false; // nie verschlucken – Fully & Co. sehen alle Tasten weiterhin
    }

    // ================================================================= Relais

    /** Führt einen Relais-Befehl aus (Main-Thread) und meldet den neuen Zustand. */
    private Boolean relayCommand(String cmd, String source) {
        if (!obj(cfg, "relay").optBoolean("enabled", true)) {
            Log.add("Relais deaktiviert, Befehl ignoriert (" + source + ")");
            return null;
        }
        Boolean st = relay.command(cmd);
        if (st == null) {
            Log.add("Relais: unbekannter Befehl „" + cmd + "“ (" + source + ")");
            return null;
        }
        Log.add("Relais " + (st ? "EIN" : "AUS") + " (" + source + ")");
        lastRelay = st;
        reportRelay(st, true);
        return st;
    }

    /** Erkennt auch Änderungen von außen (z. B. durch die Shelly-App). */
    private void pollRelay() {
        try {
            if (obj(cfg, "relay").optBoolean("enabled", true) && relay.available()) {
                Boolean st = relay.get();
                if (st != null && !st.equals(lastRelay)) {
                    if (lastRelay != null) Log.add("Relais von außen geändert: " + (st ? "EIN" : "AUS"));
                    lastRelay = st;
                    reportRelay(st, true);
                }
            }
        } finally {
            main.postDelayed(relayPoll, 2000);
        }
    }

    private void reportRelay(boolean on, boolean knx) {
        if (obj(cfg, "mqtt").optBoolean("enabled")) {
            mqtt.publish(base() + "/relay", on ? "ON" : "OFF", 1, true, false);
        }
        String ga = obj(cfg, "relay").optString("knxStatus").trim();
        if (knx && !ga.isEmpty()) sendKnxValue(ga, "1.001", on ? "1" : "0", "Relais-Status");
    }

    // ================================================================= Sensoren

    @Override
    public void onLux(double lux) {
        display.onLux(lux);
    }

    @Override
    public void onDisplayState(boolean active, int percent) {
        if (obj(cfg, "mqtt").optBoolean("enabled")) {
            mqtt.publish(base() + "/display", active ? "ON" : "OFF", 0, true, false);
            mqtt.publish(base() + "/display/brightness", String.valueOf(percent), 0, true, false);
        }
    }

    @Override
    public void onSensor(String name, String value) {
        if ("proximity".equals(name)) display.onProximity("near".equals(value));
        if (obj(cfg, "mqtt").optBoolean("enabled")) {
            mqtt.publish(base() + "/sensor/" + name, value, 0, true, false);
        }
        JSONObject s = obj(cfg, "sensors");
        String ga, dpt;
        switch (name) {
            case "temperature": ga = s.optString("knxTemperature"); dpt = "9.001"; break;
            case "humidity": ga = s.optString("knxHumidity"); dpt = "9.007"; break;
            case "illuminance": ga = s.optString("knxIlluminance"); dpt = "9.004"; break;
            case "proximity": ga = s.optString("knxProximity"); dpt = "1.002"; break;
            default: return;
        }
        ga = ga.trim();
        if (ga.isEmpty()) return;
        // Messwerte höchstens einmal pro Intervall auf den Bus, Näherung sofort
        long now = SystemClock.elapsedRealtime();
        if (!"proximity".equals(name)) {
            Long last = lastKnxSensor.get(name);
            if (last != null && now - last < s.optInt("intervalSec", 60) * 1000L - 1000) return;
        }
        lastKnxSensor.put(name, now);
        String v = "proximity".equals(name) ? ("near".equals(value) ? "1" : "0") : value;
        sendKnxValue(ga, dpt, v, null);
    }

    private void sendKnxValue(String ga, String dpt, String value, String logLabel) {
        JSONObject k = obj(cfg, "knx");
        if (!k.optBoolean("enabled")) return;
        try {
            List<Knx.Telegram> t = Knx.parse(ga + " " + dpt + " " + value);
            Knx.send(knxSettings(k), t, logLabel != null ? logLabel : "Sensor");
        } catch (IllegalArgumentException ex) {
            Log.add("KNX Sensor/Relais: " + ex.getMessage());
        }
    }

    // ================================================================= MQTT eingehend

    @Override
    public void onMessage(String topic, String payload) {
        String p = payload.trim();
        if (topic.equals(base() + "/relay/set")) {
            main.post(() -> relayCommand(p, "MQTT"));
        } else if (topic.equals(base() + "/display/set")) {
            Boolean on = p.matches("(?i)on|1|true|ein") ? Boolean.TRUE : p.matches("(?i)off|0|false|aus") ? Boolean.FALSE : null;
            if (on == null) Log.add("Display: unbekannter Befehl „" + p + "“");
            else main.post(() -> {
                Log.add("Display " + (on ? "aktiv" : "Standby") + " (MQTT)");
                display.setActive(on);
            });
        } else if (topic.equals(base() + "/display/brightness/set")) {
            if (p.equalsIgnoreCase("auto") || p.isEmpty()) {
                main.post(() -> {
                    Log.add("Helligkeit: zurück auf Konfiguration (MQTT)");
                    display.setOverride(null);
                });
            } else {
                try {
                    int v = (int) Math.round(Double.parseDouble(p.replace(',', '.')));
                    main.post(() -> {
                        Log.add("Helligkeit fest " + v + " % (MQTT)");
                        display.setOverride(v);
                    });
                } catch (NumberFormatException e) {
                    Log.add("Helligkeit: ungültiger Wert „" + p + "“");
                }
            }
        }
    }

    @Override
    public void onConnected() {
        main.post(() -> {
            Boolean st = relay.available() ? relay.get() : null;
            if (st != null) reportRelay(st, false);
            sensors.republish();
            display.republish();
        });
    }

    // ================================================================= Hilfen

    private static String eventName(String e) {
        return "long".equals(e) ? "lang" : "release".equals(e) ? "losgelassen" : "kurz";
    }

    private static Knx.Settings knxSettings(JSONObject k) {
        Knx.Settings s = new Knx.Settings();
        s.mode = k.optString("mode", "tunnel");
        s.host = k.optString("host").trim();
        s.port = k.optInt("port", 3671);
        s.multicast = k.optString("multicast", "224.0.23.12").trim();
        s.sourceAddr = k.optString("sourceAddr", "15.15.250").trim();
        s.timeoutMs = k.optInt("timeoutMs", 2000);
        return s;
    }

    /** Platzhalter: {button} = 1..4, {name} = Tastenname, {event} = short|long|release */
    private static String fill(String s, int button, String name, String event) {
        if (s == null) return "";
        return s.replace("{button}", String.valueOf(button))
                .replace("{name}", name)
                .replace("{event}", event);
    }

    @SuppressWarnings("deprecation")
    private void wakeScreen() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null || pm.isInteractive()) return;
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP, "wallkeys:wake");
        wl.acquire(3000);
    }

    // ================================================================= WebServer.Handler

    @Override
    public JSONObject status() {
        JSONObject s = new JSONObject();
        try {
            s.put("version", VERSION);
            s.put("mqttConnected", mqtt.isConnected());
            s.put("uptimeSec", (SystemClock.elapsedRealtime() - START) / 1000);
            s.put("sensors", sensors.snapshot());
            Boolean r = relay.available() ? relay.get() : null;
            s.put("relay", r == null ? JSONObject.NULL : r);
            JSONObject d = new JSONObject();
            d.put("active", display.isActive());
            d.put("brightness", display.currentPercent());
            d.put("mode", display.mode());
            s.put("display", d);
            s.put("log", Log.toJson());
        } catch (Exception ignored) {
        }
        return s;
    }

    @Override
    public void saveConfig(JSONObject newCfg) throws IOException {
        // KNX-Angaben vorab prüfen, damit Tippfehler sofort im Webinterface auffallen
        JSONArray buttons = newCfg.optJSONArray("buttons");
        if (buttons != null) {
            for (int i = 0; i < buttons.length(); i++) {
                JSONObject b = buttons.optJSONObject(i);
                if (b == null) continue;
                for (String ev : Config.EVENTS) {
                    JSONObject a = b.optJSONObject(ev);
                    if (a == null) continue;
                    try {
                        Knx.parse(a.optString("knx"));
                    } catch (IllegalArgumentException ex) {
                        throw new IOException("Taste " + (i + 1) + ", " + eventName(ev) + ", KNX " + ex.getMessage());
                    }
                    String rc = a.optString("relay").trim();
                    if (!rc.isEmpty() && !rc.matches("(?i)on|off|toggle")) {
                        throw new IOException("Taste " + (i + 1) + ", " + eventName(ev) + ": Relais-Aktion on/off/toggle");
                    }
                }
            }
        }
        checkGa(obj(newCfg, "sensors"), "knxTemperature", "Temperatur");
        checkGa(obj(newCfg, "sensors"), "knxHumidity", "Feuchte");
        checkGa(obj(newCfg, "sensors"), "knxIlluminance", "Helligkeit");
        checkGa(obj(newCfg, "sensors"), "knxProximity", "Näherung");
        checkGa(obj(newCfg, "relay"), "knxStatus", "Relais-Status");
        JSONObject k = obj(newCfg, "knx");
        if ("routing".equals(k.optString("mode")) && k.optBoolean("enabled")) {
            try {
                Knx.parseIndividual(k.optString("sourceAddr"));
            } catch (RuntimeException ex) {
                throw new IOException("KNX Absenderadresse: " + ex.getMessage());
            }
        }
        config.set(newCfg);
        main.post(this::apply);
        Log.add("Konfiguration gespeichert");
    }

    private static void checkGa(JSONObject o, String key, String label) throws IOException {
        String ga = o.optString(key).trim();
        if (ga.isEmpty()) return;
        try {
            Knx.parseGa(ga);
        } catch (IllegalArgumentException ex) {
            throw new IOException(label + ": " + ex.getMessage());
        }
    }

    @Override
    public void test(int button, String event) {
        main.post(() -> trigger(button, event));
    }

    @Override
    public Boolean relay(String cmd) {
        final Boolean[] result = new Boolean[1];
        final Object lock = new Object();
        synchronized (lock) {
            main.post(() -> {
                Boolean r = relayCommand(cmd, "Webinterface");
                synchronized (lock) {
                    result[0] = r;
                    lock.notifyAll();
                }
            });
            try {
                lock.wait(2000);
            } catch (InterruptedException ignored) {
            }
        }
        return result[0];
    }

    // ================================================================= Lebenszyklus

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        running = false;
        try {
            unregisterReceiver(keyReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        main.removeCallbacks(relayPoll);
        if (sensors != null) sensors.stop();
        if (display != null) display.stop();
        if (web != null) web.stop();
        if (mqtt != null) mqtt.shutdown();
        super.onDestroy();
    }
}
