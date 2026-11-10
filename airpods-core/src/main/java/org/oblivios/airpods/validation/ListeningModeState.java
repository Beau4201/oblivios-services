// SPDX-License-Identifier: Apache-2.0
package org.oblivios.airpods.validation;

public record ListeningModeState(ListeningMode mode) implements AirPodsMessage {
    public enum ListeningMode {
        OFF,
        ANC,
        TRANSPARENCY,
        ADAPTIVE
    }
}
