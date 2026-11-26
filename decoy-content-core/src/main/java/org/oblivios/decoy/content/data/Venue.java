// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.data;

/** A real place that a story may visit, by name and position only: never a number or an address. */
public record Venue(String id, VenueKind kind, String name, double lat, double lon) {
    public enum VenueKind {
        SUPERMARKET, SHOP, CAFE, RESTAURANT, SPORT, SCHOOL, CHILDCARE, DOCTOR, DENTIST, PHARMACY, STATION, FAITH,
        LIBRARY, MUSIC
    }
}
