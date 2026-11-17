package ai.learnharness.android;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.HttpsURLConnection;

/** Loopback-only, cookie-gated HTTP relay. Wi-Fi traffic is always pinned TLS.
 * A real local origin preserves fetch/POST, SSE, files, and secure-context microphone APIs. */
final class LocalRelay implements Closeable {
    private final Pairing pairing;
    private final ServerSocket listener;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final Set<HttpsURLConnection> connections = ConcurrentHashMap.newKeySet();
    final String secret;
    final String origin;
    private volatile boolean closed;
    LocalRelay(Pairing pairing) throws Exception {
        this.pairing = pairing;
        byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
        StringBuilder hex = new StringBuilder(); for (byte b : random) hex.append(String.format("%02x", b & 255));
        secret = hex.toString();
        listener = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        origin = "http://127.0.0.1:" + listener.getLocalPort();
        workers.execute(() -> {
            while (!closed) {
                try {
                    Socket socket = listener.accept();
                    if (sockets.size() >= 64) { socket.close(); continue; }
                    sockets.add(socket); workers.execute(() -> serve(socket));
                } catch (IOException e) { if (!closed) close(); }
            }
        });
    }
    boolean owns(String url) {
        if (url == null) return false;
        try { URI uri = URI.create(url); return uri.getScheme().equals("http") && uri.getHost().equals("127.0.0.1") && uri.getPort() == listener.getLocalPort(); }
        catch (Exception e) { return false; }
    }
    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int b; (b = input.read()) != -1;) {
            if (b == '\n') return data.toString("ISO-8859-1").replaceFirst("\r$", "");
            if (data.size() >= 32768) throw new IOException("Header too large");
            data.write(b);
        }
        if (data.size() == 0) return null;
        throw new IOException("Incomplete header");
    }
    private static boolean hop(String key) {
        return Arrays.asList("connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade").contains(key.toLowerCase(Locale.ROOT));
    }
    private void serve(Socket socket) {
        HttpsURLConnection remote = null;
        boolean started = false;
        try {
            Socket local = socket;
            local.setSoTimeout(30000);
            InputStream input = new BufferedInputStream(local.getInputStream());
            OutputStream output = new BufferedOutputStream(local.getOutputStream());
            String first = line(input);
            if (first == null) return;
            String[] request = first.split(" ");
            if (request.length != 3 || !request[1].startsWith("/") || request[1].startsWith("//")) throw new IOException("Invalid request");
            Map<String,String> headers = new HashMap<>();
            for (int count = 0;; count++) {
                String header = line(input);
                if (header == null) throw new IOException("Incomplete request");
                if (header.isEmpty()) break;
                if (count > 100) throw new IOException("Too many headers");
                int colon = header.indexOf(':');
                if (colon < 1) throw new IOException("Invalid header");
                String name = header.substring(0, colon).toLowerCase(Locale.ROOT);
                if (headers.containsKey(name)) throw new IOException("Duplicate header");
                headers.put(name, header.substring(colon + 1).trim());
            }
            String cookie = headers.getOrDefault("cookie", "");
            boolean permitted = Arrays.stream(cookie.split(";\\s*")).anyMatch(value -> value.equals("lh_android=" + secret));
            String host = "127.0.0.1:" + listener.getLocalPort();
            String originHeader = headers.get("origin");
            if (!permitted || !host.equals(headers.get("host")) || (originHeader != null && !originHeader.equals(origin))) {
                writeError(output, 403, "Forbidden"); return;
            }
            if (headers.containsKey("transfer-encoding")) { writeError(output, 411, "Content-Length required"); return; }
            long length = Long.parseLong(headers.getOrDefault("content-length", "0"));
            if (length < 0 || length > 26 * 1024 * 1024) { writeError(output, 413, "Request exceeds 26 MiB"); return; }
            remote = pairing.open(request[1]); connections.add(remote);
            remote.setRequestMethod(request[0]);
            for (Map.Entry<String,String> header : headers.entrySet()) {
                String name = header.getKey();
                if (!hop(name) && !Arrays.asList("host", "cookie", "authorization", "content-length", "accept-encoding").contains(name)) remote.setRequestProperty(name, header.getValue());
            }
            if (length > 0) {
                remote.setDoOutput(true); remote.setFixedLengthStreamingMode(length);
                try (OutputStream body = remote.getOutputStream()) { copyExactly(input, body, length); }
            }
            int status = remote.getResponseCode();
            StringBuilder response = new StringBuilder("HTTP/1.1 " + status + " " + reason(status) + "\r\nConnection: close\r\n");
            for (Map.Entry<String,List<String>> header : remote.getHeaderFields().entrySet()) {
                String name = header.getKey();
                if (name == null || hop(name) || name.equalsIgnoreCase("set-cookie") || name.equalsIgnoreCase("content-length")) continue;
                for (String value : header.getValue()) {
                    if (name.equalsIgnoreCase("location") && value.startsWith(pairing.url + "/")) value = origin + value.substring(pairing.url.length());
                    response.append(name).append(": ").append(value).append("\r\n");
                }
            }
            response.append("\r\n"); output.write(response.toString().getBytes(StandardCharsets.ISO_8859_1)); output.flush(); started = true;
            if (!request[0].equals("HEAD") && status != 204 && status != 304) {
                InputStream body = status >= 400 ? remote.getErrorStream() : remote.getInputStream();
                if (body != null) try (InputStream stream = body) {
                    byte[] buffer = new byte[16384];
                    for (int count; (count = stream.read(buffer)) != -1;) { output.write(buffer, 0, count); output.flush(); }
                }
            }
        } catch (Exception e) {
            if (!started) try { writeError(socket.getOutputStream(), 502, "PC unavailable. Check the PC server and Wi-Fi, then tap Retry."); } catch (Exception ignored) {}
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
            if (remote != null) { connections.remove(remote); remote.disconnect(); }
            sockets.remove(socket);
        }
    }
    private static String reason(int status) { return status == 200 ? "OK" : status == 201 ? "Created" : status == 204 ? "No Content" : "Response"; }
    private static void writeError(OutputStream output, int status, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        output.write(("HTTP/1.1 " + status + " Error\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); output.write(body); output.flush();
    }
    private static void copyExactly(InputStream in, OutputStream out, long remaining) throws IOException {
        byte[] buffer = new byte[16384];
        while (remaining > 0) { int count = in.read(buffer, 0, (int)Math.min(buffer.length, remaining)); if (count == -1) throw new EOFException(); out.write(buffer, 0, count); remaining -= count; }
    }
    public void close() {
        closed = true;
        try { listener.close(); } catch (IOException ignored) {}
        for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) {}
        for (HttpsURLConnection connection : connections) connection.disconnect();
        workers.shutdownNow();
    }
}
