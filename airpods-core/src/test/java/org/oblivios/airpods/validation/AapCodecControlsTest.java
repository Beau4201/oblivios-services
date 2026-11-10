// SPDX-License-Identifier: Apache-2.0
package org.oblivios.airpods.validation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The settings of Plan 9 (measured on the owner's pair on 2026-10-04, AIR-180 to AIR-182): a control
 * write is the same frame as the listening mode write, and the pair reports each control in the
 * connection burst as 04 00 04 00 09 00 ID VALUE 00 00 00.
 */
final class AapCodecControlsTest {
    private static byte[] hex(String s) {
        String t = s.replace(" ", "");
        byte[] out = new byte[t.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(t.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    @Test
    void encodesTheFramesThatWereWrittenAndReadBackOnThePair() {
        // The frames of AIR-180 and AIR-182, as sent by aap-write-test.sh.
        assertArrayEquals(hex("04 00 04 00 09 00 17 01 00 00 00"), AapCodec.setControl(AirPodsControl.PRESS_SPEED, 1));
        assertArrayEquals(hex("04 00 04 00 09 00 18 01 00 00 00"), AapCodec.setControl(AirPodsControl.PRESS_HOLD_DURATION, 1));
        assertArrayEquals(hex("04 00 04 00 09 00 23 01 00 00 00"), AapCodec.setControl(AirPodsControl.SWIPE_INTERVAL, 1));
        assertArrayEquals(hex("04 00 04 00 09 00 25 02 00 00 00"), AapCodec.setControl(AirPodsControl.SWIPE_MODE, 2));
        assertArrayEquals(hex("04 00 04 00 09 00 2F 01 00 00 00"), AapCodec.setControl(AirPodsControl.GAIN_SWIPE, 1));
        assertArrayEquals(hex("04 00 04 00 09 00 26 01 00 00 00"), AapCodec.setControl(AirPodsControl.PERSONALIZED_VOLUME, 1));
        assertArrayEquals(hex("04 00 04 00 09 00 35 01 00 00 00"), AapCodec.setControl(AirPodsControl.SLEEP_DETECTION, 1));
        assertArrayEquals(hex("04 00 04 00 09 00 1F 32 00 00 00"), AapCodec.setControl(AirPodsControl.CHIME_VOLUME, 50));
    }

    @Test
    void encodesTheControlsThePairNeverReported() {
        // Values from the third-party notes: [assumption] until the image test (AIR-187).
        assertArrayEquals(hex("04 00 04 00 09 00 01 02 00 00 00"), AapCodec.setControl(AirPodsControl.MICROPHONE, 2));
        assertArrayEquals(hex("04 00 04 00 09 00 20 01 00 00 00"), AapCodec.setControl(AirPodsControl.CONNECT_AUTOMATICALLY, 1));
        assertArrayEquals(hex("04 00 04 00 09 00 31 02 00 00 00"), AapCodec.setControl(AirPodsControl.CASE_TONE, 2));
        assertArrayEquals(hex("04 00 04 00 09 00 40 64 00 00 00"), AapCodec.setControl(AirPodsControl.CASE_TONE_VOLUME, 100));
    }

    @Test
    void refusesAValueOutsideTheRangeOfTheControl() {
        for (AirPodsControl c : AirPodsControl.values()) {
            assertThrows(IllegalArgumentException.class, () -> AapCodec.setControl(c, c.min() - 1), c.name());
            assertThrows(IllegalArgumentException.class, () -> AapCodec.setControl(c, c.max() + 1), c.name());
            AapCodec.setControl(c, c.min());
            AapCodec.setControl(c, c.max());
        }
        assertThrows(IllegalArgumentException.class, () -> AapCodec.setControl(null, 1));
    }

    @Test
    void everyControlHasItsOwnIdentifierAndNeverTheOnesWeAlreadyDecode() {
        Set<Integer> seen = new HashSet<>();
        for (AirPodsControl c : AirPodsControl.values()) {
            assertTrue(seen.add(c.identifier()), "duplicate " + c);
            assertFalse(Set.of(0x0A, 0x0D, 0x1A, 0x24, 0x28, 0x2E, 0x34).contains(c.identifier()), "clash " + c);
            assertEquals(c, AirPodsControl.ofIdentifier(c.identifier()).orElseThrow());
        }
    }

    @Test
    void decodesTheValuesOfTheBurstOfTheOwnersPair() {
        // The full burst of 2026-10-04 (AIR-179).
        AirPodsMessage m = AapCodec.decode(hex("04 00 04 00 09 00 1F 50 50 00 00"));
        assertEquals(new ControlReport(AirPodsControl.CHIME_VOLUME, 0x50), m);
        assertEquals(new ControlReport(AirPodsControl.PERSONALIZED_VOLUME, 2), AapCodec.decode(hex("04 00 04 00 09 00 26 02 00 00 00")));
        assertEquals(new ControlReport(AirPodsControl.SWIPE_MODE, 1), AapCodec.decode(hex("04 00 04 00 09 00 25 01 00 00 00")));
        assertEquals(new ControlReport(AirPodsControl.GAIN_SWIPE, 0), AapCodec.decode(hex("04 00 04 00 09 00 2F 00 00 00 00")));
        assertEquals(new ControlReport(AirPodsControl.SLEEP_DETECTION, 2), AapCodec.decode(hex("04 00 04 00 09 00 35 02 00 00 00")));
    }

    @Test
    void aValueOutsideTheRangeIsUnrecognisedAndNeverAnError() {
        // A malformed frame ends the session after a few in a row; a value we did not expect must not.
        assertInstanceOf(UnrecognisedMessage.class, AapCodec.decode(hex("04 00 04 00 09 00 35 07 00 00 00")));
        assertInstanceOf(UnrecognisedMessage.class, AapCodec.decode(hex("04 00 04 00 09 00 1F 65 50 00 00")));
        assertInstanceOf(UnrecognisedMessage.class, AapCodec.decode(hex("04 00 04 00 09 00 77 01 00 00 00")));
    }

    @Test
    void callManagementKeepsBothDataBytes() {
        assertEquals(new CallManagementReport(0, 3), AapCodec.decode(hex("04 00 04 00 09 00 24 00 03 00 00")));
        assertArrayEquals(hex("04 00 04 00 09 00 24 00 03 00 00"), AapCodec.setCallManagement(0, 3));
        assertThrows(IllegalArgumentException.class, () -> AapCodec.setCallManagement(256, 0));
        assertThrows(IllegalArgumentException.class, () -> AapCodec.setCallManagement(0, -1));
    }

    @Test
    void theControlFramesStillRejectAWrongLength() {
        assertThrows(AirPodsValidationException.class, () -> AapCodec.decode(hex("04 00 04 00 09 00 26 01 00 00")));
    }
}
