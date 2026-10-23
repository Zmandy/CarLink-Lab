package io.github.carlinklab;

import java.io.ByteArrayOutputStream;

/** CarLife takes Annex B NAL units, including SPS/PPS before the first picture. */
final class H264AccessUnit {
    private H264AccessUnit() { }

    static byte[] annexB(byte[] data) {
        if (data == null || data.length == 0) return new byte[0];
        if (data.length >= 3 && data[0] == 0 && data[1] == 0
                && (data[2] == 1 || (data.length >= 4 && data[2] == 0 && data[3] == 1))) return data;
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        int offset = 0;
        while (offset < data.length) {
            if (data.length - offset < 4) throw new IllegalArgumentException("Truncated AVC NAL length");
            long size = ((data[offset] & 255L) << 24) | ((data[offset + 1] & 255L) << 16)
                    | ((data[offset + 2] & 255L) << 8) | (data[offset + 3] & 255L);
            offset += 4;
            if (size <= 0 || size > data.length - offset) throw new IllegalArgumentException("Invalid AVC NAL length");
            out.write(0); out.write(0); out.write(0); out.write(1);
            out.write(data, offset, (int) size);
            offset += (int) size;
        }
        return out.toByteArray();
    }

    static byte[] join(byte[] first, byte[] second) {
        first = annexB(first);
        second = annexB(second);
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
