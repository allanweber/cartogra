package io.cartogra.common.event;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

public final class UuidV5 {
    private static final UUID NS = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
    private UuidV5() {}

    public static UUID fromNames(String... names) {
        return fromNs(NS, String.join(":", names));
    }

    private static UUID fromNs(UUID ns, String name) {
        byte[] nsb = toBytes(ns), nb = name.getBytes(StandardCharsets.UTF_8);
        byte[] buf = new byte[nsb.length + nb.length];
        System.arraycopy(nsb, 0, buf, 0, nsb.length);
        System.arraycopy(nb, 0, buf, nsb.length, nb.length);
        byte[] h;
        try { h = MessageDigest.getInstance("SHA-1").digest(buf); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        h[6] = (byte) ((h[6] & 0x0f) | 0x50);
        h[8] = (byte) ((h[8] & 0x3f) | 0x80);
        long msb = 0, lsb = 0;
        for (int i = 0; i < 8; i++) msb = (msb << 8) | (h[i] & 0xff);
        for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (h[i] & 0xff);
        return new UUID(msb, lsb);
    }

    private static byte[] toBytes(UUID u) {
        long msb = u.getMostSignificantBits(), lsb = u.getLeastSignificantBits();
        byte[] b = new byte[16];
        for (int i = 7; i >= 0; i--) { b[i] = (byte)(msb & 0xff); msb >>= 8; }
        for (int i = 15; i >= 8; i--) { b[i] = (byte)(lsb & 0xff); lsb >>= 8; }
        return b;
    }
}