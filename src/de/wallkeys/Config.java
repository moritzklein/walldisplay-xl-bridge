package de.wallkeys;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Persistente Konfiguration als JSON-Datei im privaten App-Verzeichnis. */
public final class Config {
    public static final int BUTTONS = 4;
    /** short = kurzer Druck, long = Langdruck erkannt, release = Loslassen nach Langdruck (z. B. Dimm-Stopp). */
    public static final String[] EVENTS = {"short", "long", "release"};

    private final File file;
    private JSONObject json;

    public Config(Context ctx) {
        file = new File(ctx.getFilesDir(), "config.json");
        json = load();
    }

    public synchronized JSONObject get() {
        try {
            return new JSONObject(json.toString());
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Übernimmt eine neue Konfiguration (wird mit Defaults ergänzt) und speichert sie. */
    public synchronized void set(JSONObject incoming) throws IOException {
        JSONObject merged = defaults();
        try {
            deepMerge(merged, incoming);
        } catch (JSONException e) {
            throw new IOException("Ungültige Konfiguration: " + e.getMessage());
        }
        json = merged;
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException(e);
        }
        if (!tmp.renameTo(file)) throw new IOException("Konnte config.json nicht schreiben");
    }

    private JSONObject load() {
        JSONObject d = defaults();
        if (file.exists()) {
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] buf = new byte[(int) file.length()];
                int off = 0;
                while (off < buf.length) {
                    int r = in.read(buf, off, buf.length - off);
                    if (r < 0) break;
                    off += r;
                }
                deepMerge(d, new JSONObject(new String(buf, 0, off, StandardCharsets.UTF_8)));
            } catch (Exception e) {
                Log.add("Konfiguration defekt, nutze Defaults: " + e.getMessage());
            }
        }
        return d;
    }

    static JSONObject defaults() {
        try {
            JSONObject c = new JSONObject();
            c.put("longPressMs", 800);
            c.put("wakeScreen", false);
            c.put("webPort", 8080);
            c.put("webPassword", "");

            JSONObject m = new JSONObject();
            m.put("enabled", false);
            m.put("host", "");
            m.put("port", 1883);
            m.put("user", "");
            m.put("pass", "");
            m.put("clientId", "wallkeys");
            m.put("baseTopic", "walldisplay");
            m.put("publishDefault", true);
            m.put("qos", 0);
            m.put("retain", false);
            m.put("keepAlive", 30);
            c.put("mqtt", m);

            JSONObject h = new JSONObject();
            h.put("globalUrl", "");
            h.put("insecureTls", true);
            h.put("timeoutMs", 4000);
            c.put("http", h);

            JSONObject k = new JSONObject();
            k.put("enabled", false);
            k.put("mode", "tunnel");
            k.put("host", "");
            k.put("port", 3671);
            k.put("multicast", "224.0.23.12");
            k.put("sourceAddr", "15.15.250");
            k.put("timeoutMs", 2000);
            c.put("knx", k);

            JSONObject s = new JSONObject();
            s.put("enabled", true);
            s.put("intervalSec", 60);
            s.put("tempOffset", -1.2);   // Werks-Offset von Shelly für das XL
            s.put("humOffset", 0.0);
            s.put("knxTemperature", "");  // GA, DPT 9.001
            s.put("knxHumidity", "");     // GA, DPT 9.007
            s.put("knxIlluminance", "");  // GA, DPT 9.004
            s.put("knxProximity", "");    // GA, DPT 1.002 (1 = nah)
            c.put("sensors", s);

            JSONObject r = new JSONObject();
            r.put("enabled", true);
            r.put("path", "/sys/class/strelay/relay1");
            r.put("inverted", true);      // XL: "0" = EIN
            r.put("knxStatus", "");       // GA für Statusmeldung, DPT 1.001
            c.put("relay", r);

            JSONObject d = new JSONObject();
            d.put("enabled", true);
            d.put("path", "/sys/class/backlight/backlight/brightness");
            d.put("autoBrightness", true);
            d.put("minBrightness", 5);     // % bei luxLow und darunter
            d.put("maxBrightness", 100);   // % bei luxHigh und darüber
            d.put("luxLow", 5);
            d.put("luxHigh", 500);
            d.put("manualBrightness", 80); // % wenn Automatik aus
            d.put("proximityWake", true);
            d.put("offAfterSec", 120);     // 0 = nie Standby
            d.put("standbyBrightness", 0); // % im Standby, 0 = Beleuchtung aus
            c.put("display", d);

            JSONArray b = new JSONArray();
            for (int i = 1; i <= BUTTONS; i++) {
                JSONObject btn = new JSONObject();
                btn.put("name", "Taste " + i);
                for (String ev : EVENTS) {
                    JSONObject a = new JSONObject();
                    a.put("mqttTopic", "");
                    a.put("mqttPayload", "");
                    a.put("httpUrl", "");
                    a.put("httpMethod", "GET");
                    a.put("httpBody", "");
                    a.put("knx", "");
                    a.put("relay", "");   // "", on, off, toggle
                    btn.put(ev, a);
                }
                b.put(btn);
            }
            c.put("buttons", b);
            return c;
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Überträgt bekannte Schlüssel aus src nach dst (rekursiv, Typen von dst bleiben erhalten). */
    private static void deepMerge(JSONObject dst, JSONObject src) throws JSONException {
        JSONArray names = dst.names();
        if (names == null) return;
        for (int i = 0; i < names.length(); i++) {
            String k = names.getString(i);
            if (!src.has(k) || src.isNull(k)) continue;
            Object dv = dst.get(k);
            Object sv = src.get(k);
            if (dv instanceof JSONObject && sv instanceof JSONObject) {
                deepMerge((JSONObject) dv, (JSONObject) sv);
            } else if (dv instanceof JSONArray && sv instanceof JSONArray) {
                JSONArray da = (JSONArray) dv, sa = (JSONArray) sv;
                for (int j = 0; j < da.length() && j < sa.length(); j++) {
                    if (da.get(j) instanceof JSONObject && sa.get(j) instanceof JSONObject) {
                        deepMerge(da.getJSONObject(j), sa.getJSONObject(j));
                    }
                }
            } else if (dv instanceof Boolean) {
                dst.put(k, src.optBoolean(k, (Boolean) dv));
            } else if (dv instanceof Integer) {
                dst.put(k, src.optInt(k, (Integer) dv));
            } else if (dv instanceof Double) {
                dst.put(k, src.optDouble(k, (Double) dv));
            } else if (dv instanceof String) {
                dst.put(k, String.valueOf(sv));
            }
        }
    }
}
