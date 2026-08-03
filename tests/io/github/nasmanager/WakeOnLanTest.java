package io.github.nasmanager;

public final class WakeOnLanTest {
    public static void main(String[] args) {
        byte[] mac = WakeOnLan.parseMac("AA:bb:CC-dd:EE-ff");
        assert mac.length == 6;
        assert (mac[0] & 0xff) == 0xaa;
        assert (mac[5] & 0xff) == 0xff;

        byte[] packet = WakeOnLan.createPacket(mac);
        assert packet.length == 102;
        for (int i = 0; i < 6; i++) assert (packet[i] & 0xff) == 0xff;
        for (int offset = 6; offset < packet.length; offset += 6) {
            for (int i = 0; i < 6; i++) assert packet[offset + i] == mac[i];
        }

        boolean rejected = false;
        try { WakeOnLan.parseMac("not-a-mac"); } catch (IllegalArgumentException expected) { rejected = true; }
        assert rejected;
        System.out.println("WakeOnLanTest: all checks passed");
    }
}
