package org.sovereignos.airpods.validation;

public sealed interface AirPodsMessage permits BatteryReport, ListeningModeState, WearState {
}
