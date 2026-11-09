package de.wallkeys;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Ansteuerung des eingebauten Relais über sysfs (/sys/class/strelay/relay1).
 * Beim Wall Display XL (SAWD-3A1XE10EU2) ist die Logik invertiert: "0" = EIN, "1" = AUS.
 */
public final class Relay {
    private volatile String path = "/sys/class/strelay/relay1";
    private volatile boolean inverted = true;

    public void configure(String path, boolean inverted) {
        if (path != null && !path.trim().isEmpty()) this.path = path.trim();
        this.inverted = inverted;
    }

    public boolean available() {
        return new File(path).exists();
    }

    /** @return true = EIN, false = AUS; null wenn nicht lesbar */
    public synchronized Boolean get() {
        try {
            String v = new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.US_ASCII).trim();
            boolean raw = !v.startsWith("0");
            return inverted != raw;
        } catch (IOException e) {
            return null;
        }
    }

    public synchronized boolean set(boolean on) {
        boolean raw = inverted != on;
        try (FileOutputStream out = new FileOutputStream(path)) {
            out.write((raw ? "1" : "0").getBytes(StandardCharsets.US_ASCII));
            return true;
        } catch (IOException e) {
            Log.add("Relais schreiben fehlgeschlagen: " + e.getMessage());
            return false;
        }
    }

    /**
     * Wertet einen Befehl aus: on/off/1/0/true/false/ein/aus/toggle/um.
     * @return neuer Zustand, oder null bei unbekanntem Befehl
     */
    public Boolean command(String cmd) {
        String c = cmd == null ? "" : cmd.trim().toLowerCase(Locale.ROOT);
        Boolean target;
        switch (c) {
            case "on": case "1": case "true": case "ein": case "an":
                target = true;
                break;
            case "off": case "0": case "false": case "aus":
                target = false;
                break;
            case "toggle": case "um": case "umschalten": {
                Boolean cur = get();
                target = cur == null || !cur;
                break;
            }
            default:
                return null;
        }
        return set(target) ? target : null;
    }
}
