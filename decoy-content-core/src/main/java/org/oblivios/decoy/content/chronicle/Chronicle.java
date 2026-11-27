// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.chronicle;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/** What happens when, from the start of the phone to now, the earliest event first. */
public record Chronicle(List<Event> events, Routine routine, Instant phoneStart, Instant now, ZoneId zone) {
    public Chronicle {
        events = List.copyOf(events);
    }

    public Availability availability() {
        return new Availability(events);
    }

    /** The events of which any part lies from {@code from} up to, not including, {@code to}. */
    public List<Event> between(Instant from, Instant to) {
        return events.stream().filter(e -> e.start().isBefore(to) && e.end().isAfter(from)).toList();
    }

    public Optional<Event> event(String id) {
        return events.stream().filter(e -> e.id().equals(id)).findFirst();
    }
}
