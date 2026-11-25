// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.country;

/** The pack for a device; {@code fellBack} is true when the neutral pack was taken, so the setup can say so. */
public record PackChoice(CountryPack pack, boolean fellBack) {}
