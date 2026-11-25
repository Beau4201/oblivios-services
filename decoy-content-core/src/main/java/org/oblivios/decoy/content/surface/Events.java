// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.random.RandomGenerator;

/** Draws event times inside the story: between the phone start and now, in the waking hours of the zone. */
final class Events {
    private Events() {}

    static Instant randomInstant(RandomGenerator r, Instant from, Instant to, ZoneId zone, LocalTime earliest, LocalTime latest) {
        long days = Math.max(0, Duration.between(from, to).toDays());
        LocalDate first = from.atZone(zone).toLocalDate();
        int e = earliest.toSecondOfDay() / 60;
        int l = latest.toSecondOfDay() / 60;
        for (int attempt = 0; attempt < 40; attempt++) {
            LocalDate day = first.plusDays(r.nextLong(days + 1));
            int minute = e + r.nextInt(Math.max(1, l - e + 1));
            // local date and time first, then the zone: adding minutes to a zoned start of day moves across a DST change
            Instant at = day.atTime(minute / 60, minute % 60).atZone(zone).toInstant();
            if (!at.isBefore(from) && !at.isAfter(to)) {
                return at;
            }
        }
        return to.minus(Duration.ofMinutes(1 + r.nextInt(600))); // the span is under a day: stay before now
    }
}
