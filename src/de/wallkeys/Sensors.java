package de.wallkeys;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Liest die Sensoren des Wall Display XL:
 *  - Temperatur/Feuchte: CHT8305 über sysfs (Milli-°C / Milli-%)
 *  - Helligkeit (lx) und Näherung: Android-Sensoren (STK3A5x)
 *  - CPU-Temperatur: thermal_zone0
 */
public final class Sensors implements SensorEventListener {
    public interface Listener {
        /** Ein Sensorwert soll gemeldet werden (Main-Thread). */
        void onSensor(String name, String value);

        /** Jeder neue Lichtwert, ungedrosselt (für die Auto-Helligkeit). */
        void onLux(double lux);
    }

    private static final String TH_DIR = "/sys/devices/platform/twi.1/i2c-1/1-0040/";
    private static final String CPU_TEMP = "/sys/class/thermal/thermal_zone0/temp";

    private final SensorManager sm;
    private final Handler handler;
    private final Listener listener;
    private boolean registered;

    // Zuletzt gemeldete Werte (für Änderungs-Schwellen) und aktuelle Werte (für Status)
    private final JSONObject current = new JSONObject();
    private double lastTemp = Double.NaN, lastHum = Double.NaN, lastLux = Double.NaN;
    private long lastLuxTime;

    private volatile int intervalSec = 60;
    private volatile double tempOffset, humOffset;
    private final Runnable poll = this::pollFiles;

    public Sensors(Context ctx, Handler handler, Listener listener) {
        this.sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        this.handler = handler;
        this.listener = listener;
    }

    public void configure(boolean enabled, int intervalSec, double tempOffset, double humOffset) {
        this.intervalSec = Math.max(5, intervalSec);
        this.tempOffset = tempOffset;
        this.humOffset = humOffset;
        handler.removeCallbacks(poll);
        if (sm != null && registered) {
            sm.unregisterListener(this);
            registered = false;
        }
        if (!enabled) return;
        if (sm != null) {
            // Der Android-Näherungssensor liefert beim XL nichts – Näherung kommt über setProximity()
            Sensor light = sm.getDefaultSensor(Sensor.TYPE_LIGHT);
            if (light != null) sm.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL, handler);
            registered = true;
        }
        lastTemp = lastHum = lastLux = Double.NaN; // nach Neukonfiguration alles neu melden
        handler.postDelayed(poll, 2000);
    }

    /** Alle aktuellen Werte erneut melden (z. B. nach MQTT-Reconnect). */
    public void republish() {
        handler.post(() -> {
            lastTemp = lastHum = lastLux = Double.NaN;
            pollFiles();
            String p = current.optString("proximity", "");
            if (!p.isEmpty()) listener.onSensor("proximity", p);
        });
    }

    public JSONObject snapshot() {
        synchronized (current) {
            try {
                return new JSONObject(current.toString());
            } catch (Exception e) {
                return new JSONObject();
            }
        }
    }

    private void pollFiles() {
        Double t = readMilli(TH_DIR + "temp1_input");
        Double h = readMilli(TH_DIR + "humidity1_input");
        Double cpu = readMilli(CPU_TEMP);
        long now = System.currentTimeMillis();
        boolean periodic = true; // pro Intervall immer melden (retained Werte bleiben frisch)
        if (t != null) {
            double v = round(t + tempOffset, 1);
            put("temperature", v);
            if (periodic || Double.isNaN(lastTemp) || Math.abs(v - lastTemp) >= 0.2) {
                lastTemp = v;
                listener.onSensor("temperature", fmt(v, 1));
            }
        }
        if (h != null) {
            double v = Math.max(0, Math.min(100, round(h + humOffset, 0)));
            put("humidity", v);
            lastHum = v;
            listener.onSensor("humidity", fmt(v, 0));
        }
        if (cpu != null) {
            double v = round(cpu, 1);
            put("cpu_temperature", v);
            listener.onSensor("cpu_temperature", fmt(v, 1));
        }
        // Helligkeit: letzter bekannter Wert im Intervall (der Sensor meldet nur Änderungen)
        Object lux = current.opt("illuminance");
        if (lux instanceof Number) {
            listener.onSensor("illuminance", fmt(((Number) lux).doubleValue(), 0));
            lastLuxTime = now;
        }
        handler.removeCallbacks(poll);
        handler.postDelayed(poll, intervalSec * 1000L);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.values == null || e.values.length == 0) return;
        if (e.sensor.getType() == Sensor.TYPE_LIGHT) {
            double lux = Math.round(e.values[0]);
            put("illuminance", lux);
            listener.onLux(lux);
            long now = System.currentTimeMillis();
            // Helligkeit springt schnell: nur bei deutlicher Änderung und höchstens alle 5 s melden
            double diff = Double.isNaN(lastLux) ? Double.MAX_VALUE : Math.abs(lux - lastLux);
            boolean significant = diff >= Math.max(5, lastLux * 0.15);
            if (significant && now - lastLuxTime >= 5000) {
                lastLux = lux;
                lastLuxTime = now;
                listener.onSensor("illuminance", fmt(lux, 0));
            } else if (Double.isNaN(lastLux)) {
                lastLux = lux;
            }
        }
    }

    /** Näherung vom gpio_keys-Eingang (F5 = nah, F6 = frei), Main-Thread. */
    public void setProximity(boolean near) {
        String v = near ? "near" : "far";
        if (!v.equals(current.optString("proximity"))) {
            put("proximity", v);
            listener.onSensor("proximity", v);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    public void stop() {
        handler.removeCallbacks(poll);
        if (sm != null) sm.unregisterListener(this);
        registered = false;
    }

    private void put(String k, Object v) {
        synchronized (current) {
            try {
                current.put(k, v);
            } catch (Exception ignored) {
            }
        }
    }

    private static Double readMilli(String path) {
        try {
            String s = new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.US_ASCII).trim();
            return Long.parseLong(s) / 1000.0;
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    private static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }

    static String fmt(double v, int digits) {
        return digits == 0 ? String.valueOf(Math.round(v)) : String.format(java.util.Locale.ROOT, "%." + digits + "f", v);
    }
}
