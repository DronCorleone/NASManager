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
import java.util.Base64;
import java.util.Locale;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Minimal RFC 6455 client for the TrueNAS JSON-RPC API; no external dependency required. */
final class TrueNasWebSocketClient implements AutoCloseable {
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_MESSAGE = 8 * 1024 * 1024;
    private final SecureRandom random = new SecureRandom();
    private Socket socket;
    private InputStream input;
    private OutputStream output;
    private long nextId = 1;

    TrueNasWebSocketClient(AppConfig config) throws Exception {
        URI server = URI.create(config.normalizedUrl());
        boolean secure = "https".equalsIgnoreCase(server.getScheme());
        String scheme = secure ? "wss" : "ws";
        int port = server.getPort() > 0 ? server.getPort() : (secure ? 443 : 80);
        URI uri = new URI(scheme, null, server.getHost(), port, "/api/current", null, null);
        connect(uri, secure);
        login(config);
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
            if (!response.has("id") || response.optLong("id", -1) != id) continue;
            JSONObject error = response.optJSONObject("error");
            if (error != null) {
                JSONObject data = error.optJSONObject("data");
                String reason = data == null ? error.optString("message", "JSON-RPC error")
                        : data.optString("reason", error.optString("message", "JSON-RPC error"));
                throw new IOException(reason);
            }
            return response.opt("result");
        }
    }

    JSONObject nextEvent(String collection) throws Exception {
        while (true) {
            JSONObject message = new JSONObject(readTextMessage());
            String method = message.optString("method", "");
            JSONObject params = message.optJSONObject("params");
            if (params == null) continue;
            String messageCollection = params.optString("collection", method);
            if (!collection.equals(messageCollection) && !messageCollection.startsWith(collection + ":")) continue;
            JSONObject fields = params.optJSONObject("fields");
            return fields == null ? params : fields;
        }
    }

    private void login(AppConfig config) throws Exception {
        if (config.username != null && !config.username.trim().isEmpty()) {
            JSONObject loginData = new JSONObject()
                    .put("mechanism", "API_KEY_PLAIN")
                    .put("username", config.username.trim())
                    .put("api_key", config.apiKey)
                    .put("login_options", new JSONObject().put("user_info", false).put("reconnect_token", false));
            Object result = call("auth.login_ex", new JSONArray().put(loginData));
            JSONObject object = result instanceof JSONObject ? (JSONObject) result : null;
            if (object == null || !"SUCCESS".equalsIgnoreCase(object.optString("response_type"))) {
                throw new IOException("TrueNAS authentication failed");
            }
        } else {
            Object result = call("auth.login_with_api_key", new JSONArray().put(config.apiKey));
            if (!(result instanceof Boolean) || !((Boolean) result)) {
                throw new IOException("TrueNAS authentication failed");
            }
        }
    }

    private void connect(URI uri, boolean secure) throws Exception {
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 7000);
        plain.setSoTimeout(15000);
        if (secure) {
            SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(plain, uri.getHost(), uri.getPort(), true);
            SSLParameters parameters = ssl.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            ssl.setSSLParameters(parameters);
            ssl.startHandshake();
            socket = ssl;
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
            throw new IOException("WebSocket upgrade failed: " + headers.split("\r\n", 2)[0]);
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
