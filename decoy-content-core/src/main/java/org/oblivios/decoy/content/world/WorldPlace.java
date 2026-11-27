// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.world;

import java.util.Objects;
import org.oblivios.decoy.content.origin.Origin;
import org.oblivios.decoy.content.story.PlaceKind;

/**
 * A place of the persona's life: a point where people live, or a venue of the place data, whose id it then keeps. No
 * address and no number of a business (ADR-334); the name of a point where people live is empty.
 */
public record WorldPlace(String id, PlaceKind kind, String name, double lat, double lon, String municipality, Origin origin) {
    public WorldPlace {
        Objects.requireNonNull(origin, "origin");
    }
}
