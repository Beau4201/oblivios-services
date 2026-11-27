// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.chronicle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.oblivios.decoy.content.chronicle.RoutineTest.Built;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.country.Packs;
import org.oblivios.decoy.content.data.HolidayData;
import org.oblivios.decoy.content.data.PlaceData;
import org.oblivios.decoy.content.data.PlaceData.Destination;
import org.oblivios.decoy.content.data.SchoolHoliday;
import org.oblivios.decoy.content.origin.Origin;
import org.oblivios.decoy.content.persona.PersonaProfile.Gender;
import org.oblivios.decoy.content.persona.PersonaProfile.JobKind;
import org.oblivios.decoy.content.persona.PersonaProfile.TravelMode;
import org.oblivios.decoy.content.persona.Profiles;
import org.oblivios.decoy.content.persona.TripAnswer;
import org.oblivios.decoy.content.persona.WorkPattern;
import org.oblivios.decoy.content.surface.Trip;
import org.oblivios.decoy.content.world.WorldPlace;

class TripsTest {
    private static final Set<DayOfWeek> WEEK = EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
    private static final TripAnswer FRANCE = new TripAnswer(YearMonth.of(2026, 7), 9, "FR", TravelMode.CAR);
    private static final TripAnswer INDONESIA = new TripAnswer(YearMonth.of(2026, 2), 15, "ID", TravelMode.PLANE);
    private static final TripAnswer HOME_COUNTRY = new TripAnswer(YearMonth.of(2026, 9), 3, "NL", TravelMode.TRAIN);
    private static final List<TripAnswer> ANSWERS = List.of(FRANCE, INDONESIA, HOME_COUNTRY);

    private static Built office(long seed, int months, List<TripAnswer> trips) {
        return RoutineTest.build("utrecht", months, seed, p -> p.job(JobKind.OFFICE)
                .work(new WorkPattern(WEEK, LocalTime.of(8, 30), LocalTime.of(17, 0), false)).trips(trips));
    }

    private static Built parent(long seed, int months, List<TripAnswer> trips) {
        return RoutineTest.build("utrecht", months, seed, p -> p.job(JobKind.OFFICE)
                .work(new WorkPattern(WEEK, LocalTime.of(9, 0), LocalTime.of(17, 0), false))
                .children(List.of(Profiles.child(8, Gender.MALE, "Sem"))).trips(trips));
    }

    /** The dates at home from the day of leaving to the day of coming back. */
    private static List<LocalDate> dates(Built b, Trip t) {
        LocalDate first = LocalDate.ofInstant(t.from(), b.zone());
        LocalDate last = LocalDate.ofInstant(t.to(), b.zone());
        return first.datesUntil(last.plusDays(1)).toList();
    }

    private static YearMonth month(Built b, Trip t) {
        return YearMonth.from(LocalDate.ofInstant(t.from(), b.zone()));
    }

    private static Set<String> names(String country) {
        return PlaceData.abroad(country).stream().map(Destination::name).collect(Collectors.toSet());
    }

    @Test
    void pastYearFollowsTheAnswers() {
        for (long seed = 1; seed <= 8; seed++) {
            Built b = office(seed, 14, ANSWERS);
            List<Trip> past = b.chronicle().trips().stream()
                    .filter(t -> !month(b, t).isBefore(YearMonth.of(2025, 11))).toList();
            assertEquals(3, past.size(), "seed " + seed + ": " + past);
            for (TripAnswer a : ANSWERS) {
                Trip t = past.stream().filter(x -> month(b, x).equals(a.month())).findFirst().orElseThrow();
                assertEquals(a.days(), dates(b, t).size(), "seed " + seed + " " + a);
                assertEquals(a.mode(), t.mode());
                assertFalse(t.places().isEmpty());
                if (a.country().equals("NL")) {
                    assertTrue(t.places().stream().allMatch(p -> !p.municipality().isEmpty()), t.toString());
                    assertTrue(t.places().stream().noneMatch(p -> p.municipality()
                            .equals(PlaceData.require("NL", "utrecht").municipality())), "a trip into the home city " + t);
                } else {
                    Set<String> known = names(a.country());
                    assertTrue(t.places().stream().allMatch(p -> known.contains(p.name())), t.toString());
                }
            }
        }
    }

    @Test
    void noTripWithoutAnAnswer() {
        assertEquals(List.of(), office(3, 40, List.of()).chronicle().trips());
    }

    @Test
    void earlierYearsFollowThePastYear() {
        int earlier = 0;
        for (long seed = 1; seed <= 10; seed++) {
            Built b = office(seed, 40, ANSWERS);
            for (Trip t : b.chronicle().trips()) {
                if (month(b, t).isBefore(YearMonth.of(2025, 11))) {
                    earlier++;
                    assertTrue(ANSWERS.stream().anyMatch(a -> a.mode() == t.mode()
                            && a.month().getMonth() == month(b, t).getMonth()), "like no answer: " + t);
                }
            }
        }
        assertTrue(earlier > 10, "earlier trips: " + earlier);
    }

    @Test
    void tripsFallInLeaveOrFreeDays() {
        CountryPack pack = Packs.load("NL").orElseThrow();
        int leaveNeeded = 0;
        for (long seed = 1; seed <= 10; seed++) {
            Built b = office(seed, 30, ANSWERS);
            Set<LocalDate> leave = b.of(EventKind.LEAVE).stream().map(b::day).collect(Collectors.toSet());
            assertFalse(b.chronicle().trips().isEmpty());
            for (Trip t : b.chronicle().trips()) {
                for (LocalDate d : dates(b, t)) {
                    if (WEEK.contains(d.getDayOfWeek()) && !pack.publicHolidays(d.getYear()).contains(d)
                            && d.isAfter(LocalDate.ofInstant(b.chronicle().phoneStart(), b.zone()))) {
                        leaveNeeded++;
                        assertTrue(leave.contains(d), "a work day of a trip without leave, seed " + seed + ": " + d);
                    }
                }
                for (EventKind kind : new EventKind[] {EventKind.WORK, EventKind.COMMUTE, EventKind.ACTIVITY}) {
                    for (Event e : b.of(kind)) {
                        assertFalse(e.start().isBefore(t.to()) && e.end().isAfter(t.from()), kind + " during " + t + ": " + e);
                    }
                }
            }
        }
        assertTrue(leaveNeeded > 50, "work days in trips: " + leaveNeeded);
    }

    @Test
    void schoolChildTripsOnlyInSchoolHolidays() {
        List<SchoolHoliday> school = HolidayData.load("NL").holidays(PlaceData.require("NL", "utrecht").schoolRegion());
        CountryPack pack = Packs.load("NL").orElseThrow();
        List<TripAnswer> summer = List.of(FRANCE);
        int found = 0;
        for (long seed = 1; seed <= 12; seed++) {
            Built b = parent(seed, 30, summer);
            for (Trip t : b.chronicle().trips()) {
                found++;
                for (LocalDate d : dates(b, t)) {
                    boolean free = d.getDayOfWeek().getValue() >= DayOfWeek.SATURDAY.getValue()
                            || pack.publicHolidays(d.getYear()).contains(d)
                            || school.stream().anyMatch(h -> !d.isBefore(h.from()) && !d.isAfter(h.to()));
                    assertTrue(free, "a school day in a trip, seed " + seed + ": " + d);
                }
            }
        }
        assertTrue(found > 12, "trips: " + found);
    }

    @Test
    void everyTripHasItsBookingBefore() {
        for (long seed = 1; seed <= 10; seed++) {
            Built b = office(seed, 30, ANSWERS);
            for (Trip t : b.chronicle().trips()) {
                List<Event> bookings = b.of(EventKind.BOOKING).stream()
                        .filter(e -> e.origin().equals(Origin.event(t.id()))).toList();
                assertEquals(1, bookings.size(), "bookings of " + t);
                assertTrue(bookings.get(0).end().isBefore(t.from()), bookings.get(0) + " before " + t);
                assertTrue(bookings.get(0).start().isAfter(b.chronicle().phoneStart()), bookings.get(0).toString());
                assertTrue(b.chronicle().event(t.id()).map(e -> e.kind() == EventKind.TRIP).orElse(false), "no event " + t.id());
                List<Event> legs = b.of(EventKind.TRAVEL_LEG).stream()
                        .filter(e -> e.origin().equals(Origin.event(t.id()))).toList();
                assertEquals(2, legs.size(), "legs of " + t);
                assertEquals(t.from(), legs.get(0).start());
                assertEquals(t.to(), legs.get(1).end());
            }
        }
    }

    @Test
    void abroadDestinationsCarryTheirZone() {
        assertEquals(Set.of(ZoneId.of("Europe/Paris")),
                PlaceData.abroad("FR").stream().map(Destination::zone).collect(Collectors.toSet()));
        assertTrue(PlaceData.abroad("LU").size() >= 1, "the capital of a small country");
        assertEquals(List.of(), PlaceData.abroad("AQ"));
        Built b = office(4, 14, ANSWERS);
        for (Trip t : b.chronicle().trips()) {
            // an earlier year's trip is like the answer of the same month of the year
            String country = ANSWERS.stream().filter(a -> a.month().getMonth() == month(b, t).getMonth()).findFirst()
                    .orElseThrow().country();
            if (country.equals("NL")) {
                assertEquals(b.zone(), t.zone());
            } else {
                assertTrue(PlaceData.abroad(country).stream()
                        .anyMatch(d -> d.zone().equals(t.zone()) && d.name().equals(t.places().get(0).name())), t.toString());
            }
        }
    }

    @Test
    void tripItemsUseTheDestinationZone() {
        for (long seed = 1; seed <= 8; seed++) {
            Built b = office(seed, 14, List.of(INDONESIA));
            Trip t = b.chronicle().trips().get(0);
            assertTrue(t.zone().getId().startsWith("Asia/"), t.zone().toString());
            List<Event> there = b.chronicle().events().stream()
                    .filter(e -> !e.kind().marksDays() || e.kind() == EventKind.TRIP)
                    .filter(e -> !e.start().isBefore(t.from()) && !e.end().isAfter(t.to())).toList();
            there.forEach(e -> assertEquals(t.zone(), e.zone(), e.toString()));
            Set<String> places = t.places().stream().map(WorldPlace::id).collect(Collectors.toSet());
            List<Event> nights = there.stream().filter(e -> e.kind() == EventKind.SLEEP).toList();
            assertTrue(nights.size() >= 10, "nights away, seed " + seed + ": " + nights.size());
            for (Event night : nights.subList(1, nights.size() - 1)) {
                assertTrue(places.contains(night.placeId()), "a night at " + night.placeId());
                LocalTime bed = LocalTime.ofInstant(night.start(), t.zone());
                assertTrue(bed.getHour() >= 21 || bed.getHour() < 3, "bed at " + bed + " there, seed " + seed);
                assertEquals(Duration.ofDays(1).toDays(), ChronoUnit.DAYS.between(
                        LocalDate.ofInstant(night.start().minus(Duration.ofHours(6)), t.zone()),
                        LocalDate.ofInstant(night.end(), t.zone())), night.toString());
            }
        }
    }

    /** Whatever the trips, nobody is in two places at once, and no night is one a person could not have. */
    @Test
    void tripsNeverSpendTimeTwice() {
        List<String> found = new ArrayList<>();
        int trips = 0;
        for (int seed = 0; seed < 40; seed++) {
            JobKind job = JobKind.values()[seed % JobKind.values().length];
            int start = new int[] {5, 7, 14, 18, 22}[seed % 5];
            boolean works = job != JobKind.RETIRED && job != JobKind.NONE && job != JobKind.JOB_SEEKER;
            List<TripAnswer> answers = List.of(FRANCE, INDONESIA, HOME_COUNTRY,
                    new TripAnswer(YearMonth.of(2026, 5), 1, "BE", TravelMode.TRAIN));
            Built b = RoutineTest.build("utrecht", 20, 200 + seed, p -> {
                p.job(job).birthYear(1990).trips(answers);
                return works ? p.work(new WorkPattern(WEEK, LocalTime.of(start, 0), LocalTime.of((start + 8) % 24, 0),
                        false)) : p;
            });
            trips += b.chronicle().trips().size();
            Event last = null;
            for (Event e : b.chronicle().events()) {
                if (e.kind().marksDays() || e.kind() == EventKind.BOOKING) {
                    continue;
                }
                if (last != null && e.start().isBefore(last.end())) {
                    found.add(job + " from " + start + ", seed " + seed + ": " + last.kind() + " to " + last.end() + " overlaps "
                            + e.kind() + " from " + e.start());
                }
                long minutes = Duration.between(e.start(), e.end()).toMinutes();
                if (e.kind() == EventKind.SLEEP && (minutes < 120 || minutes > 14 * 60)) {
                    found.add(job + ", seed " + seed + ": a sleep of " + minutes + " min from " + e.start());
                }
                if (last == null || e.end().isAfter(last.end())) {
                    last = e;
                }
            }
            for (Event booking : b.of(EventKind.BOOKING)) {
                if (b.chronicle().events().stream().anyMatch(e -> !e.kind().marksDays() && e.kind() != EventKind.BOOKING
                        && e.start().isBefore(booking.end()) && e.end().isAfter(booking.start()))) {
                    found.add("seed " + seed + ": booked while busy " + booking.start());
                }
            }
        }
        assertTrue(trips > 100, "trips: " + trips);
        assertTrue(found.isEmpty(), found.size() + " found, the first: " + found.stream().limit(12).toList());
    }

    @Test
    void aTripTheJourneyDoesNotFitIsRefused() {
        IllegalArgumentException far = assertThrows(IllegalArgumentException.class,
                () -> office(1, 14, List.of(new TripAnswer(YearMonth.of(2026, 5), 1, "AU", TravelMode.CAR))));
        assertTrue(far.getMessage().startsWith("trips: "), far.getMessage());
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> office(1, 14, List.of(new TripAnswer(YearMonth.of(2026, 5), 3, "AQ", TravelMode.PLANE))));
        assertTrue(unknown.getMessage().startsWith("trips: "), unknown.getMessage());
        assertTrue(unknown.getMessage().contains("AQ"), unknown.getMessage());
    }

    @Test
    void sameDrawsSameTrips() {
        assertEquals(office(7, 30, ANSWERS).chronicle(), office(7, 30, ANSWERS).chronicle());
        Set<List<Trip>> seen = new HashSet<>();
        for (long seed = 1; seed <= 5; seed++) {
            seen.add(office(seed, 14, ANSWERS).chronicle().trips());
        }
        assertEquals(5, seen.size(), "the seed moves no trip");
    }
}
