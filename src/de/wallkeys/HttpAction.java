package de.wallkeys;

import android.util.Base64;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Führt Webhooks aus. Zugangsdaten können direkt in der URL stehen
 * (http://user:pass@host/pfad) und werden als HTTP Basic Auth gesendet –
 * so funktioniert z. B. die DoorBird-API (/bha-api/open-door.cgi).
 */
public final class HttpAction {
    private static final ExecutorService EXEC = Executors.newFixedThreadPool(3);
    private static SSLSocketFactory trustAll;
    private static final HostnameVerifier ANY_HOST = (h, s) -> true;

    private HttpAction() {}

    public static void fire(String url, String method, String body, int timeoutMs, boolean insecureTls) {
        if (url == null || url.trim().isEmpty()) return;
        final String u = url.trim();
        EXEC.execute(() -> run(u, method, body, timeoutMs, insecureTls));
    }

    private static void run(String rawUrl, String method, String body, int timeoutMs, boolean insecureTls) {
        String shown = rawUrl.replaceAll("//[^/@]*@", "//***@");
        HttpURLConnection c = null;
        try {
            URL url = new URL(rawUrl);
            String auth = url.getUserInfo();
            if (auth != null) {
                url = new URL(url.getProtocol(), url.getHost(), url.getPort(), url.getFile());
            }
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setInstanceFollowRedirects(true);
            c.setUseCaches(false);
            String m = (method == null || method.isEmpty()) ? "GET" : method.toUpperCase();
            c.setRequestMethod(m);
            c.setRequestProperty("User-Agent", "WallKeys/1.0");
            if (auth != null) {
                String plain = URLDecoder.decode(auth, "UTF-8");
                c.setRequestProperty("Authorization", "Basic "
                        + Base64.encodeToString(plain.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
            }
            if (insecureTls && c instanceof HttpsURLConnection) {
                ((HttpsURLConnection) c).setSSLSocketFactory(trustAllFactory());
                ((HttpsURLConnection) c).setHostnameVerifier(ANY_HOST);
            }
            boolean hasBody = body != null && !body.isEmpty() && !"GET".equals(m);
            if (hasBody) {
                byte[] data = body.getBytes(StandardCharsets.UTF_8);
                String t = body.trim();
                c.setRequestProperty("Content-Type", (t.startsWith("{") || t.startsWith("["))
                        ? "application/json; charset=utf-8" : "text/plain; charset=utf-8");
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(data.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(data);
                }
            }
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (is != null) {
                byte[] buf = new byte[1024];
                while (is.read(buf) > 0) { /* Antwort verwerfen */ }
                is.close();
            }
            Log.add("HTTP " + m + " " + shown + " → " + code);
        } catch (Exception e) {
            Log.add("HTTP Fehler " + shown + ": " + e.getClass().getSimpleName() + " " + e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static synchronized SSLSocketFactory trustAllFactory() throws Exception {
        if (trustAll == null) {
            TrustManager[] tm = {new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tm, new SecureRandom());
            trustAll = ctx.getSocketFactory();
        }
        return trustAll;
    }
}
