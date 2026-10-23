package io.github.carlinklab;

import org.junit.Test;
import static org.junit.Assert.*;

public final class H264AccessUnitTest {
    @Test public void preservesBothAnnexBStartCodes() {
        byte[] three = {0, 0, 1, 0x65, 42};
        byte[] four = {0, 0, 0, 1, 0x67, 12};
        assertArrayEquals(three, H264AccessUnit.annexB(three));
        assertArrayEquals(four, H264AccessUnit.annexB(four));
    }

    @Test public void convertsMultipleLengthPrefixedNals() {
        byte[] avc = {0, 0, 0, 2, 0x67, 11, 0, 0, 0, 3, 0x68, 22, 33};
        byte[] expected = {0, 0, 0, 1, 0x67, 11, 0, 0, 0, 1, 0x68, 22, 33};
        assertArrayEquals(expected, H264AccessUnit.annexB(avc));
    }

    @Test public void joinsParameterSetsAndPicture() {
        byte[] config = {0, 0, 0, 1, 0x67, 11, 0, 0, 0, 1, 0x68, 22};
        byte[] picture = {0, 0, 0, 2, 0x65, 33};
        assertArrayEquals(new byte[]{0, 0, 0, 1, 0x67, 11, 0, 0, 0, 1, 0x68, 22,
                0, 0, 0, 1, 0x65, 33}, H264AccessUnit.join(config, picture));
    }

    @Test public void acceptsMissingOptionalParameterBuffer() {
        byte[] sps = {0, 0, 0, 1, 0x67, 11};
        assertArrayEquals(sps, H264AccessUnit.join(sps, null));
        assertArrayEquals(sps, H264AccessUnit.join(null, sps));
        assertArrayEquals(new byte[0], H264AccessUnit.join(null, new byte[0]));
    }

    @Test public void rejectsMalformedLengthPrefixes() {
        for (byte[] malformed : new byte[][]{
                {0, 0}, {0, 0, 0, 0}, {0, 0, 0, 8, 0x65},
                {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff},
                {0, 0, 0, 2, 0x65, 11, 0}}) {
            try {
                H264AccessUnit.annexB(malformed);
                fail("Malformed AVC must not be forwarded to the head unit");
            } catch (IllegalArgumentException expected) { }
        }
    }
}
