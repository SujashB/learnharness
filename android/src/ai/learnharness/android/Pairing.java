package ai.learnharness.android;

import org.json.JSONObject;
import java.net.URL;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** The certificate fingerprint is supplied out of band by the trusted PC. */
final class Pairing {
    final String url, token, fingerprint;
    private final SSLContext tls;
    Pairing(String text) throws Exception {
        JSONObject value = new JSONObject(text);
        if (value.getInt("version") != 1) throw new Exception("Unsupported pairing version");
        URL endpoint = new URL(value.getString("url"));
        if (!endpoint.getProtocol().equals("https") || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getRef() != null || !(endpoint.getPath().isEmpty() || endpoint.getPath().equals("/"))) throw new Exception("Pairing needs an HTTPS PC address");
        url = "https://" + endpoint.getAuthority();
        token = value.getString("token");
        fingerprint = value.getString("fingerprint");
        if (!token.matches("[a-f0-9]{64}") || !fingerprint.matches("[a-f0-9]{64}")) throw new Exception("Invalid pairing credentials");
        tls = SSLContext.getInstance("TLS");
        tls.init(null, new TrustManager[]{new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException { throw new CertificateException("Client certificates are not used"); }
            public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                try {
                    if (chain == null || chain.length == 0) throw new CertificateException("Missing certificate");
                    chain[0].checkValidity();
                    byte[] digest = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                    StringBuilder hex = new StringBuilder();
                    for (byte b : digest) hex.append(String.format("%02x", b & 255));
                    if (!MessageDigest.isEqual(hex.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII), fingerprint.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) throw new CertificateException("PC certificate changed. Pair again on the PC.");
                } catch (CertificateException e) { throw e; }
                catch (Exception e) { throw new CertificateException(e); }
            }
        }}, null);
    }
    HttpsURLConnection open(String requestPath) throws Exception {
        if (!requestPath.startsWith("/") || requestPath.startsWith("//")) throw new Exception("Invalid request path");
        HttpsURLConnection connection = (HttpsURLConnection)new URL(url + requestPath).openConnection();
        connection.setSSLSocketFactory(tls.getSocketFactory());
        // Identity is the exact paired certificate, not a public DNS name. The trust manager
        // still validates the leaf fingerprint and expiry on every TLS connection.
        connection.setHostnameVerifier((host, session) -> host.equals(java.net.URI.create(url).getHost()));
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(0); // Agent event streams can remain quiet for minutes.
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Accept-Encoding", "identity");
        return connection;
    }
    void check() throws Exception {
        HttpsURLConnection connection = open("/__android/health");
        connection.setReadTimeout(10000);
        try {
            if (connection.getResponseCode() != 200) throw new Exception("Pairing rejected by the PC");
            try (java.io.InputStream input = connection.getInputStream()) {
                byte[] bytes = new byte[1024]; int count = input.read(bytes);
                if (count < 0 || !new String(bytes, 0, count, java.nio.charset.StandardCharsets.UTF_8).contains("learnharness")) throw new Exception("This is not a LearnHarness gateway");
            }
        } finally { connection.disconnect(); }
    }
}
