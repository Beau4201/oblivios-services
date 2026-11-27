// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.chronicle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.oblivios.decoy.content.chronicle.RoutineTest.Built;
import org.oblivios.decoy.content.origin.Origin;
import org.oblivios.decoy.content.persona.PersonaProfile.JobKind;
import org.oblivios.decoy.content.world.WorldPerson;

class LifeEventsTest {

    @Test
    void birthdaysAreEventsOfEveryPerson() {
        Built b = RoutineTest.build("utrecht", 30, 11, p -> p.job(JobKind.NONE));
        LocalDate from = LocalDate.ofInstant(b.chronicle().phoneStart(), b.zone());
        LocalDate to = LocalDate.ofInstant(b.chronicle().now(), b.zone());
        List<WorldPerson> everyone = new ArrayList<>(b.world().people());
        everyone.add(b.world().self());
        int expected = 0;
        for (WorldPerson who : everyone) {
            for (int year = from.getYear(); year <= to.getYear(); year++) {
                LocalDate day = MonthDay.from(who.birthDate()).atYear(year); // 29 February is 28 in other years
                if (day.isBefore(from) || day.isAfter(to) || !day.isAfter(who.birthDate())) {
                    continue;
                }
                expected++;
                assertTrue(b.of(EventKind.BIRTHDAY).stream().anyMatch(e -> b.day(e).equals(day)
                        && e.people().equals(List.of(who.id())) && e.origin().equals(Origin.world(who.id()))
                        && e.layer() == Event.Layer.LIFE), "no birthday of " + who.id() + " on " + day);
            }
        }
        assertTrue(expected > 20, "birthdays: " + expected);
        assertEquals(expected, b.of(EventKind.BIRTHDAY).size());
    }

    /** No answer and no pin gives a lasting change yet, so the story holds none. */
    @Test
    void noLastingChangeWithoutAnAnswer() {
        for (long seed = 1; seed <= 20; seed++) {
            Built b = RoutineTest.build("utrecht", 30, seed, p -> p);
            assertEquals(List.of(), b.of(EventKind.CHANGE), "seed " + seed);
        }
    }
}
