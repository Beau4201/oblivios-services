// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.country;

/** A number in E.164 form, of the kind it was drawn as. */
public record PhoneNumber(String e164, NumberType type) {
    public enum NumberType { MOBILE, FIXED }
}
