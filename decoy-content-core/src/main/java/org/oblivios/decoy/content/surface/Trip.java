// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import org.oblivios.decoy.content.origin.Origin;
import org.oblivios.decoy.content.persona.PersonaProfile.TravelMode;
import org.oblivios.decoy.content.world.WorldPlace;

/**
 * A trip of the chronicle, from leaving home to coming back, so photos and calendar entries of those days can sit at
 * its places. {@code id} is the id of its event; {@code zone} is the destination's.
 */
public record Trip(String id, List<WorldPlace> places, TravelMode mode, Instant from, Instant to, ZoneId zone, Origin origin) {
    public Trip {
        Objects.requireNonNull(origin, "origin");
        places = List.copyOf(places);
    }
}
