package org.sovereignos.airpods.validation;

public record ListeningModeState(ListeningMode mode) implements AirPodsMessage {
    public enum ListeningMode {
        OFF,
        ANC,
        TRANSPARENCY,
        ADAPTIVE
    }
}
