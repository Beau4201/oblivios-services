// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.chronicle;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.oblivios.decoy.content.origin.Origin;

/**
 * One thing that happens in the life: {@code placeId} is a place of the world, or empty where the story holds none;
 * {@code people} are ids of the world. {@code details} has the keys {@code company}, {@code amount}, {@code ref},
 * {@code site} and {@code title}.
 */
public record Event(String id, EventKind kind, Instant start, Instant end, ZoneId zone, String placeId, List<String> people,
        Layer layer, Set<Trace> traces, Map<String, String> details, Origin origin) {

    /** The layer of the design that made the event; each adapts to the ones before it. */
    public enum Layer { YEAR, ROUTINE, LIFE, CONVERSATION, SMALL }

    public Event {
        Objects.requireNonNull(origin, "origin");
        people = List.copyOf(people);
        traces = Set.copyOf(traces);
        details = Map.copyOf(details);
    }
}
