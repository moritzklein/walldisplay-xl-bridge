package de.wallkeys;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Minimaler MQTT-3.1.1-Client ohne externe Bibliotheken: Publish (QoS 0/1) und Subscribe (QoS 0).
 * Hält eine Dauerverbindung, sendet PINGREQ, verbindet sich selbständig neu
 * und setzt ein Last Will "offline" auf &lt;baseTopic&gt;/status.
 */
public final class MqttClient {
    public interface Listener {
        /** Eingehende Nachricht auf einem abonnierten Topic (Reader-Thread). */
        void onMessage(String topic, String payload);

        /** Nach jedem (Wieder-)Verbinden, z. B. um retained Zustände zu senden. */
        void onConnected();
    }

    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
    private volatile java.util.List<String> subscriptions = new java.util.ArrayList<>();
    private volatile Listener listener;

    public void setListener(Listener l) {
        listener = l;
    }

    /** Abonnements werden beim nächsten (Wieder-)Verbinden gesetzt. */
    public void setSubscriptions(java.util.List<String> topics) {
        subscriptions = new java.util.ArrayList<>(topics);
    }

    private volatile String host = "";
    private volatile int port = 1883;
    private volatile String user = "", pass = "", clientId = "wallkeys", statusTopic = "walldisplay/status";
    private volatile int keepAlive = 30;
    private volatile boolean enabled;

    private Socket socket;
    private OutputStream out;
    private volatile boolean connected;
    private volatile long lastRx;
    private int packetId = 1;
    private int backoffSec = 2;

    public MqttClient() {
        exec.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    public boolean isConnected() {
        return connected;
    }

    public synchronized void configure(boolean enabled, String host, int port, String user, String pass,
                                       String clientId, String baseTopic, int keepAlive) {
        disconnect(true);
        this.enabled = enabled && host != null && !host.trim().isEmpty();
        this.host = host == null ? "" : host.trim();
        this.port = port;
        this.user = user == null ? "" : user;
        this.pass = pass == null ? "" : pass;
        this.clientId = (clientId == null || clientId.isEmpty()) ? "wallkeys" : clientId;
        this.statusTopic = stripSlash(baseTopic) + "/status";
        this.keepAlive = Math.max(5, keepAlive);
        backoffSec = 0;
        nextTry = 0;
    }

    /** Veröffentlicht asynchron; ist die Verbindung weg, wird erst neu verbunden. */
    public void publish(String topic, String payload, int qos, boolean retain) {
        publish(topic, payload, qos, retain, true);
    }

    /** @param log false für häufige Werte (Sensoren), die das Protokoll nicht füllen sollen */
    public void publish(String topic, String payload, int qos, boolean retain, boolean log) {
        exec.execute(() -> {
            synchronized (MqttClient.this) {
                if (!enabled) return;
                if (!connected && System.currentTimeMillis() >= nextTry) connect();
                if (!connected) {
                    if (log) Log.add("MQTT nicht verbunden, verworfen: " + topic + " = " + payload);
                    return;
                }
                try {
                    sendPublish(topic, payload, Math.min(1, Math.max(0, qos)), retain);
                    if (log) Log.add("MQTT → " + topic + " = " + payload);
                } catch (IOException e) {
                    Log.add("MQTT Sendefehler: " + e.getMessage());
                    disconnect(false);
                }
            }
        });
    }

    public void shutdown() {
        exec.execute(() -> {
            synchronized (MqttClient.this) {
                disconnect(true);
                enabled = false;
            }
        });
        exec.shutdown();
    }

    // ---------------------------------------------------------------- intern

    private long nextTry;
    private long lastPing;

    private synchronized void tick() {
        if (!enabled) return;
        long now = System.currentTimeMillis();
        if (!connected) {
            if (now >= nextTry) connect();
            return;
        }
        if (now - lastPing >= keepAlive * 1000L / 2) {
            try {
                write(new byte[]{(byte) 0xC0, 0x00}); // PINGREQ
                lastPing = now;
            } catch (IOException e) {
                Log.add("MQTT Ping fehlgeschlagen: " + e.getMessage());
                disconnect(false);
                return;
            }
        }
        if (now - lastRx > keepAlive * 1500L) {
            Log.add("MQTT Timeout, verbinde neu");
            disconnect(false);
        }
    }

    private void connect() {
        try {
            Socket s = new Socket();
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(host, port), 5000);
            s.setSoTimeout(5000);
            socket = s;
            out = s.getOutputStream();

            ByteArrayOutputStream vh = new ByteArrayOutputStream();
            writeString(vh, "MQTT");
            vh.write(4); // Protokoll-Level 3.1.1
            int flags = 0x02 /* clean session */ | 0x04 /* will */ | 0x20 /* will retain */;
            if (!user.isEmpty()) flags |= 0x80;
            if (!user.isEmpty() && !pass.isEmpty()) flags |= 0x40;
            vh.write(flags);
            vh.write(keepAlive >> 8);
            vh.write(keepAlive & 0xFF);
            writeString(vh, clientId);
            writeString(vh, statusTopic);
            writeBinary(vh, "offline".getBytes(StandardCharsets.UTF_8));
            if (!user.isEmpty()) writeString(vh, user);
            if (!user.isEmpty() && !pass.isEmpty()) writeString(vh, pass);
            write(packet(0x10, vh.toByteArray()));

            DataInputStream in = new DataInputStream(s.getInputStream());
            int type = in.readUnsignedByte();
            int len = readLength(in);
            byte[] body = new byte[len];
            in.readFully(body);
            if ((type & 0xF0) != 0x20 || len < 2) throw new IOException("Unerwartete Antwort " + type);
            if (body[1] != 0) throw new IOException(connackText(body[1]));

            s.setSoTimeout(0);
            connected = true;
            lastRx = lastPing = System.currentTimeMillis();
            backoffSec = 2;
            Log.add("MQTT verbunden mit " + host + ":" + port);
            startReader(s, in);
            sendPublish(statusTopic, "online", 1, true);
            java.util.List<String> subs = subscriptions;
            if (!subs.isEmpty()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                int id = packetId;
                packetId = packetId >= 65535 ? 1 : packetId + 1;
                b.write(id >> 8);
                b.write(id & 0xFF);
                for (String t : subs) {
                    writeString(b, t);
                    b.write(0); // QoS 0
                }
                write(packet(0x82, b.toByteArray()));
                Log.add("MQTT abonniert: " + String.join(", ", subs));
            }
            Listener l = listener;
            if (l != null) exec.execute(l::onConnected);
        } catch (IOException e) {
            Log.add("MQTT Verbindung zu " + host + ":" + port + " fehlgeschlagen: " + e.getMessage());
            disconnect(false);
            backoffSec = Math.min(60, Math.max(2, backoffSec * 2));
            nextTry = System.currentTimeMillis() + backoffSec * 1000L;
        }
    }

    private void startReader(Socket s, DataInputStream in) {
        Thread t = new Thread(() -> {
            try {
                while (true) {
                    int type = in.readUnsignedByte();
                    int len = readLength(in);
                    byte[] body = new byte[len];
                    in.readFully(body);
                    lastRx = System.currentTimeMillis();
                    if ((type & 0xF0) == 0xE0) break; // DISCONNECT vom Broker
                    if ((type & 0xF0) == 0x30 && len >= 2) onPublish(type, body);
                }
            } catch (IOException ignored) {
                // Verbindung beendet
            }
            synchronized (MqttClient.this) {
                if (socket == s && connected) {
                    Log.add("MQTT Verbindung getrennt");
                    disconnect(false);
                }
            }
        }, "mqtt-reader");
        t.setDaemon(true);
        t.start();
    }

    /** Eingehendes PUBLISH zerlegen, bei QoS 1 mit PUBACK bestätigen, an den Listener geben. */
    private void onPublish(int header, byte[] body) {
        int qos = (header >> 1) & 0x03;
        int tlen = ((body[0] & 0xFF) << 8) | (body[1] & 0xFF);
        if (2 + tlen > body.length) return;
        String topic = new String(body, 2, tlen, StandardCharsets.UTF_8);
        int off = 2 + tlen;
        if (qos > 0) {
            if (off + 2 > body.length) return;
            byte[] ack = {0x40, 0x02, body[off], body[off + 1]};
            off += 2;
            exec.execute(() -> {
                synchronized (MqttClient.this) {
                    try {
                        write(ack);
                    } catch (IOException ignored) {
                    }
                }
            });
        }
        String payload = new String(body, off, body.length - off, StandardCharsets.UTF_8);
        Listener l = listener;
        if (l != null) {
            try {
                l.onMessage(topic, payload);
            } catch (RuntimeException e) {
                Log.add("MQTT Verarbeitungsfehler: " + e.getMessage());
            }
        }
    }

    private void disconnect(boolean graceful) {
        if (socket != null) {
            if (graceful && connected) {
                try {
                    sendPublish(statusTopic, "offline", 1, true);
                    write(new byte[]{(byte) 0xE0, 0x00});
                } catch (IOException ignored) {
                }
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        socket = null;
        out = null;
        connected = false;
    }

    private void sendPublish(String topic, String payload, int qos, boolean retain) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeString(b, topic);
        if (qos > 0) {
            int id = packetId;
            packetId = packetId >= 65535 ? 1 : packetId + 1;
            b.write(id >> 8);
            b.write(id & 0xFF);
        }
        b.write(payload.getBytes(StandardCharsets.UTF_8));
        int header = 0x30 | (qos << 1) | (retain ? 1 : 0);
        write(packet(header, b.toByteArray()));
    }

    private void write(byte[] data) throws IOException {
        if (out == null) throw new IOException("nicht verbunden");
        out.write(data);
        out.flush();
    }

    private static byte[] packet(int header, byte[] body) {
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        p.write(header);
        int len = body.length;
        do {
            int d = len % 128;
            len /= 128;
            if (len > 0) d |= 0x80;
            p.write(d);
        } while (len > 0);
        p.write(body, 0, body.length);
        return p.toByteArray();
    }

    private static int readLength(InputStream in) throws IOException {
        int mul = 1, val = 0, d;
        do {
            d = in.read();
            if (d < 0) throw new IOException("EOF");
            val += (d & 0x7F) * mul;
            mul *= 128;
        } while ((d & 0x80) != 0 && mul <= 128 * 128 * 128);
        return val;
    }

    private static void writeString(ByteArrayOutputStream b, String s) {
        writeBinary(b, s.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBinary(ByteArrayOutputStream b, byte[] d) {
        b.write(d.length >> 8);
        b.write(d.length & 0xFF);
        b.write(d, 0, d.length);
    }

    private static String stripSlash(String s) {
        if (s == null || s.isEmpty()) return "walldisplay";
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String connackText(int code) {
        switch (code) {
            case 1: return "Protokollversion abgelehnt";
            case 2: return "Client-ID abgelehnt";
            case 3: return "Broker nicht verfügbar";
            case 4: return "Benutzername/Passwort falsch";
            case 5: return "Nicht autorisiert";
            default: return "CONNACK Fehler " + code;
        }
    }
}
