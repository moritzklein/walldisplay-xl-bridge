package de.wallkeys;

import org.json.JSONArray;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Kleiner Ringpuffer für das Webinterface, zusätzlich Ausgabe nach logcat (Tag "WallKeys"). */
public final class Log {
    private static final int MAX = 200;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();

    private Log() {}

    public static void add(String msg) {
        String line = new SimpleDateFormat("dd.MM. HH:mm:ss", Locale.GERMANY).format(new Date()) + "  " + msg;
        android.util.Log.i("WallKeys", msg);
        synchronized (LINES) {
            LINES.addFirst(line);
            while (LINES.size() > MAX) LINES.removeLast();
        }
    }

    public static JSONArray toJson() {
        JSONArray a = new JSONArray();
        synchronized (LINES) {
            for (String l : LINES) a.put(l);
        }
        return a;
    }
}
