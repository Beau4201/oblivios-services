// SPDX-License-Identifier: Apache-2.0
package org.oblivios.airpods.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.oblivios.airpods.validation.ListeningModeState.ListeningMode;

/**
 * Frames captured from the owner's AirPods Pro with the debug probe on 2026-10-02 while speaking with
 * music playing (conversation awareness on), and the adaptive mode exchange. No frame here carries a
 * serial number or an address.
 */
final class AapCodecConversationTest {
    private static byte[] hex(String s) {
        String t = s.replace(" ", "");
        byte[] out = new byte[t.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(t.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    @Test
    void levelsSeenWhileTheOwnerSpoke() {
        for (int level : new int[] {0x01, 0x02, 0x03, 0x05, 0x0B, 0x04, 0x08, 0x09}) {
            assertEquals(
                    new ConversationAwarenessLevel(level),
                    AapCodec.decode(hex(String.format("04 00 04 00 4B 00 02 00 01 %02X", level))));
        }
    }

    @Test
    void everyByteValueIsCarriedAsIs() {
        assertEquals(new ConversationAwarenessLevel(0), AapCodec.decode(hex("04 00 04 00 4B 00 02 00 01 00")));
        assertEquals(new ConversationAwarenessLevel(255), AapCodec.decode(hex("04 00 04 00 4B 00 02 00 01 FF")));
    }

    @Test
    void aFrameOfAnotherShapeIsRejected() {
        assertThrows(AirPodsValidationException.class, () -> AapCodec.decode(hex("04 00 04 00 4B 00 02 00 01")));
        assertThrows(AirPodsValidationException.class, () -> AapCodec.decode(hex("04 00 04 00 4B 00 02 00 01 09 00")));
        assertThrows(AirPodsValidationException.class, () -> AapCodec.decode(hex("04 00 04 00 4B 00 03 00 01 09")));
        assertThrows(AirPodsValidationException.class, () -> AapCodec.decode(hex("04 00 04 00 4B 00 02 01 01 09")));
        assertThrows(AirPodsValidationException.class, () -> AapCodec.decode(hex("04 00 04 00 4B 00 02 00 00 09")));
    }

    @Test
    void adaptiveModeIsAcceptedAndReportedBack() {
        assertEquals(new ListeningModeState(ListeningMode.ADAPTIVE), AapCodec.decode(hex("04 00 04 00 09 00 0D 04 00 00 00")));
        assertEquals("04 00 04 00 09 00 0D 04 00 00 00", java.util.HexFormat.ofDelimiter(" ").withUpperCase()
                .formatHex(AapCodec.setListeningMode(ListeningMode.ADAPTIVE)));
    }

    @Test
    void adaptiveStrength() {
        assertEquals(new AdaptiveLevelSetting(50), AapCodec.decode(hex("04 00 04 00 09 00 2E 32 00 00 00")));
        assertEquals(new AdaptiveLevelSetting(0), AapCodec.decode(hex("04 00 04 00 09 00 2E 00 00 00 00")));
        assertEquals(new AdaptiveLevelSetting(100), AapCodec.decode(hex("04 00 04 00 09 00 2E 64 00 00 00")));
        assertThrows(AirPodsValidationException.class, () -> AapCodec.decode(hex("04 00 04 00 09 00 2E 65 00 00 00")));
        assertThrows(AirPodsValidationException.class, () -> AapCodec.decode(hex("04 00 04 00 09 00 2E FF 00 00 00")));
    }
}
