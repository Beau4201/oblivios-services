// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.chronicle;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.List;
import org.oblivios.decoy.content.origin.Origin;
import org.oblivios.decoy.content.world.World;
import org.oblivios.decoy.content.world.WorldPerson;

/**
 * The life events beside leave, sickness (exceptions of the routine) and trips: the birthdays of the people of the
 * world. A lasting change exists only when an answer or a pin of the review says so; none can yet, so none is made.
 */
final class LifeEvents {
    private LifeEvents() {}

    static void birthdays(ChronicleBuilder.Events events, World w, LocalDate from, LocalDate to) {
        List<WorldPerson> everyone = new ArrayList<>(w.people());
        everyone.add(w.self());
        for (WorldPerson who : everyone) {
            for (int year = from.getYear(); year <= to.getYear(); year++) {
                LocalDate day = MonthDay.from(who.birthDate()).atYear(year); // 29 February is 28 in other years
                if (!day.isBefore(from) && !day.isAfter(to) && day.isAfter(who.birthDate())) {
                    events.days(EventKind.BIRTHDAY, day, day, List.of(who.id()), Origin.world(who.id()), Event.Layer.LIFE);
                }
            }
        }
    }
}
