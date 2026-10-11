package io.github.carlinklab;

import java.util.Locale;

/** Standard single-frame OBD responses with or without CAN headers / DLC. */
final class ObdResponseParser {
    static byte[] standard(String raw, int service, int pid) {
        if (raw == null) return null;
        for (String line : raw.toUpperCase(Locale.US).split("[\\r\\n]+")) {
            String hex = line.replace(">", "").trim();
            // Never turn adapter status text into bytes.
            if (!hex.matches("[0-9A-F\\s]+")) continue;
            hex = hex.replaceAll("\\s", "");
            if (hex.matches("7E[89A-F][0-9A-F]+")) hex = hex.substring(3);
            else if (hex.startsWith("18DA") && hex.length() >= 12) hex = hex.substring(8);
            // ATH1/ATD1 adapters may include a one-digit DLC before the PCI.
            if (hex.length() % 2 == 1) hex = hex.substring(1);
            if (hex.length() < 6) continue;
            byte[] bytes = new byte[hex.length() / 2];
            for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(hex.substring(i*2, i*2+2), 16);
            int offset = (bytes[0] & 255) == service ? 0 : 1;
            int length = offset == 0 ? bytes.length : bytes[0] & 255;
            if (offset == 1 && (length < 3 || length > 7 || bytes.length < length + 1)) continue;
            if (length < 3 || (bytes[offset] & 255) != service || (bytes[offset + 1] & 255) != pid) continue;
            byte[] result = new byte[length];
            System.arraycopy(bytes, offset, result, 0, length);
            return result;
        }
        return null;
    }
}
