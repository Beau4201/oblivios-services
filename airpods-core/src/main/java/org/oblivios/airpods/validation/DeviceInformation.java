// SPDX-License-Identifier: Apache-2.0
package org.oblivios.airpods.validation;

import java.util.regex.Pattern;

/**
 * The model number and the firmware version out of the device information frame (opcode 0x001D),
 * the only two fields of that frame the app keeps. The frame also carries the owner's chosen name and
 * serial numbers; {@link AapCodec} skips them and they never end up here.
 */
public record DeviceInformation(String modelNumber, String firmware) implements AirPodsMessage {
    /** Apple model numbers of the AirPods: the letter A and four digits. */
    static final Pattern MODEL = Pattern.compile("A[0-9]{4}");

    /** Version strings seen on the owner's pair (for example 90.3431000025000000.6807). */
    static final Pattern FIRMWARE = Pattern.compile("[0-9A-Za-z._-]{1,32}");

    public DeviceInformation {
        if (modelNumber == null || !MODEL.matcher(modelNumber).matches()) {
            throw new AirPodsValidationException("model number");
        }
        if (firmware == null || !FIRMWARE.matcher(firmware).matches()) {
            throw new AirPodsValidationException("firmware");
        }
    }
}
