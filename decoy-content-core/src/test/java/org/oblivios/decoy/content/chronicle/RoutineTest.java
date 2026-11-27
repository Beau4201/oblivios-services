// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.chronicle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.oblivios.decoy.content.Draws;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.PersonaInputs;
import org.oblivios.decoy.content.Seed;
import org.oblivios.decoy.content.TestSetup;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.country.Packs;
import org.oblivios.decoy.content.data.DataSet;
import org.oblivios.decoy.content.data.HolidayData;
import org.oblivios.decoy.content.data.PlaceData;
import org.oblivios.decoy.content.data.SchoolHoliday;
import org.oblivios.decoy.content.persona.ActivityAnswer;
import org.oblivios.decoy.content.persona.ChildAnswer;
import org.oblivios.decoy.content.persona.ChildAnswer.Custody;
import org.oblivios.decoy.content.persona.PersonaProfile;
import org.oblivios.decoy.content.persona.PersonaProfile.ActivityKind;
import org.oblivios.decoy.content.persona.PersonaProfile.Gender;
import org.oblivios.decoy.content.persona.PersonaProfile.JobKind;
import org.oblivios.decoy.content.persona.PersonaProfile.Transport;
import org.oblivios.decoy.content.persona.Profiles;
import org.oblivios.decoy.content.persona.WorkPattern;
import org.oblivios.decoy.content.story.MonthCount;
import org.oblivios.decoy.content.story.VolumeCounts;
import org.oblivios.decoy.content.surface.Relation;
import org.oblivios.decoy.content.world.World;
import org.oblivios.decoy.content.world.WorldBuilder;
import org.oblivios.decoy.content.world.WorldPerson;

class RoutineTest {
    static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");
    private static final Set<DayOfWeek> WEEK = EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);

    record Built(Persona persona, World world, Chronicle chronicle) {
        ZoneId zone() {
            return chronicle.zone();
        }

        List<Event> of(EventKind kind) {
            return chronicle.events().stream().filter(e -> e.kind() == kind).toList();
        }

        LocalDate day(Event e) {
            return LocalDate.ofInstant(e.start(), zone());
        }

        LocalTime time(Instant at) {
            return LocalTime.ofInstant(at, zone());
        }

        Instant at(LocalDate d, int hour, int minute) {
            return d.atTime(hour, minute).atZone(zone()).toInstant();
        }

        /** The sleep that ends last before {@code t}. */
        Event sleepBefore(Instant t) {
            return of(EventKind.SLEEP).stream().filter(s -> !s.end().isAfter(t))
                    .reduce((a, b) -> b.end().isAfter(a.end()) ? b : a).orElseThrow();
        }
    }

    static Built build(String city, int months, long seed, UnaryOperator<PersonaProfile.Builder> answers) {
        PersonaInputs in = Profiles.inputs();
        PersonaInputs at = new PersonaInputs(in.givenName(), in.familyName(), city, in.countryCode(), in.zone(), in.locale());
        PersonaProfile profile = answers.apply(PersonaProfile.builder(at).birthYear(1988).gender(Gender.FEMALE)
                .languages(List.of("nl")).transport(Transport.BIKE).children(List.of()).activities(List.of())).build();
        CountryPack pack = Packs.load("NL").orElseThrow();
        Persona persona = TestSetup.derive(Seed.of(seed, seed), profile, 2026);
        Draws draws = Draws.of(Seed.of(seed, seed + 1));
        DataSet data = DataSet.of("NL");
        World world = WorldBuilder.build(persona, pack, data, draws);
        List<MonthCount> counts = new ArrayList<>();
        for (int i = 0; i < months; i++) {
            counts.add(new MonthCount(YearMonth.of(2026, 10).minusMonths(months - 1L - i), 60, 10, 8, 1_000_000L));
        }
        Chronicle c = ChronicleBuilder.frame(persona, world, data,
                TestSetup.inputs(new VolumeCounts(counts), List.of(), List.of(), "AndroidWifi", null, null, NOW), draws);
        return new Built(persona, world, c);
    }

    private static WorkPattern hours(int start, int end, boolean rotating) {
        return new WorkPattern(WEEK, LocalTime.of(start, 0), LocalTime.of(end, 0), rotating);
    }

    private static Built office(int start, int end) {
        return build("utrecht", 18, 1, b -> b.job(JobKind.OFFICE).work(hours(start, end, false)));
    }

    @Test
    void wakeFollowsWorkStartAndTravel() {
        Built b = office(7, 15);
        List<Event> work = b.of(EventKind.WORK);
        assertTrue(work.size() > 100, "work days: " + work.size());
        for (Event w : work) {
            Event there = b.of(EventKind.COMMUTE).stream().filter(c -> c.end().equals(w.start())).findFirst().orElseThrow();
            assertTrue(there.start().isBefore(w.start()));
            Event sleep = b.sleepBefore(there.start());
            long ready = Duration.between(sleep.end(), there.start()).toMinutes();
            assertTrue(ready >= 30 && ready <= 120, "awake " + ready + " min before leaving on " + b.day(w));
        }
    }

    @Test
    void nightShiftSleepsByDay() {
        Built b = build("utrecht", 18, 2, p -> p.job(JobKind.SHIFT).work(hours(22, 6, false)));
        Availability a = b.chronicle().availability();
        List<Event> nights = b.of(EventKind.WORK);
        assertTrue(nights.size() > 100);
        for (Event w : nights.subList(0, nights.size() - 1)) {
            LocalDate d = b.day(w);
            assertTrue(a.asleep(b.at(d.plusDays(1), 10, 0)), "awake at 10:00 after the night of " + d);
            assertFalse(a.asleep(b.at(d, 23, 0)), "asleep at 23:00 in the shift of " + d);
        }
    }

    @Test
    void aShiftThatEndsAtMidnightIsFollowedByTheWayHomeAndThenSleep() {
        Built b = build("utrecht", 14, 10, p -> p.job(JobKind.SHIFT).work(hours(16, 0, false)));
        List<Event> work = b.of(EventKind.WORK);
        assertTrue(work.size() > 100);
        for (Event w : work.subList(0, work.size() - 1)) {
            Event home = b.of(EventKind.COMMUTE).stream().filter(c -> c.start().equals(w.end())).findFirst().orElseThrow();
            Event sleep = b.of(EventKind.SLEEP).stream().filter(s -> s.start().isAfter(w.start())).findFirst().orElseThrow();
            assertFalse(sleep.start().isBefore(home.end()), "asleep on the way home, " + b.day(w));
        }
    }

    @Test
    void rotatingShiftAlternatesWeeks() {
        Built b = build("utrecht", 18, 3, p -> p.job(JobKind.SHIFT).work(hours(7, 15, true)));
        Set<LocalTime> starts = new HashSet<>();
        LocalDate lastMonday = null;
        LocalTime lastStart = null;
        for (Event w : b.of(EventKind.WORK)) {
            LocalDate monday = b.day(w).with(DayOfWeek.MONDAY);
            LocalTime start = b.time(w.start());
            starts.add(start);
            if (lastMonday != null && monday.equals(lastMonday)) {
                assertEquals(lastStart, start, "two shifts in the week of " + monday);
            } else if (lastMonday != null && ChronoUnit.WEEKS.between(lastMonday, monday) == 1) {
                assertFalse(start.equals(lastStart), "the same shift in two weeks, the second of " + monday);
            }
            lastMonday = monday;
            lastStart = start;
        }
        assertEquals(Set.of(LocalTime.of(7, 0), LocalTime.of(15, 0), LocalTime.of(23, 0)), starts);
    }

    @Test
    void weekendWakesLater() {
        Built b = office(8, 17);
        assertTrue(b.chronicle().routine().wakeFree().isAfter(b.chronicle().routine().wakeWorkday()));
        int saturdays = 0;
        for (Event s : b.of(EventKind.SLEEP)) {
            if (LocalDate.ofInstant(s.end(), b.zone()).getDayOfWeek() == DayOfWeek.SATURDAY) {
                saturdays++;
                assertTrue(b.time(s.end()).isAfter(b.chronicle().routine().wakeWorkday()), "early on a Saturday: " + s);
            }
        }
        assertTrue(saturdays > 50);
    }

    @Test
    void noWorkOnPublicHolidays() {
        Built b = office(8, 17);
        CountryPack pack = Packs.load("NL").orElseThrow();
        for (Event w : b.of(EventKind.WORK)) {
            assertFalse(pack.publicHolidays(b.day(w).getYear()).contains(b.day(w)), "work on " + b.day(w));
        }
        assertFalse(b.of(EventKind.PUBLIC_HOLIDAY).isEmpty());
        assertFalse(b.of(EventKind.LEAVE).isEmpty());
        Set<LocalDate> off = new HashSet<>();
        b.of(EventKind.LEAVE).forEach(e -> off.add(b.day(e)));
        b.of(EventKind.SICK).forEach(e -> off.add(b.day(e)));
        b.of(EventKind.WORK).forEach(w -> assertFalse(off.contains(b.day(w)), "work on a day off " + b.day(w)));
    }

    private static boolean in(List<SchoolHoliday> holidays, LocalDate d) {
        return holidays.stream().anyMatch(h -> !d.isBefore(h.from()) && !d.isAfter(h.to()));
    }

    @Test
    void schoolChildrenFollowTheirRegion() {
        HolidayData h = HolidayData.load("NL");
        for (String[] pair : new String[][] {{"groningen", "maastricht"}, {"maastricht", "groningen"}}) {
            Built b = build(pair[0], 24, 4, p -> p.job(JobKind.NONE).children(List.of(Profiles.child(8, Gender.MALE, "Sem"))));
            List<SchoolHoliday> own = h.holidays(PlaceData.require("NL", pair[0]).schoolRegion());
            List<SchoolHoliday> other = h.holidays(PlaceData.require("NL", pair[1]).schoolRegion());
            int whileTheOtherIsFree = 0;
            for (Event e : b.of(EventKind.BRING_CHILD)) {
                assertFalse(in(own, b.day(e)), pair[0] + ": to school in a holiday, " + b.day(e));
                whileTheOtherIsFree += in(other, b.day(e)) ? 1 : 0;
            }
            assertTrue(whileTheOtherIsFree > 0, pair[0] + " follows the holidays of " + pair[1]);
        }
    }

    @Test
    void childrenAreBroughtOnTheirDays() {
        ChildAnswer shared = new ChildAnswer(8, Gender.MALE, "Sem", Custody.SHARED, Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY));
        Built b = build("utrecht", 18, 5, p -> p.job(JobKind.NONE).children(List.of(shared)));
        String child = b.world().people().stream().filter(x -> x.relation() == Relation.CHILD).map(WorldPerson::id)
                .findFirst().orElseThrow();
        for (EventKind kind : new EventKind[] {EventKind.BRING_CHILD, EventKind.FETCH_CHILD}) {
            Set<DayOfWeek> seen = new HashSet<>();
            for (Event e : b.of(kind)) {
                seen.add(b.day(e).getDayOfWeek());
                assertEquals(List.of(child), e.people(), kind.toString());
            }
            assertEquals(shared.daysWithPersona(), seen, kind.toString());
        }
    }

    @Test
    void activitiesRecurWeekly() {
        ActivityAnswer gym = new ActivityAnswer(ActivityKind.GYM, DayOfWeek.FRIDAY, LocalTime.of(18, 0), 60, null);
        Built b = build("utrecht", 18, 6, p -> p.job(JobKind.NONE).activities(List.of(gym)));
        Set<LocalDate> expected = new HashSet<>();
        Set<LocalDate> sick = new HashSet<>();
        b.of(EventKind.SICK).forEach(e -> sick.add(b.day(e)));
        for (LocalDate d = LocalDate.ofInstant(b.chronicle().phoneStart(), b.zone()).plusDays(1);
                d.isBefore(LocalDate.ofInstant(NOW, b.zone())); d = d.plusDays(1)) {
            if (d.getDayOfWeek() == DayOfWeek.FRIDAY && !sick.contains(d)) {
                expected.add(d);
            }
        }
        Set<LocalDate> found = new HashSet<>();
        for (Event e : b.of(EventKind.ACTIVITY)) {
            assertEquals(LocalTime.of(18, 0), b.time(e.start()));
            assertEquals(60, Duration.between(e.start(), e.end()).toMinutes());
            found.add(b.day(e));
        }
        assertTrue(found.containsAll(expected), "missing " + expected.stream().filter(d -> !found.contains(d)).toList());
    }

    @Test
    void phoneStartsWithinTheHolidayData() {
        LocalDate first = HolidayData.load("NL").firstDay();
        Built parent = build("utrecht", 40, 7, p -> p.job(JobKind.NONE).children(List.of(Profiles.child(8, Gender.MALE, "Sem"))));
        assertFalse(LocalDate.ofInstant(parent.chronicle().phoneStart(), parent.zone()).isBefore(first));
        Built single = build("utrecht", 40, 7, p -> p.job(JobKind.NONE));
        assertTrue(LocalDate.ofInstant(single.chronicle().phoneStart(), single.zone()).isBefore(first));
    }

    @Test
    void dstGapIsNeverAnEventTime() {
        ActivityAnswer late = new ActivityAnswer(ActivityKind.GAMING, DayOfWeek.SUNDAY, LocalTime.of(2, 30), 60, null);
        Built b = build("utrecht", 18, 8, p -> p.job(JobKind.NONE).activities(List.of(late)));
        LocalDate change = LocalDate.of(2026, 3, 29);
        Event e = b.of(EventKind.ACTIVITY).stream().filter(x -> b.day(x).equals(change)).findFirst().orElseThrow();
        assertEquals(LocalDateTime.of(change, LocalTime.of(3, 30)), LocalDateTime.ofInstant(e.start(), b.zone()));
        assertEquals(60, Duration.between(e.start(), e.end()).toMinutes());
        b.chronicle().events().forEach(x -> assertTrue(x.end().isAfter(x.start()), x.toString()));
    }

    @Test
    void sameDrawsSameRoutine() {
        assertEquals(office(8, 17).chronicle(), office(8, 17).chronicle());
    }

    /** Whatever the life, nobody is in two blocks at once, and every night's sleep is one a person could have. */
    @Test
    void timeIsNeverSpentTwice() {
        List<String> found = new ArrayList<>();
        int built = 0;
        for (int seed = 0; seed < 60; seed++) {
            JobKind job = JobKind.values()[seed % JobKind.values().length];
            boolean rotating = seed % 4 == 0;
            Transport transport = Transport.values()[seed % 3];
            int start = new int[] {5, 7, 14, 18, 22}[seed % 5];
            List<ChildAnswer> children = seed % 2 == 0 ? List.of()
                    : List.of(Profiles.child(2, Gender.FEMALE, "Mila"), Profiles.child(8, Gender.MALE, "Sem"));
            ActivityAnswer gym = new ActivityAnswer(ActivityKind.GYM, DayOfWeek.of(1 + seed % 7), LocalTime.of(7 + seed % 16, 0), 90,
                    null);
            Built b = build("utrecht", 14, 100 + seed, p -> {
                p.job(job).transport(transport).children(children).activities(List.of(gym)).birthYear(1990);
                boolean works = job != JobKind.RETIRED && job != JobKind.NONE && job != JobKind.JOB_SEEKER;
                return works ? p.work(hours(start, (start + 8) % 24, rotating)) : p;
            });
            built++;
            Event last = null;
            for (Event e : b.chronicle().events()) {
                if (e.kind().marksDays()) {
                    continue;
                }
                String of = job + " from " + start + (rotating ? " rotating, " : ", ") + transport + ", seed " + seed + ": ";
                // a bring or fetch may run into the commute that follows it from the same door, nothing else may
                if (last != null && e.start().isBefore(last.end())) {
                    found.add(of + last.kind() + " to " + LocalDateTime.ofInstant(last.end(), b.zone()) + " overlaps " + e.kind()
                            + " from " + LocalDateTime.ofInstant(e.start(), b.zone()));
                }
                if (e.kind() == EventKind.SLEEP) {
                    long minutes = Duration.between(e.start(), e.end()).toMinutes();
                    if (minutes < 120 || minutes > 14 * 60) {
                        found.add(of + "a sleep of " + minutes + " min from " + LocalDateTime.ofInstant(e.start(), b.zone()));
                    }
                }
                if (last == null || e.end().isAfter(last.end())) {
                    last = e;
                }
            }
        }
        assertEquals(60, built);
        assertTrue(found.isEmpty(), found.size() + " overlaps or odd sleeps, the first: " + found.stream().limit(12).toList());
    }

    @Test
    void eventIdsAreUniqueAndFound() {
        Chronicle c = build("utrecht", 18, 9, p -> p.job(JobKind.OFFICE).work(hours(9, 17, false))
                .children(List.of(Profiles.child(2, Gender.FEMALE, "Mila"), Profiles.child(9, Gender.MALE, "Sem")))).chronicle();
        assertEquals(c.events().size(), c.events().stream().map(Event::id).distinct().count());
        Event any = c.events().get(c.events().size() / 2);
        assertEquals(any, c.event(any.id()).orElseThrow());
        c.events().forEach(e -> assertTrue(e.start().isBefore(c.now()), "starts after now: " + e));
    }
}
