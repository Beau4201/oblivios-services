// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.oblivios.decoy.content.Seed;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.lang.LanguagePack;
import org.oblivios.decoy.content.story.Timeline;

/**
 * Calendar entries, alarms and notes of the story. Entries reach {@link #HORIZON_DAYS} days past now (a few
 * appointments and the coming public holidays, because a calendar with nothing ahead is a tell); nothing else of the
 * story lies after now (the validator rule R-FUTURE).
 */
public final class CalendarSurface {
    /** How far ahead of now a calendar entry may lie; the checker allows exactly this. */
    public static final int HORIZON_DAYS = 90;

    private CalendarSurface() {}

    /** {@code lang} is the persona's language; a holiday without a name in the pack gets the generic title of the country's language. */
    public static CalendarPlan generate(People people, Timeline t, CountryPack c, LanguagePack lang, Seed s) {
        RandomGenerator r = s.stream("calendar");
        String holiday = LanguagePack.forService(c, lang.tag()).text("holiday.generic");
        ZoneId zone = t.zone();
        LocalDate nowDate = LocalDate.ofInstant(t.now(), ZoneOffset.UTC);
        LocalDate startDate = LocalDate.ofInstant(t.phoneStart(), zone);
        List<CalEvent> events = new ArrayList<>();

        for (Contact contact : people.contacts()) {
            if (contact.birthday() != null) {
                String title = lang.format("calendar.birthday", Map.of("name", contact.name()));
                events.add(new CalEvent(title, contact.birthday().atStartOfDay(ZoneOffset.UTC).toInstant(), true));
            }
        }
        LocalDate horizonDate = nowDate.plusDays(HORIZON_DAYS);
        for (int year = startDate.getYear(); year <= horizonDate.getYear(); year++) {
            for (LocalDate h : c.publicHolidays(year)) {
                if (!h.isBefore(startDate) && !h.isAfter(horizonDate)) {
                    events.add(new CalEvent(c.holidayName(h).orElse(holiday), h.atStartOfDay(ZoneOffset.UTC).toInstant(), false));
                }
            }
        }
        List<String> titles = lang.list("calendar.appointments");
        long days = Math.max(1, Duration.between(t.phoneStart(), t.now()).toDays());
        int appointments = (int) Math.min(600, Math.max(45, days / 6));
        int upcoming = 3 + r.nextInt(6);
        for (int i = 0; i < appointments + upcoming; i++) {
            String title = titles.get(r.nextInt(titles.size()));
            if (r.nextInt(100) < 25 && !people.contacts().isEmpty()) {
                String name = people.contacts().get(r.nextInt(people.contacts().size())).name();
                title = lang.format("calendar.lunch", Map.of("name", name));
            }
            // the last few lie ahead: from tomorrow to the horizon
            Instant from = i < appointments ? t.phoneStart() : t.now().plus(Duration.ofDays(1));
            Instant to = i < appointments ? t.now() : t.now().plus(Duration.ofDays(HORIZON_DAYS));
            events.add(new CalEvent(title, Events.randomInstant(r, from, to, zone, LocalTime.of(8, 0), LocalTime.of(20, 0)), false));
        }
        events.sort(Comparator.comparing(CalEvent::start));

        List<Alarm> alarms = new ArrayList<>();
        Set<DayOfWeek> weekdays = EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
        alarms.add(new Alarm(t.wakeTime().minusMinutes(5L * r.nextInt(7)), weekdays));
        if (r.nextBoolean()) {
            alarms.add(new Alarm(t.wakeTime().plusMinutes(60 + 5L * r.nextInt(25)), EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)));
        }
        if (r.nextInt(100) < 30) {
            alarms.add(new Alarm(LocalTime.of(12 + r.nextInt(6), 5 * r.nextInt(12)), EnumSet.of(DayOfWeek.values()[r.nextInt(7)])));
        }

        List<String> texts = lang.list("calendar.notes");
        List<Note> notes = new ArrayList<>();
        int noteCount = 3 + r.nextInt(10);
        for (int i = 0; i < noteCount; i++) {
            Instant created = Events.randomInstant(r, t.phoneStart(), t.now(), zone, LocalTime.of(7, 0), LocalTime.of(22, 0));
            notes.add(new Note(texts.get(r.nextInt(texts.size())), created, r.nextInt(100) < 40));
        }
        notes.sort(Comparator.comparing(Note::created));
        return new CalendarPlan(events, alarms, notes);
    }
}
