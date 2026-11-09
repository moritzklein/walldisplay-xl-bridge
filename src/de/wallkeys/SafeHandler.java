package de.wallkeys;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

/**
 * Handler für den Main-Thread, der Fehler protokolliert, statt den Prozess zu beenden.
 *
 * Wichtig, weil Android einen abgestürzten AccessibilityService nicht automatisch neu bindet –
 * ein einzelner Fehler würde sonst Tasten, Sensoren und Webinterface bis zur manuellen
 * Reaktivierung lahmlegen.
 */
public final class SafeHandler extends Handler {
    public SafeHandler() {
        super(Looper.getMainLooper());
    }

    @Override
    public void dispatchMessage(Message msg) {
        try {
            super.dispatchMessage(msg);
        } catch (Throwable t) {
            android.util.Log.e("WallKeys", "Interner Fehler (abgefangen)", t);
            Log.add("Interner Fehler (abgefangen): " + t);
        }
    }
}
