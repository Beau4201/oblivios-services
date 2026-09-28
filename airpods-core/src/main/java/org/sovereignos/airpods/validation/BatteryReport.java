package org.sovereignos.airpods.validation;

public record BatteryReport(int leftPercent, int rightPercent, int casePercent) implements AirPodsMessage {
}
