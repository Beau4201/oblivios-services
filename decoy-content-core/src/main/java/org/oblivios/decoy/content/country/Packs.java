// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.country;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

/** The registry of packs. A country without a pack, or whose pack has no verified range, gets the neutral pack. */
public final class Packs {
    private static final NeutralPack NEUTRAL = new NeutralPack();
    private static final List<CountryPack> REGISTRY = List.of(new DutchPack());

    private Packs() {}

    public static CountryPack neutral() {
        return NEUTRAL;
    }

    public static PackChoice forDevice(String simCountry, ZoneId zone, Locale locale) {
        return forDevice(REGISTRY, simCountry, zone, locale);
    }

    // shortcut: the zone is not used to pick a pack, only the SIM country and then the locale's country.
    static PackChoice forDevice(List<CountryPack> registry, String simCountry, ZoneId zone, Locale locale) {
        String country = simCountry != null && !simCountry.isBlank() ? simCountry : locale.getCountry();
        for (CountryPack p : registry) {
            if (p.code().equalsIgnoreCase(country)) {
                try {
                    p.mobileNumber(new SplittableRandom(0));
                    return new PackChoice(p, false);
                } catch (NoVerifiedRange e) {
                    return new PackChoice(NEUTRAL, true);
                }
            }
        }
        return new PackChoice(NEUTRAL, true);
    }
}
