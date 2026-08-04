package io.github.nasmanager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;

/** Minimal RFC 6455 client for the TrueNAS JSON-RPC API; no external dependency required. */
final class TrueNasWebSocketClient implements AutoCloseable {
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_MESSAGE = 8 * 1024 * 1024;
    private final SecureRandom random = new SecureRandom();
    private Socket socket;
    private InputStream input;
    private OutputStream output;
    private long nextId = 1;
    private final Map<String, ArrayDeque<Object>> eventBacklog = new HashMap<>();

    /** The modern /api/current endpoint is absent on older TrueNAS versions. */
    static final class ApiUnavailableException extends IOException {
        ApiUnavailableException(String message) { super(message); }
    }

    /** A server-side JSON-RPC method/auth/job failure (as distinct from transport failure). */
    static final class JsonRpcException extends IOException {
        final int code;
        final String errorName;
        final String reason;

        JsonRpcException(String method, int code, String errorName, String reason) {
            super(formatMessage(method, code, errorName, reason));
            this.code = code;
            this.errorName = errorName;
            this.reason = reason;
        }

        private static String formatMessage(String method, int code, String errorName, String reason) {
            StringBuilder message = new StringBuilder(method).append(": ");
            if (reason != null && !reason.trim().isEmpty()) message.append(reason.trim());
            else message.append("JSON-RPC error ").append(code);
            if (errorName != null && !errorName.trim().isEmpty()) {
                message.append(" (").append(errorName.trim()).append(')');
            }
            return message.toString();
        }
    }

    TrueNasWebSocketClient(AppConfig config) throws Exception {
        URI server = config.requireServerUri();
        boolean secure = "https".equalsIgnoreCase(server.getScheme());
        int port = server.getPort() > 0 ? server.getPort() : secure ? 443 : 80;
        URI uri = new URI(secure ? "wss" : "ws", null, server.getHost(), port,
                "/api/current", null, null);
        try {
            connect(uri);
            login(config);
            // Explicitly select the JSON-RPC 2.0 job behavior documented for /api/current. With
            // legacy_jobs disabled, the original call completes with the job's final result/error.
            call("core.set_options", new JSONArray().put(new JSONObject().put("legacy_jobs", false)));
        } catch (Exception error) {
            close();
            throw error;
        }
    }

    Object call(String method, JSONArray params) throws Exception {
        long id = nextId++;
        JSONObject request = new JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", method)
                .put("params", params == null ? new JSONArray() : params);
        sendFrame(1, request.toString().getBytes(StandardCharsets.UTF_8));
        while (true) {
            JSONObject response = new JSONObject(readTextMessage());
            if (!response.has("id") || response.optLong("id", -1) != id) {
                enqueueEvent(response);
                continue;
            }
            JSONObject error = response.optJSONObject("error");
            if (error != null) {
                JSONObject data = error.optJSONObject("data");
                String reason = data == null ? error.optString("message", "")
                        : data.optString("reason", error.optString("message", ""));
                String errorName = data == null ? "" : data.optString("errname", "");
                throw new JsonRpcException(method, error.optInt("code", 0), errorName, reason);
            }
            return response.opt("result");
        }
    }

    /** Job methods return their final result/error on the original JSON-RPC call. */
    Object callJob(String method, JSONArray params) throws Exception {
        int previousTimeout = socket.getSoTimeout();
        socket.setSoTimeout(10 * 60 * 1000);
        try {
            return call(method, params);
        } finally {
            try { socket.setSoTimeout(previousTimeout); } catch (IOException ignored) { }
        }
    }

    JSONObject nextEvent(String collection) throws Exception {
        Object fields = nextEventFields(collection);
        if (fields instanceof JSONObject) return (JSONObject) fields;
        return new JSONObject().put("fields", fields == null ? JSONObject.NULL : fields);
    }

    /** Returns event fields without assuming they are an object (app.stats fields are an array). */
    Object nextEventFields(String collection) throws Exception {
        Object pending = pollEvent(collection);
        if (pending != null) return pending;
        while (true) {
            JSONObject message = new JSONObject(readTextMessage());
            enqueueEvent(message);
            pending = pollEvent(collection);
            if (pending != null) return pending;
        }
    }

    private void enqueueEvent(JSONObject message) {
        String method = message.optString("method", "");
        JSONObject params = message.optJSONObject("params");
        if (params == null) return;
        String collection = params.optString("collection", method);
        if (collection.isEmpty()) return;
        int dynamicArgs = collection.indexOf(':');
        String key = dynamicArgs < 0 ? collection : collection.substring(0, dynamicArgs);
        Object fields = params.has("fields") ? params.opt("fields") : params;
        ArrayDeque<Object> queue = eventBacklog.get(key);
        if (queue == null) {
            queue = new ArrayDeque<>();
            eventBacklog.put(key, queue);
        }
        // A bounded backlog prevents a slow consumer from retaining an unbounded stats history.
        if (queue.size() >= 4) queue.removeFirst();
        queue.addLast(fields == null ? JSONObject.NULL : fields);
    }

    private Object pollEvent(String collection) {
        ArrayDeque<Object> queue = eventBacklog.get(collection);
        return queue == null || queue.isEmpty() ? null : queue.removeFirst();
    }

    private void login(AppConfig config) throws Exception {
        boolean passwordLogin = config.usesPasswordAuthentication();
        JSONObject loginData = new JSONObject()
                .put("mechanism", config.authenticationMechanism())
                .put("username", config.username.trim())
                // Never put an API key on an insecure WebSocket. TrueNAS revokes keys used over
                // HTTP; the supported LAN alternative is username/password authentication.
                .put(config.authenticationSecretField(),
                        passwordLogin ? config.password : config.apiKey)
                // v25.10 rejects unknown login_options properties. reconnect_token is a
                // response field in older docs, not a supported input option here.
                .put("login_options", new JSONObject().put("user_info", false));
        Object result = call("auth.login_ex", new JSONArray().put(loginData));
        JSONObject object = result instanceof JSONObject ? (JSONObject) result : null;
        if (object == null || !"SUCCESS".equalsIgnoreCase(object.optString("response_type"))) {
            String responseType = object == null ? "invalid response" : object.optString("response_type", "unknown response");
            String credential = passwordLogin ? "username or password" : "username or API key";
            if ("AUTH_ERR".equalsIgnoreCase(responseType)) {
                throw new IOException("TrueNAS authentication failed: invalid " + credential + ".");
            }
            if ("EXPIRED".equalsIgnoreCase(responseType)) {
                throw new IOException("TrueNAS authentication failed: the supplied credential has expired.");
            }
            if ("OTP_REQUIRED".equalsIgnoreCase(responseType)) {
                throw new IOException("TrueNAS authentication requires a one-time password, which is not supported by this connection mode.");
            }
            if ("REDIRECT".equalsIgnoreCase(responseType)) {
                JSONArray urls = object.optJSONArray("urls");
                String destination = urls == null || urls.length() == 0 ? "another server" : urls.optString(0, "another server");
                throw new IOException("TrueNAS authentication must be completed on " + destination + ".");
            }
            throw new IOException("TrueNAS authentication failed (" + credential + "): " + responseType);
        }
    }

    private void connect(URI uri) throws Exception {
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 7000);
        plain.setSoTimeout(15000);
        if ("wss".equalsIgnoreCase(uri.getScheme())) {
            try {
                // Use Android's application-aware default TLS factory so its Network Security
                // Config trust anchors are honored. Hostname verification stays enabled.
                SSLSocket ssl = (SSLSocket) HttpsURLConnection.getDefaultSSLSocketFactory()
                        .createSocket(plain, uri.getHost(), uri.getPort(), true);
                ssl.setUseClientMode(true);
                SSLParameters parameters = ssl.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                ssl.setSSLParameters(parameters);
                ssl.startHandshake();
                socket = ssl;
            } catch (SSLHandshakeException error) {
                try { plain.close(); } catch (Exception ignored) { }
                throw new IOException("TLS certificate validation failed. Use a certificate trusted by Android and a server name present in that certificate.", error);
            }
        } else {
            socket = plain;
        }
        input = new BufferedInputStream(socket.getInputStream());
        output = new BufferedOutputStream(socket.getOutputStream());
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String key = Base64.getEncoder().encodeToString(nonce);
        String host = uri.getPort() == 80 || uri.getPort() == 443
                ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
        String handshake = "GET " + uri.getRawPath() + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        output.write(handshake.getBytes(StandardCharsets.US_ASCII));
        output.flush();
        String headers = readHttpHeaders();
        String lower = headers.toLowerCase(Locale.US);
        if (!headers.startsWith("HTTP/1.1 101") && !headers.startsWith("HTTP/1.0 101")) {
            String statusLine = headers.split("\r\n", 2)[0];
            try { socket.close(); } catch (Exception ignored) { }
            if (statusLine.matches("HTTP/1\\.[01] (404|410)( |$).*$")) {
                throw new ApiUnavailableException("TrueNAS JSON-RPC endpoint is unavailable: " + statusLine);
            }
            throw new IOException("WebSocket upgrade failed: " + statusLine);
        }
        String expected = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).getBytes(StandardCharsets.US_ASCII)));
        if (!lower.contains("sec-websocket-accept: " + expected.toLowerCase(Locale.US))) {
            throw new IOException("Invalid WebSocket handshake");
        }
    }

    private String readHttpHeaders() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int state = 0;
        while (bytes.size() < 32768) {
            int value = input.read();
            if (value < 0) throw new EOFException("Connection closed during handshake");
            bytes.write(value);
            state = (state == 0 && value == '\r') ? 1
                    : (state == 1 && value == '\n') ? 2
                    : (state == 2 && value == '\r') ? 3
                    : (state == 3 && value == '\n') ? 4 : 0;
            if (state == 4) return bytes.toString(StandardCharsets.US_ASCII.name());
        }
        throw new IOException("WebSocket headers too large");
    }

    private String readTextMessage() throws IOException {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        boolean started = false;
        while (message.size() <= MAX_MESSAGE) {
            int first = readByte();
            int second = readByte();
            boolean fin = (first & 0x80) != 0;
            int opcode = first & 0x0f;
            boolean masked = (second & 0x80) != 0;
            long length = second & 0x7f;
            if (length == 126) length = ((long) readByte() << 8) | readByte();
            else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) length = (length << 8) | readByte();
            }
            if (length > MAX_MESSAGE || message.size() + length > MAX_MESSAGE) throw new IOException("WebSocket message too large");
            byte[] mask = masked ? readExact(4) : null;
            byte[] payload = readExact((int) length);
            if (masked) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
            if (opcode == 8) throw new EOFException("WebSocket closed by server");
            if (opcode == 9) { sendFrame(10, payload); continue; }
            if (opcode == 1) started = true;
            if (opcode == 1 || opcode == 0) message.write(payload, 0, payload.length);
            if (started && fin) return message.toString(StandardCharsets.UTF_8.name());
        }
        throw new IOException("WebSocket message too large");
    }

    private synchronized void sendFrame(int opcode, byte[] payload) throws IOException {
        output.write(0x80 | opcode);
        if (payload.length < 126) output.write(0x80 | payload.length);
        else if (payload.length <= 0xffff) {
            output.write(0x80 | 126);
            output.write((payload.length >>> 8) & 0xff);
            output.write(payload.length & 0xff);
        } else {
            output.write(0x80 | 127);
            long longLength = payload.length;
            for (int shift = 56; shift >= 0; shift -= 8) output.write((int) ((longLength >>> shift) & 0xff));
        }
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        output.write(mask);
        for (int i = 0; i < payload.length; i++) output.write(payload[i] ^ mask[i % 4]);
        output.flush();
    }

    private int readByte() throws IOException {
        int value = input.read();
        if (value < 0) throw new EOFException("WebSocket closed");
        return value;
    }

    private byte[] readExact(int length) throws IOException {
        byte[] result = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(result, offset, length - offset);
            if (count < 0) throw new EOFException("WebSocket closed");
            offset += count;
        }
        return result;
    }

    @Override
    public void close() {
        try { if (output != null) sendFrame(8, new byte[0]); } catch (Exception ignored) { }
        try { if (socket != null) socket.close(); } catch (Exception ignored) { }
    }
}
