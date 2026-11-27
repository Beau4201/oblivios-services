// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.chronicle;

import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import org.oblivios.decoy.content.origin.Origin;

/** A stretch of a day of the routine, in local time; an end that is not after the start lies on the next day. */
public record Block(EventKind kind, LocalTime start, LocalTime end, String placeId, List<String> people, Origin origin) {
    public Block {
        Objects.requireNonNull(origin, "origin");
        people = List.copyOf(people);
    }

    /** The start in minutes from the midnight of the block's day. */
    int from() {
        return start.toSecondOfDay() / 60;
    }

    /** The end in minutes from the same midnight; above 1440 when the block crosses midnight. */
    int to() {
        int to = end.toSecondOfDay() / 60;
        return to > from() ? to : to + 24 * 60;
    }

    boolean overlaps(Block other) {
        return from() < other.to() && other.from() < to();
    }

    boolean work() {
        return kind == EventKind.WORK || kind == EventKind.SCHOOL;
    }
}
