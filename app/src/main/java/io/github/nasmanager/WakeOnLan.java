package io.github.nasmanager;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

final class WakeOnLan {
    private WakeOnLan() { }

    static void send(String macAddress, String broadcastAddress) throws Exception {
        byte[] mac = parseMac(macAddress);
        byte[] packet = createPacket(mac);
        InetAddress address = InetAddress.getByName(
                broadcastAddress == null || broadcastAddress.trim().isEmpty()
                        ? "255.255.255.255" : broadcastAddress.trim());
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.send(new DatagramPacket(packet, packet.length, address, 9));
        }
    }

    static byte[] parseMac(String value) {
        if (value == null) throw new IllegalArgumentException("MAC is empty");
        String normalized = value.replace(":", "").replace("-", "").trim();
        if (!normalized.matches("(?i)[0-9a-f]{12}")) {
            throw new IllegalArgumentException("Invalid MAC address");
        }
        byte[] result = new byte[6];
        for (int i = 0; i < 6; i++) {
            result[i] = (byte) Integer.parseInt(normalized.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    static byte[] createPacket(byte[] mac) {
        if (mac == null || mac.length != 6) throw new IllegalArgumentException("Invalid MAC bytes");
        byte[] bytes = new byte[102];
        for (int i = 0; i < 6; i++) bytes[i] = (byte) 0xff;
        for (int i = 6; i < bytes.length; i += mac.length) {
            System.arraycopy(mac, 0, bytes, i, mac.length);
        }
        return bytes;
    }
}
