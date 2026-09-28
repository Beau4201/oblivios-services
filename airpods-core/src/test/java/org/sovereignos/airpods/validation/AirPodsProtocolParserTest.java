package org.sovereignos.airpods.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

final class AirPodsProtocolParserTest {
    private final AirPodsProtocolParser parser = new AirPodsProtocolParser();

    @Test
    void parsesBatteryReport() {
        AirPodsMessage message = parser.parse(new byte[] {0x01, 0x03, 0x00, 80, 75, 60});
        BatteryReport report = assertInstanceOf(BatteryReport.class, message);
        assertEquals(80, report.leftPercent());
        assertEquals(75, report.rightPercent());
        assertEquals(60, report.casePercent());
    }

    @Test
    void parsesBatteryReportWithNotPresentComponent() {
        AirPodsMessage message = parser.parse(new byte[] {0x01, 0x03, 0x00, 80, 75, (byte) 0xFF});
        BatteryReport report = assertInstanceOf(BatteryReport.class, message);
        assertEquals(0xFF, report.casePercent());
    }

    @Test
    void parsesListeningModeState() {
        AirPodsMessage message = parser.parse(new byte[] {0x02, 0x01, 0x00, 2});
        ListeningModeState state = assertInstanceOf(ListeningModeState.class, message);
        assertEquals(ListeningModeState.ListeningMode.TRANSPARENCY, state.mode());
    }

    @Test
    void parsesWearState() {
        AirPodsMessage message = parser.parse(new byte[] {0x03, 0x02, 0x00, 1, 0});
        WearState state = assertInstanceOf(WearState.class, message);
        assertTrue(state.leftWorn());
        assertFalse(state.rightWorn());
    }
}
