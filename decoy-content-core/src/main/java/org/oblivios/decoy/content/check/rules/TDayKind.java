// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check.rules;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.chronicle.Event;
import org.oblivios.decoy.content.chronicle.EventKind;
import org.oblivios.decoy.content.chronicle.YearFrame;
import org.oblivios.decoy.content.check.OriginRule;
import org.oblivios.decoy.content.check.PhoneViews;
import org.oblivios.decoy.content.check.PlanStory;
import org.oblivios.decoy.content.check.Violation;
import org.oblivios.decoy.content.persona.PersonaProfile.JobKind;
import org.oblivios.decoy.content.story.PlaceKind;

/**
 * {@code T-DAY-KIND}: work or school on a day without work or school: a public holiday, a day outside the work week, a
 * day of leave or sickness, or for a pupil a school holiday; and a child brought to or fetched from school on a public
 * holiday, in a school holiday or in the weekend. A view item has no day of its own yet: its event is judged.
 */
public final class TDayKind implements OriginRule {

    @Override
    public String id() {
        return "T-DAY-KIND";
    }

    @Override
    public void check(PhoneViews v, PlanStory story, List<Violation> out) {
        Persona p = story.persona();
        YearFrame year = story.year();
        List<Event> events = story.chronicle().events();
        Set<LocalDate> off = new HashSet<>();
        events.stream().filter(e -> e.kind() == EventKind.LEAVE || e.kind() == EventKind.SICK)
                .forEach(e -> off.add(day(e)));
        for (Event e : events) {
            LocalDate day = day(e);
            String why = null;
            if (e.kind() == EventKind.WORK || e.kind() == EventKind.SCHOOL) {
                if (year.publicHoliday(day)) {
                    why = "a public holiday";
                } else if (!p.work().days().contains(day.getDayOfWeek())) {
                    why = "a day without work";
                } else if (off.contains(day)) {
                    why = "a day of leave or sickness";
                } else if (p.job() == JobKind.PUPIL && year.schoolHoliday(day)) {
                    why = "a school holiday";
                }
            } else if (e.kind() == EventKind.BRING_CHILD || e.kind() == EventKind.FETCH_CHILD) {
                boolean care = story.world().place(e.placeId()).map(x -> x.kind() == PlaceKind.CARE).orElse(false);
                if (year.publicHoliday(day)) {
                    why = "a public holiday";
                } else if (!care && year.schoolHoliday(day)) {
                    why = "a school holiday";
                } else if (!care && day.getDayOfWeek().getValue() >= DayOfWeek.SATURDAY.getValue()) {
                    why = "the weekend";
                }
            }
            if (why != null) {
                out.add(new Violation(id(), e.kind() + " on " + day + ", " + why));
            }
        }
    }

    private static LocalDate day(Event e) {
        return LocalDate.ofInstant(e.start(), e.zone());
    }
}
