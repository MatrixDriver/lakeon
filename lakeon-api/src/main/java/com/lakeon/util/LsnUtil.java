package com.lakeon.util;

public final class LsnUtil {
    private LsnUtil() {}

    /** Parse Neon LSN "segment/offset" hex string to long. E.g., "0/1A2B3C0" -> 27542464 */
    public static long parse(String lsn) {
        if (lsn == null || lsn.isBlank()) throw new IllegalArgumentException("LSN is blank");
        String[] parts = lsn.split("/");
        if (parts.length != 2) throw new IllegalArgumentException("Invalid LSN format: " + lsn);
        long segment = Long.parseUnsignedLong(parts[0], 16);
        long offset = Long.parseUnsignedLong(parts[1], 16);
        return (segment << 32) | offset;
    }

    /** Convert long back to Neon LSN hex string. E.g., 27542464 -> "0/1A2B3C0" */
    public static String format(long lsn) {
        long segment = (lsn >>> 32) & 0xFFFFFFFFL;
        long offset = lsn & 0xFFFFFFFFL;
        return String.format("%X/%X", segment, offset);
    }
}
