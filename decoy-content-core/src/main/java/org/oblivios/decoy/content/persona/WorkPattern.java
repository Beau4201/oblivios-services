// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.persona;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Set;

/**
 * The days and hours of work or study; an end before the start crosses midnight. {@code rotating} shifts change from
 * week to week.
 */
public record WorkPattern(Set<DayOfWeek> days, LocalTime start, LocalTime end, boolean rotating) {

    /** The pattern of a persona without work or study. */
    public static final WorkPattern NONE = new WorkPattern(Set.of(), LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, false);

    public WorkPattern {
        days = days == null ? null : Set.copyOf(days);
    }
}
