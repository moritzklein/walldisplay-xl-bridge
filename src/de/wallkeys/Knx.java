package de.wallkeys;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * KNXnet/IP-Client ohne externe Bibliotheken.
 *
 * Tunneling (Unicast, UDP 3671, NAT-Modus): pro Tastendruck wird ein Tunnel geöffnet, die
 * Telegramme werden gesendet (inkl. Bestätigung L_Data.con) und der Tunnel wieder geschlossen.
 * Damit wird kein Tunnel-Slot des Routers dauerhaft belegt.
 *
 * Routing (Multicast 224.0.23.12): verbindungslos, nur im selben Netz sinnvoll.
 *
 * Telegramm-Syntax (eine Zeile pro Telegramm):
 *   &lt;Gruppenadresse&gt; &lt;DPT&gt; &lt;Wert&gt; [Status-GA]
 *   1/1/10 1.001 1
 *   1/1/10 1.001 toggle 1/1/11
 *   3/2/5  5.001 75
 *   4/0/1  9.001 21.5
 *   2/0/7  17.001 3
 */
public final class Knx {
    private static final int CONNECT_REQ = 0x0205, CONNECT_RES = 0x0206, DISCONNECT_REQ = 0x0209,
            DISCONNECT_RES = 0x020A, TUNNEL_REQ = 0x0420, TUNNEL_ACK = 0x0421, ROUTING_IND = 0x0530;
    private static final int L_DATA_REQ = 0x11, L_DATA_CON = 0x2E, L_DATA_IND = 0x29;

    /** Zuletzt gesendete DPT-1-Werte (Fallback für toggle ohne Status-Antwort). */
    private static final Map<Integer, Integer> LAST = new HashMap<>();
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    private Knx() {}

    // ======================================================================= Telegramm-Modell

    public static final class Telegram {
        final int ga;
        final String gaText, dpt, value;
        final int statusGa;
        final boolean toggle;
        final byte[] apdu; // null bei toggle

        Telegram(int ga, String gaText, String dpt, String value, int statusGa) {
            this.ga = ga;
            this.gaText = gaText;
            this.dpt = dpt;
            this.value = value;
            this.statusGa = statusGa;
            this.toggle = "toggle".equalsIgnoreCase(value) || "um".equalsIgnoreCase(value);
            if (toggle && !dpt.startsWith("1")) throw new IllegalArgumentException("toggle nur mit DPT 1.x");
            this.apdu = toggle ? null : encode(dpt, value);
        }

        @Override
        public String toString() {
            return gaText + " " + dpt + " " + value;
        }
    }

    /** Parst einen mehrzeiligen Telegramm-Block. Leere Zeilen und # Kommentare werden ignoriert. */
    public static List<Telegram> parse(String block) {
        List<Telegram> out = new ArrayList<>();
        if (block == null) return out;
        String[] lines = block.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            int hash = l.indexOf('#');
            if (hash >= 0) l = l.substring(0, hash).trim();
            if (l.isEmpty()) continue;
            String[] p = l.split("\\s+");
            try {
                if (p.length < 3) throw new IllegalArgumentException("Format: GA DPT Wert [Status-GA]");
                int ga = parseGa(p[0]);
                String dpt = p[1].toLowerCase(Locale.ROOT).replace("dpt", "").replace("-", ".");
                // Wert darf Leerzeichen enthalten (z. B. Text bei DPT 16), Status-GA nur bei toggle
                String value;
                int status = -1;
                if (p.length >= 4 && p[2].equalsIgnoreCase("toggle")) {
                    value = p[2];
                    status = parseGa(p[3]);
                } else {
                    int at = l.indexOf(p[1], p[0].length());
                    value = l.substring(at + p[1].length()).trim();
                }
                out.add(new Telegram(ga, p[0], dpt, value, status));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Zeile " + (i + 1) + " „" + l + "“: " + e.getMessage());
            }
        }
        return out;
    }

    static int parseGa(String s) {
        String[] p = s.split("/");
        try {
            if (p.length == 3) {
                int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]), c = Integer.parseInt(p[2]);
                if (a < 0 || a > 31 || b < 0 || b > 7 || c < 0 || c > 255) throw new NumberFormatException();
                return (a << 11) | (b << 8) | c;
            } else if (p.length == 2) {
                int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]);
                if (a < 0 || a > 31 || b < 0 || b > 2047) throw new NumberFormatException();
                return (a << 11) | b;
            } else if (p.length == 1) {
                int v = Integer.parseInt(p[0]);
                if (v < 0 || v > 0xFFFF) throw new NumberFormatException();
                return v;
            }
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException("ungültige Gruppenadresse „" + s + "“");
    }

    static int parseIndividual(String s) {
        String[] p = s.trim().split("\\.");
        if (p.length != 3) throw new IllegalArgumentException("Physikalische Adresse im Format 1.1.250");
        int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]), c = Integer.parseInt(p[2]);
        return (a << 12) | (b << 8) | c;
    }

    // ======================================================================= DPT-Kodierung

    /** Liefert die APDU (TPCI/APCI + Daten) für GroupValueWrite. */
    static byte[] encode(String dpt, String raw) {
        String v = raw.trim();
        String main = dpt.contains(".") ? dpt.substring(0, dpt.indexOf('.')) : dpt;
        String sub = dpt.contains(".") ? dpt.substring(dpt.indexOf('.') + 1) : "";
        try {
            switch (main) {
                case "1":
                    return small(bool(v));
                case "2":
                    return small(range(intOf(v), 0, 3));
                case "3": {
                    String l = v.toLowerCase(Locale.ROOT);
                    if (l.equals("+") || l.equals("up") || l.equals("heller") || l.equals("auf")) return small(0x09);
                    if (l.equals("-") || l.equals("down") || l.equals("dunkler") || l.equals("ab")) return small(0x01);
                    if (l.equals("stop")) return small(0x00);
                    return small(range(intOf(v), 0, 15));
                }
                case "5": {
                    double d = num(v);
                    if (sub.equals("001")) return big((int) Math.round(range(d, 0, 100) * 255 / 100.0));
                    if (sub.equals("003")) return big((int) Math.round(range(d, 0, 360) * 255 / 360.0));
                    return big(range(intOf(v), 0, 255));
                }
                case "6":
                    return big(range(intOf(v), -128, 127) & 0xFF);
                case "7": {
                    int x = range(intOf(v), 0, 65535);
                    return big(x >> 8, x & 0xFF);
                }
                case "8": {
                    int x = range(intOf(v), -32768, 32767);
                    return big((x >> 8) & 0xFF, x & 0xFF);
                }
                case "9":
                    return big(float16(num(v)));
                case "12": {
                    long x = Long.parseLong(v);
                    if (x < 0 || x > 0xFFFFFFFFL) throw new IllegalArgumentException("0..4294967295");
                    return big(ByteBuffer.allocate(4).putInt((int) x).array());
                }
                case "13":
                    return big(ByteBuffer.allocate(4).putInt(Integer.parseInt(v)).array());
                case "14":
                    return big(ByteBuffer.allocate(4).putFloat((float) num(v)).array());
                case "16": {
                    byte[] t = new byte[14];
                    byte[] s = v.getBytes(StandardCharsets.ISO_8859_1);
                    System.arraycopy(s, 0, t, 0, Math.min(14, s.length));
                    return big(t);
                }
                case "17":
                    return big(range(intOf(v), 1, 64) - 1);
                case "18": {
                    String l = v.toLowerCase(Locale.ROOT);
                    boolean learn = l.startsWith("learn") || l.startsWith("lernen");
                    int n = range(intOf(l.replaceAll("[^0-9]", "")), 1, 64);
                    return big((learn ? 0x80 : 0) | (n - 1));
                }
                case "20":
                    return big(range(intOf(v), 0, 255));
                case "232": {
                    String h = v.startsWith("#") ? v.substring(1) : v;
                    if (h.matches("[0-9a-fA-F]{6}")) {
                        int x = Integer.parseInt(h, 16);
                        return big((x >> 16) & 0xFF, (x >> 8) & 0xFF, x & 0xFF);
                    }
                    String[] c = v.split("[,;\\s]+");
                    if (c.length != 3) throw new IllegalArgumentException("#RRGGBB oder r,g,b");
                    return big(range(intOf(c[0]), 0, 255), range(intOf(c[1]), 0, 255), range(intOf(c[2]), 0, 255));
                }
                default:
                    throw new IllegalArgumentException("DPT " + dpt + " wird nicht unterstützt");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("ungültiger Wert „" + raw + "“ für DPT " + dpt);
        }
    }

    private static int bool(String v) {
        switch (v.toLowerCase(Locale.ROOT)) {
            case "1": case "on": case "ein": case "an": case "true": case "auf": return 1;
            case "0": case "off": case "aus": case "false": case "ab": return 0;
            default: throw new IllegalArgumentException("DPT 1 erwartet 0/1, ein/aus oder toggle");
        }
    }

    private static int intOf(String v) {
        return Integer.parseInt(v.trim());
    }

    private static double num(String v) {
        return Double.parseDouble(v.trim().replace(',', '.'));
    }

    private static int range(int x, int lo, int hi) {
        if (x < lo || x > hi) throw new IllegalArgumentException("Wert " + x + " außerhalb " + lo + ".." + hi);
        return x;
    }

    private static double range(double x, double lo, double hi) {
        if (x < lo || x > hi) throw new IllegalArgumentException("Wert " + x + " außerhalb " + lo + ".." + hi);
        return x;
    }

    static int[] float16(double f) {
        double m = f * 100.0;
        int e = 0;
        while ((m > 2047 || m < -2048) && e < 15) {
            m /= 2;
            e++;
        }
        long mi = Math.round(m);
        if (mi > 2047) mi = 2047;
        if (mi < -2048) mi = -2048;
        int mant = (int) mi & 0x7FF;
        int sign = mi < 0 ? 1 : 0;
        return new int[]{(sign << 7) | (e << 3) | (mant >> 8), mant & 0xFF};
    }

    private static byte[] small(int v) {
        return new byte[]{0x00, (byte) (0x80 | (v & 0x3F))};
    }

    private static byte[] big(int... d) {
        byte[] b = new byte[2 + d.length];
        b[0] = 0x00;
        b[1] = (byte) 0x80;
        for (int i = 0; i < d.length; i++) b[2 + i] = (byte) d[i];
        return b;
    }

    private static byte[] big(byte[] d) {
        byte[] b = new byte[2 + d.length];
        b[0] = 0x00;
        b[1] = (byte) 0x80;
        System.arraycopy(d, 0, b, 2, d.length);
        return b;
    }

    private static final byte[] READ = {0x00, 0x00};

    // ======================================================================= Versand

    public static final class Settings {
        String mode = "tunnel", host = "", multicast = "224.0.23.12", sourceAddr = "15.15.250";
        int port = 3671, timeoutMs = 2000;
    }

    /** Sendet die Telegramme asynchron (seriell, damit sich Tunnel nicht überschneiden). */
    public static void send(Settings s, List<Telegram> telegrams, String label) {
        if (telegrams.isEmpty()) return;
        EXEC.execute(() -> {
            try {
                if ("routing".equals(s.mode)) routing(s, telegrams);
                else tunnel(s, telegrams);
            } catch (Exception e) {
                Log.add("KNX Fehler (" + label + "): " + e.getMessage());
            }
        });
    }

    private static void routing(Settings s, List<Telegram> telegrams) throws IOException {
        int src = parseIndividual(s.sourceAddr);
        InetAddress grp = InetAddress.getByName(s.multicast);
        try (MulticastSocket sock = new MulticastSocket()) {
            sock.setTimeToLive(16);
            for (Telegram t : telegrams) {
                byte[] apdu = t.apdu;
                if (t.toggle) {
                    int nv = 1 - LAST.getOrDefault(t.ga, 0);
                    apdu = small(nv);
                    LAST.put(t.ga, nv);
                }
                byte[] cemi = cemi(L_DATA_IND, src, t.ga, apdu);
                byte[] f = frame(ROUTING_IND, cemi);
                sock.send(new DatagramPacket(f, f.length, grp, s.port));
                Log.add("KNX Routing → " + (t.toggle ? t.gaText + " = " + LAST.get(t.ga) + " (toggle)" : t.toString()));
            }
        }
    }

    // ---------------------------------------------------------------- Tunneling

    private static final class Tunnel implements AutoCloseable {
        final DatagramSocket sock;
        final InetSocketAddress server;
        final int timeout;
        int channel = -1, seq = 0;
        final List<byte[]> inbox = new ArrayList<>(); // empfangene cEMI-Frames

        Tunnel(Settings s) throws IOException {
            sock = new DatagramSocket();
            server = new InetSocketAddress(InetAddress.getByName(s.host), s.port);
            timeout = Math.max(500, s.timeoutMs);
        }

        void connect() throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            b.write(nat(), 0, 8);     // Control-Endpoint (NAT: 0.0.0.0:0)
            b.write(nat(), 0, 8);     // Data-Endpoint
            b.write(new byte[]{0x04, 0x04, 0x02, 0x00}, 0, 4); // CRI: Tunnel, Link-Layer
            sendRaw(frame(CONNECT_REQ, b.toByteArray()));
            long end = System.currentTimeMillis() + timeout;
            while (true) {
                byte[] r = receive(end);
                if (r == null) throw new IOException("Keine Antwort von " + server + " (Firewall UDP 3671? IP richtig?)");
                if (service(r) != CONNECT_RES) continue;
                int status = r[7] & 0xFF;
                if (status != 0) throw new IOException(connectError(status));
                channel = r[6] & 0xFF;
                return;
            }
        }

        /** Sendet einen cEMI-Frame im Tunnel und wartet auf TUNNELING_ACK (1 Wiederholung). */
        void sendCemi(byte[] cemi) throws IOException {
            byte[] body = new byte[4 + cemi.length];
            body[0] = 0x04;
            body[1] = (byte) channel;
            body[2] = (byte) seq;
            body[3] = 0x00;
            System.arraycopy(cemi, 0, body, 4, cemi.length);
            byte[] f = frame(TUNNEL_REQ, body);
            for (int attempt = 0; attempt < 2; attempt++) {
                sendRaw(f);
                long end = System.currentTimeMillis() + 1000;
                byte[] r;
                while ((r = receive(end)) != null) {
                    int sv = service(r);
                    if (sv == TUNNEL_ACK && (r[7] & 0xFF) == channel && (r[8] & 0xFF) == seq) {
                        seq = (seq + 1) & 0xFF;
                        if ((r[9] & 0xFF) != 0) throw new IOException("Tunnel-ACK Fehler " + (r[9] & 0xFF));
                        return;
                    }
                    handleIncoming(r);
                }
            }
            throw new IOException("Kein Tunnel-ACK vom Router");
        }

        /** Wartet auf einen eingehenden cEMI-Frame, der das Prädikat erfüllt. */
        byte[] await(java.util.function.Predicate<byte[]> want, int ms) throws IOException {
            for (int i = 0; i < inbox.size(); i++) {
                if (want.test(inbox.get(i))) return inbox.remove(i);
            }
            long end = System.currentTimeMillis() + ms;
            byte[] r;
            while ((r = receive(end)) != null) {
                byte[] c = handleIncoming(r);
                if (c != null && want.test(c)) {
                    inbox.remove(c);
                    return c;
                }
            }
            return null;
        }

        /** Bestätigt eingehende TUNNELING_REQUESTs und legt deren cEMI im Posteingang ab. */
        private byte[] handleIncoming(byte[] r) throws IOException {
            int sv = service(r);
            if (sv == TUNNEL_REQ && r.length > 10 && (r[7] & 0xFF) == channel) {
                byte[] ack = frame(TUNNEL_ACK, new byte[]{0x04, (byte) channel, r[8], 0x00});
                sendRaw(ack);
                byte[] c = new byte[r.length - 10];
                System.arraycopy(r, 10, c, 0, c.length);
                inbox.add(c);
                return c;
            }
            if (sv == DISCONNECT_REQ) {
                sendRaw(frame(DISCONNECT_RES, new byte[]{(byte) channel, 0x00}));
                channel = -1;
                throw new IOException("Router hat den Tunnel geschlossen");
            }
            return null;
        }

        void disconnect() {
            if (channel < 0) return;
            try {
                byte[] body = new byte[10];
                body[0] = (byte) channel;
                System.arraycopy(nat(), 0, body, 2, 8);
                sendRaw(frame(DISCONNECT_REQ, body));
                long end = System.currentTimeMillis() + 500;
                byte[] r;
                while ((r = receive(end)) != null) {
                    if (service(r) == DISCONNECT_RES) break;
                    if (service(r) == TUNNEL_REQ) {
                        sendRaw(frame(TUNNEL_ACK, new byte[]{0x04, (byte) channel, r[8], 0x00}));
                    }
                }
            } catch (IOException ignored) {
            }
            channel = -1;
        }

        private void sendRaw(byte[] f) throws IOException {
            sock.send(new DatagramPacket(f, f.length, server));
        }

        private byte[] receive(long endMillis) throws IOException {
            byte[] buf = new byte[512];
            while (true) {
                long left = endMillis - System.currentTimeMillis();
                if (left <= 0) return null;
                sock.setSoTimeout((int) left);
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(p);
                } catch (SocketTimeoutException e) {
                    return null;
                }
                if (p.getLength() < 6 || buf[0] != 0x06 || buf[1] != 0x10) continue;
                byte[] r = new byte[p.getLength()];
                System.arraycopy(buf, 0, r, 0, r.length);
                return r;
            }
        }

        @Override
        public void close() {
            disconnect();
            sock.close();
        }
    }

    private static void tunnel(Settings s, List<Telegram> telegrams) throws IOException {
        if (s.host.isEmpty()) throw new IOException("Keine Router-IP konfiguriert");
        long t0 = System.currentTimeMillis();
        try (Tunnel t = new Tunnel(s)) {
            t.connect();
            for (Telegram tg : telegrams) {
                byte[] apdu = tg.apdu;
                String shown = tg.toString();
                if (tg.toggle) {
                    int cur = readBit(t, tg.statusGa >= 0 ? tg.statusGa : tg.ga);
                    String how = cur >= 0 ? "Status" : "Speicher";
                    if (cur < 0) cur = LAST.getOrDefault(tg.ga, 0);
                    int nv = 1 - cur;
                    apdu = small(nv);
                    shown = tg.gaText + " " + tg.dpt + " " + nv + " (toggle, " + how + "=" + cur + ")";
                }
                t.sendCemi(cemi(L_DATA_REQ, 0, tg.ga, apdu));
                final int ga = tg.ga;
                byte[] con = t.await(c -> (c[0] & 0xFF) == L_DATA_CON && dest(c) == ga, 1500);
                if (tg.dpt.equals("1") || tg.dpt.startsWith("1.")) LAST.put(tg.ga, apdu[1] & 0x01);
                if (con == null) Log.add("KNX → " + shown + " (ohne Bestätigung)");
                else if ((con[2 + (con[1] & 0xFF)] & 0x01) != 0) Log.add("KNX → " + shown + " – Bus meldet Fehler (keine Quittung)");
                else Log.add("KNX → " + shown);
            }
        }
        Log.add("KNX Tunnel fertig in " + (System.currentTimeMillis() - t0) + " ms");
    }

    /** Liest einen DPT-1-Wert per GroupValueRead. Liefert -1, wenn niemand antwortet. */
    private static int readBit(Tunnel t, int ga) throws IOException {
        t.sendCemi(cemi(L_DATA_REQ, 0, ga, READ));
        byte[] r = t.await(c -> {
            if ((c[0] & 0xFF) != L_DATA_IND || dest(c) != ga) return false;
            int apci = apci(c);
            return apci == 0x40 || apci == 0x80; // Response oder Write
        }, 1200);
        if (r == null) return -1;
        int base = 2 + (r[1] & 0xFF);
        return r[base + 8] & 0x01;
    }

    // ======================================================================= Rahmen-Helfer

    private static byte[] cemi(int msgCode, int src, int ga, byte[] apdu) {
        byte[] c = new byte[9 + apdu.length];
        c[0] = (byte) msgCode;
        c[1] = 0x00;                 // keine Zusatzinfo
        c[2] = (byte) 0xBC;          // Standard-Frame, nicht wiederholen, Priorität niedrig
        c[3] = (byte) 0xE0;          // Gruppenadresse, Routing-Zähler 6
        c[4] = (byte) (src >> 8);
        c[5] = (byte) src;
        c[6] = (byte) (ga >> 8);
        c[7] = (byte) ga;
        c[8] = (byte) (apdu.length - 1);
        System.arraycopy(apdu, 0, c, 9, apdu.length);
        return c;
    }

    private static int dest(byte[] c) {
        int base = 2 + (c[1] & 0xFF);
        if (c.length < base + 6) return -1;
        return ((c[base + 4] & 0xFF) << 8) | (c[base + 5] & 0xFF);
    }

    private static int apci(byte[] c) {
        int base = 2 + (c[1] & 0xFF);
        if (c.length < base + 9) return -1;
        return (((c[base + 7] & 0x03) << 8) | (c[base + 8] & 0xFF)) & 0x3C0;
    }

    private static byte[] frame(int service, byte[] body) {
        int len = 6 + body.length;
        byte[] f = new byte[len];
        f[0] = 0x06;
        f[1] = 0x10;
        f[2] = (byte) (service >> 8);
        f[3] = (byte) service;
        f[4] = (byte) (len >> 8);
        f[5] = (byte) len;
        System.arraycopy(body, 0, f, 6, body.length);
        return f;
    }

    private static int service(byte[] r) {
        return ((r[2] & 0xFF) << 8) | (r[3] & 0xFF);
    }

    private static byte[] nat() {
        return new byte[]{0x08, 0x01, 0, 0, 0, 0, 0, 0};
    }

    private static String connectError(int status) {
        switch (status) {
            case 0x22: return "Router: Verbindungstyp nicht unterstützt";
            case 0x23: return "Router: Option nicht unterstützt";
            case 0x24: return "Router: alle Tunnel belegt (E_NO_MORE_CONNECTIONS)";
            case 0x29: return "Router: Tunnel-Adresse belegt";
            default: return "Router lehnt Verbindung ab (Status 0x" + Integer.toHexString(status) + ")";
        }
    }
}
