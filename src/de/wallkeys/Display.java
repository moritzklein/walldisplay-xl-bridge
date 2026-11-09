package de.wallkeys;

import android.os.Handler;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Hintergrundbeleuchtung des Wall Display XL – ersetzt die Helligkeits- und
 * Standby-Logik der Shelly-App (Stargate).
 *
 * Schreibt direkt nach /sys/class/backlight/backlight/brightness (0..255, für Apps beschreibbar),
 * genau wie Stargate. Android/Fully laufen unverändert weiter, 0 schaltet nur das Licht aus.
 *
 *  - Auto-Helligkeit: Lux → Helligkeit, logarithmisch zwischen luxLow/luxHigh, mit Hysterese
 *  - Näherung: nah → aktiv; frei → nach offAfterSec auf Standby-Helligkeit
 *  - Tastendruck / MQTT weckt ebenfalls
 */
public final class Display {
    public interface Listener {
        void onDisplayState(boolean active, int percent);
    }

    private final Handler handler;
    private final Listener listener;

    // Konfiguration
    private volatile boolean enabled = true, auto = true, proximityWake = true;
    private volatile int minPct = 5, maxPct = 100, manualPct = 80, offPct = 0, offAfterSec = 120;
    private volatile double luxLow = 5, luxHigh = 500;
    private volatile String path = "/sys/class/backlight/backlight/brightness";
    private volatile int maxRaw = 255;

    // Zustand
    private double lux = Double.NaN;
    private boolean active = true;
    private Integer overridePct;          // per MQTT gesetzte feste Helligkeit (null = Konfiguration)
    private int currentRaw = -1, targetRaw = -1;
    private int lastReportedPct = -1;
    private int autoFallbackPct = -1;
    private Boolean lastReportedActive;

    private final Runnable standby = this::goStandby;
    private final Runnable fade = this::fadeStep;
    private final Runnable keepAlive = this::keepAliveTick;

    public Display(Handler handler, Listener listener) {
        this.handler = handler;
        this.listener = listener;
    }

    public void configure(boolean enabled, boolean auto, int minPct, int maxPct, double luxLow, double luxHigh,
                          int manualPct, boolean proximityWake, int offAfterSec, int offPct, String path) {
        this.enabled = enabled;
        this.auto = auto;
        this.minPct = clamp(minPct, 0, 100);
        this.maxPct = clamp(Math.max(maxPct, this.minPct), 0, 100);
        this.luxLow = Math.max(0.1, luxLow);
        this.luxHigh = Math.max(this.luxLow + 1, luxHigh);
        this.manualPct = clamp(manualPct, 0, 100);
        this.proximityWake = proximityWake;
        this.offAfterSec = Math.max(0, offAfterSec);
        this.offPct = clamp(offPct, 0, 100);
        if (path != null && !path.trim().isEmpty()) this.path = path.trim();
        readMax();
        handler.removeCallbacks(keepAlive);
        handler.removeCallbacks(standby);
        if (!enabled) return;
        active = true;
        currentRaw = readRaw();
        // Bis der Lichtsensor (meldet nur Änderungen) einen Wert liefert: Helligkeit beim Start beibehalten
        if (autoFallbackPct < 0 && currentRaw > 0) {
            autoFallbackPct = clamp((int) Math.round(currentRaw * 100.0 / maxRaw), this.minPct, this.maxPct);
        }
        update(false);
        if (proximityWake && offAfterSec > 0 && !nearNow) handler.postDelayed(standby, offAfterSec * 1000L);
        handler.postDelayed(keepAlive, 30000);
    }

    private boolean nearNow;

    /** Neuer Lichtwert (lx). */
    public void onLux(double lx) {
        lux = lx;
        if (enabled) update(true);
    }

    /** Näherung vom gpio_keys-Sensor. */
    public void onProximity(boolean near) {
        nearNow = near;
        if (!enabled || !proximityWake) return;
        handler.removeCallbacks(standby);
        if (near) {
            wake();
        } else if (offAfterSec > 0) {
            handler.postDelayed(standby, offAfterSec * 1000L);
        }
    }

    /** Aktivität (Tastendruck): aufwecken und Standby-Timer neu starten, falls niemand erkannt wird. */
    public void onActivity() {
        if (!enabled) return;
        wake();
        handler.removeCallbacks(standby);
        if (proximityWake && offAfterSec > 0 && !nearNow) handler.postDelayed(standby, offAfterSec * 1000L);
    }

    /** MQTT: ON = aktiv, OFF = Standby. */
    public void setActive(boolean on) {
        if (!enabled) return;
        handler.removeCallbacks(standby);
        if (on) {
            onActivity();
        } else {
            active = false;
            update(false);
        }
    }

    /** MQTT: feste Helligkeit 0..100, oder null = zurück zur Automatik/Konfiguration. */
    public void setOverride(Integer pct) {
        overridePct = pct == null ? null : clamp(pct, 0, 100);
        if (enabled) update(false);
    }

    public boolean isActive() {
        return active;
    }

    public int currentPercent() {
        int raw = currentRaw >= 0 ? currentRaw : readRaw();
        return raw < 0 ? -1 : (int) Math.round(raw * 100.0 / maxRaw);
    }

    public String mode() {
        if (!enabled) return "aus";
        if (overridePct != null) return "fest " + overridePct + " %";
        return auto ? "auto" : "manuell";
    }

    public void stop() {
        handler.removeCallbacks(standby);
        handler.removeCallbacks(fade);
        handler.removeCallbacks(keepAlive);
    }

    // ---------------------------------------------------------------- intern

    private void wake() {
        if (!active) {
            active = true;
            update(false);
        }
    }

    private void goStandby() {
        if (!enabled || nearNow) return;
        active = false;
        update(false);
    }

    /** Zielhelligkeit bestimmen und hinblenden. */
    private void update(boolean fromSensor) {
        int pct;
        if (!active) pct = offPct;
        else if (overridePct != null) pct = overridePct;
        else if (auto && !Double.isNaN(lux)) pct = luxToPercent(lux);
        else if (auto) pct = autoFallbackPct >= 0 ? autoFallbackPct : maxPct; // noch kein Lux-Wert
        else pct = manualPct;

        int raw = pctToRaw(pct);
        // Hysterese bei Sensor-Änderungen: kleine Schwankungen ignorieren
        if (fromSensor && targetRaw >= 0 && Math.abs(raw - targetRaw) < Math.max(2, maxRaw * 0.03)) return;
        targetRaw = raw;
        if (currentRaw < 0) currentRaw = readRaw();
        handler.removeCallbacks(fade);
        handler.post(fade);
        report();
    }

    /** Sanftes Überblenden (~0,6 s), beim Einschalten aus Standby sofort hell. */
    private void fadeStep() {
        if (targetRaw < 0) return;
        if (currentRaw < 0) currentRaw = targetRaw;
        int diff = targetRaw - currentRaw;
        if (diff == 0) {
            write(targetRaw);
            return;
        }
        boolean wakingUp = currentRaw <= pctToRaw(offPct) && diff > 0;
        int step = wakingUp ? diff : (int) Math.signum(diff) * Math.max(1, Math.abs(diff) / 6);
        currentRaw += step;
        write(currentRaw);
        if (currentRaw != targetRaw) handler.postDelayed(fade, 100);
    }

    /** Falls Android oder eine andere App die Helligkeit überschreibt: alle 30 s wiederherstellen. */
    private void keepAliveTick() {
        if (enabled && targetRaw >= 0) {
            int raw = readRaw();
            if (raw >= 0 && raw != targetRaw && !handler.hasCallbacks(fade)) {
                currentRaw = raw;
                handler.post(fade);
            }
        }
        handler.postDelayed(keepAlive, 30000);
    }

    private void report() {
        int pct = (int) Math.round(targetRaw * 100.0 / maxRaw);
        if (lastReportedActive == null || lastReportedActive != active || pct != lastReportedPct) {
            lastReportedActive = active;
            lastReportedPct = pct;
            listener.onDisplayState(active, pct);
        }
    }

    public void republish() {
        lastReportedActive = null;
        if (enabled && targetRaw >= 0) report();
    }

    int luxToPercent(double lx) {
        double l = Math.max(luxLow, Math.min(luxHigh, lx));
        double f = (Math.log(l) - Math.log(luxLow)) / (Math.log(luxHigh) - Math.log(luxLow));
        return (int) Math.round(minPct + f * (maxPct - minPct));
    }

    private int pctToRaw(int pct) {
        if (pct <= 0) return 0;
        return Math.max(1, (int) Math.round(pct * maxRaw / 100.0));
    }

    private void readMax() {
        try {
            File f = new File(new File(path).getParentFile(), "max_brightness");
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.US_ASCII).trim();
            maxRaw = Math.max(1, Integer.parseInt(s));
        } catch (IOException | RuntimeException e) {
            maxRaw = 255;
        }
    }

    private int readRaw() {
        try {
            return Integer.parseInt(new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.US_ASCII).trim());
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    private boolean writeErrorLogged;

    private void write(int raw) {
        try (FileOutputStream out = new FileOutputStream(path)) {
            out.write(String.valueOf(clamp(raw, 0, maxRaw)).getBytes(StandardCharsets.US_ASCII));
            writeErrorLogged = false;
        } catch (IOException e) {
            if (!writeErrorLogged) Log.add("Helligkeit schreiben fehlgeschlagen: " + e.getMessage());
            writeErrorLogged = true;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
