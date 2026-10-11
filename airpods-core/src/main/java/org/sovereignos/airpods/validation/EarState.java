// SPDX-License-Identifier: Apache-2.0
package org.sovereignos.airpods.validation;

/** Where the two pods are. "Primary" is the pod the AirPods currently use for the microphone. */
public record EarState(Placement primary, Placement secondary) implements AirPodsMessage {
    public enum Placement {
        IN_EAR,
        OUT_OF_EAR,
        IN_CASE
    }
}
