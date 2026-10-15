// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.airpods.validation;

/** The "automatic ear detection" setting (control identifier 0x0A), not the sensor state. */
public record EarDetectionSetting(boolean enabled) implements AirPodsMessage {
}
