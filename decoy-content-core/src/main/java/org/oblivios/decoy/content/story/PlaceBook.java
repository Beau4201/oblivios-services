// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.story;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * City centres and capitals. shortcut: ten Dutch cities written by hand (approximate centre coordinates), no
 * other country; the setup replaces this with GeoNames (CC BY 4.0, attribution needed) when it reads the city
 * the owner enters. A country without an entry makes the timeline refuse rather than place the story at a guess.
 */
final class PlaceBook {
    private static final Map<String, double[]> NL = Map.of(
            "amsterdam", new double[] {52.37, 4.90}, "rotterdam", new double[] {51.92, 4.48},
            "utrecht", new double[] {52.09, 5.12}, "den haag", new double[] {52.08, 4.31},
            "eindhoven", new double[] {51.44, 5.47}, "groningen", new double[] {53.22, 6.57},
            "tilburg", new double[] {51.56, 5.09}, "almere", new double[] {52.37, 5.22},
            "breda", new double[] {51.59, 4.78}, "nijmegen", new double[] {51.84, 5.85});

    private PlaceBook() {}

    static boolean hasCountry(String countryCode) {
        return "NL".equalsIgnoreCase(countryCode);
    }

    static Optional<double[]> city(String countryCode, String city) {
        if (!hasCountry(countryCode) || city == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(NL.get(city.trim().toLowerCase(Locale.ROOT)));
    }

    static double[] capital(String countryCode) {
        if (!hasCountry(countryCode)) {
            throw new IllegalArgumentException("no place data for country " + countryCode);
        }
        return NL.get("amsterdam");
    }
}
