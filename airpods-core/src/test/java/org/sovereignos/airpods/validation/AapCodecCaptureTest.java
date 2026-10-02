package org.sovereignos.airpods.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.sovereignos.airpods.contracts.AirPodsComponentId;
import org.sovereignos.airpods.validation.BatteryStatus.Charge;
import org.sovereignos.airpods.validation.ListeningModeState.ListeningMode;

/**
 * Frames captured from the owner's AirPods Pro with the debug probe on 2026-10-02 (AIR-121). Only
 * frame kinds that carry no serial number or address are kept; the device information frame is never
 * captured. Order of the components in a battery frame is the device's own (right, left, case).
 */
final class AapCodecCaptureTest {
    private static byte[] hex(String s) {
        String t = s.replace(" ", "");
        byte[] out = new byte[t.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(t.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    @Test
    void batteryWithBothPodsInTheCase() {
        BatteryStatus s = (BatteryStatus) AapCodec.decode(hex("04 00 04 00 04 00 03 02 01 64 01 01 04 01 64 01 01 08 01 20 02 01"));
        assertEquals(
                List.of(
                        new BatteryStatus.Entry(AirPodsComponentId.RIGHT_EARBUD, 100, Charge.CHARGING),
                        new BatteryStatus.Entry(AirPodsComponentId.LEFT_EARBUD, 100, Charge.CHARGING),
                        new BatteryStatus.Entry(AirPodsComponentId.CASE, 32, Charge.DISCHARGING)),
                s.entries());
    }

    @Test
    void batteryWithBothPodsInTheEarsAndTheCaseOutOfReach() {
        BatteryStatus s = (BatteryStatus) AapCodec.decode(hex("04 00 04 00 04 00 03 02 01 64 02 01 04 01 64 02 01 08 01 00 04 01"));
        assertEquals(
                List.of(
                        new BatteryStatus.Entry(AirPodsComponentId.RIGHT_EARBUD, 100, Charge.DISCHARGING),
                        new BatteryStatus.Entry(AirPodsComponentId.LEFT_EARBUD, 100, Charge.DISCHARGING),
                        new BatteryStatus.Entry(AirPodsComponentId.CASE, -1, Charge.DISCONNECTED)),
                s.entries());
    }

    @Test
    void earState() {
        assertEquals(new EarState(EarState.Placement.IN_CASE, EarState.Placement.IN_CASE), AapCodec.decode(hex("04 00 04 00 06 00 02 02")));
        assertEquals(new EarState(EarState.Placement.IN_EAR, EarState.Placement.IN_EAR), AapCodec.decode(hex("04 00 04 00 06 00 00 00")));
    }

    @Test
    void listeningModeAndTheDevicesAnswerToAnOffRequest() {
        assertEquals(new ListeningModeState(ListeningMode.TRANSPARENCY), AapCodec.decode(hex("04 00 04 00 09 00 0D 03 00 00 00")));
        // Before "allow off" was enabled the pair refused "off" (request 04 00 04 00 09 00 0D 01 ...)
        // and answered with noise cancel; after 04 00 04 00 09 00 34 01 00 00 00 it answered off.
        assertEquals(new ListeningModeState(ListeningMode.ANC), AapCodec.decode(hex("04 00 04 00 09 00 0D 02 00 00 00")));
        assertEquals(new ListeningModeState(ListeningMode.OFF), AapCodec.decode(hex("04 00 04 00 09 00 0D 01 00 00 00")));
    }

    @Test
    void conversationAwarenessSetting() {
        assertEquals(new ConversationAwarenessSetting(false), AapCodec.decode(hex("04 00 04 00 09 00 28 02 00 00 00")));
        assertEquals(new ConversationAwarenessSetting(true), AapCodec.decode(hex("04 00 04 00 09 00 28 01 00 00 00")));
    }

    @Test
    void theHandshakeAnswerAndTheOtherControlSettingsAreReportedNotActedOn() {
        assertEquals(new UnrecognisedMessage(-1, -1), AapCodec.decode(hex("01 00 04 00 00 00 01 00 03 00 00 00 00 00 00 00 00 00")));
        for (int id : new int[] {0x17, 0x18, 0x1B, 0x1F, 0x23, 0x24, 0x25, 0x26, 0x29, 0x2C, 0x2E, 0x2F, 0x33, 0x35, 0x3E}) {
            byte[] f = hex("04 00 04 00 09 00 00 00 00 00 00");
            f[6] = (byte) id;
            assertEquals(new UnrecognisedMessage(9, id), AapCodec.decode(f));
        }
    }
}
