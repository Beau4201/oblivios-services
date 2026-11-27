// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.chronicle;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.oblivios.decoy.content.world.WorldBuilder;

/** What keeps the persona busy at a moment: the events of a chronicle that take time, not the ones that mark days. */
public final class Availability {
    /** No event that takes time is longer than this, so a search goes back no further. */
    private static final Duration LONGEST = Duration.ofDays(2);

    private final List<Event> busy;

    Availability(List<Event> events) {
        // an event that ends when it starts, as one done on the phone does, takes no time
        busy = events.stream().filter(e -> !e.kind().marksDays() && e.end().isAfter(e.start()))
                .sorted(Comparator.comparing(Event::start)).toList();
    }

    public Optional<Block> at(Instant t) {
        return event(t, e -> true).map(e -> new Block(e.kind(), LocalTime.ofInstant(e.start(), e.zone()),
                LocalTime.ofInstant(e.end(), e.zone()), e.placeId(), e.people(), e.origin()));
    }

    public boolean asleep(Instant t) {
        return event(t, e -> e.kind() == EventKind.SLEEP).isPresent();
    }

    public boolean free(Instant t) {
        return event(t, e -> true).isEmpty();
    }

    /** Whether nothing takes any part of the time from {@code from} up to {@code to}. */
    public boolean free(Instant from, Instant to) {
        return busy.stream().noneMatch(e -> e.start().isBefore(to) && e.end().isAfter(from));
    }

    /** The place of what the persona does at {@code t}; home where nothing says another. */
    public String placeAt(Instant t) {
        return event(t, e -> true).map(Event::placeId).filter(id -> !id.isEmpty()).orElse(WorldBuilder.HOME);
    }

    private Optional<Event> event(Instant t, Predicate<Event> wanted) {
        int low = 0;
        int high = busy.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (busy.get(middle).start().isAfter(t)) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        for (int i = low - 1; i >= 0 && !busy.get(i).start().isBefore(t.minus(LONGEST)); i--) {
            if (busy.get(i).end().isAfter(t) && wanted.test(busy.get(i))) {
                return Optional.of(busy.get(i));
            }
        }
        return Optional.empty();
    }
}
