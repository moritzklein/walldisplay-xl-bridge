package de.wallkeys;

import android.content.res.AssetManager;
import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sehr kleiner HTTP/1.0-Server für das Konfigurations-Webinterface.
 *
 * GET  /                  Weboberfläche
 * GET  /api/config        aktuelle Konfiguration
 * POST /api/config        Konfiguration speichern (JSON)
 * GET  /api/status        Status + Log
 * POST /api/test?b=1&e=short   Tastendruck simulieren
 * POST /api/relay?cmd=on|off|toggle   Relais schalten
 */
public final class WebServer {
    public interface Handler {
        JSONObject status();
        void saveConfig(JSONObject cfg) throws IOException;
        void test(int button, String event);

        /** Relais schalten: on/off/toggle. @return neuer Zustand oder null bei Fehler */
        Boolean relay(String cmd);
    }

    private final AssetManager assets;
    private final Config config;
    private final Handler handler;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private ServerSocket server;
    private Thread acceptThread;

    public WebServer(AssetManager assets, Config config, Handler handler) {
        this.assets = assets;
        this.config = config;
        this.handler = handler;
    }

    public synchronized void start(int port) {
        stop();
        try {
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(port));
            server = s;
            acceptThread = new Thread(() -> {
                while (!s.isClosed()) {
                    try {
                        Socket c = s.accept();
                        pool.execute(() -> handle(c));
                    } catch (IOException e) {
                        if (!s.isClosed()) Log.add("Webserver accept: " + e.getMessage());
                    }
                }
            }, "web-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            Log.add("Webinterface läuft auf Port " + port);
        } catch (IOException e) {
            Log.add("Webserver konnte Port " + port + " nicht öffnen: " + e.getMessage());
        }
    }

    public synchronized void stop() {
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
            server = null;
        }
    }

    private void handle(Socket sock) {
        try (Socket s = sock) {
            s.setSoTimeout(10000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase(Locale.ROOT);
            String target = parts[1];

            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int i = line.indexOf(':');
                if (i > 0) headers.put(line.substring(0, i).trim().toLowerCase(Locale.ROOT), line.substring(i + 1).trim());
            }
            int len = 0;
            try {
                len = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            } catch (NumberFormatException ignored) {
            }
            if (len > 256 * 1024) {
                send(out, 413, "text/plain", "zu groß");
                return;
            }
            byte[] body = new byte[len];
            int off = 0;
            while (off < len) {
                int r = in.read(body, off, len - off);
                if (r < 0) break;
                off += r;
            }

            String pw = config.get().optString("webPassword", "");
            if (!pw.isEmpty() && !authorized(headers.get("authorization"), pw)) {
                String msg = "HTTP/1.0 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"WallKeys\"\r\n"
                        + "Content-Length: 0\r\nConnection: close\r\n\r\n";
                out.write(msg.getBytes(StandardCharsets.UTF_8));
                return;
            }

            String path = target;
            Map<String, String> query = new HashMap<>();
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                for (String kv : target.substring(q + 1).split("&")) {
                    int e = kv.indexOf('=');
                    if (e > 0) query.put(URLDecoder.decode(kv.substring(0, e), "UTF-8"),
                            URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
                }
            }

            route(method, path, query, new String(body, 0, off, StandardCharsets.UTF_8), out);
        } catch (Exception e) {
            Log.add("Webserver Fehler: " + e.getMessage());
        }
    }

    private void route(String method, String path, Map<String, String> q, String body, OutputStream out) throws IOException {
        try {
            if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
                send(out, 200, "text/html; charset=utf-8", readAsset("index.html"));
            } else if ("GET".equals(method) && "/api/config".equals(path)) {
                send(out, 200, "application/json", config.get().toString(2));
            } else if ("POST".equals(method) && "/api/config".equals(path)) {
                handler.saveConfig(new JSONObject(body));
                send(out, 200, "application/json", "{\"ok\":true}");
            } else if ("GET".equals(method) && "/api/status".equals(path)) {
                send(out, 200, "application/json", handler.status().toString());
            } else if ("POST".equals(method) && "/api/test".equals(path)) {
                int b = Integer.parseInt(q.getOrDefault("b", "0"));
                String e = q.getOrDefault("e", "short");
                if (b < 1 || b > Config.BUTTONS || !java.util.Arrays.asList(Config.EVENTS).contains(e)) {
                    send(out, 400, "application/json", "{\"ok\":false,\"error\":\"b=1..4, e=short|long|release\"}");
                } else {
                    handler.test(b, e);
                    send(out, 200, "application/json", "{\"ok\":true}");
                }
            } else if ("POST".equals(method) && "/api/relay".equals(path)) {
                Boolean st = handler.relay(q.getOrDefault("cmd", "toggle"));
                if (st == null) send(out, 400, "application/json", "{\"ok\":false,\"error\":\"Relais nicht schaltbar\"}");
                else send(out, 200, "application/json", "{\"ok\":true,\"on\":" + st + "}");
            } else {
                send(out, 404, "text/plain", "nicht gefunden");
            }
        } catch (Exception e) {
            JSONObject err = new JSONObject();
            try {
                err.put("ok", false);
                err.put("error", String.valueOf(e.getMessage()));
            } catch (Exception ignored) {
            }
            send(out, 400, "application/json", err.toString());
        }
    }

    private static boolean authorized(String header, String pw) {
        if (header == null || !header.startsWith("Basic ")) return false;
        try {
            String dec = new String(Base64.decode(header.substring(6).trim(), Base64.DEFAULT), StandardCharsets.UTF_8);
            int i = dec.indexOf(':');
            return i >= 0 && dec.substring(i + 1).equals(pw);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private String readAsset(String name) throws IOException {
        try (InputStream is = assets.open(name)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) > 0) b.write(buf, 0, r);
            return b.toString("UTF-8");
        }
    }

    private static void send(OutputStream out, int code, String type, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        String status = code == 200 ? "OK" : code == 404 ? "Not Found" : code == 413 ? "Too Large" : "Bad Request";
        String head = "HTTP/1.0 " + code + " " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new IOException("Zeile zu lang");
        }
        if (c < 0 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }
}
