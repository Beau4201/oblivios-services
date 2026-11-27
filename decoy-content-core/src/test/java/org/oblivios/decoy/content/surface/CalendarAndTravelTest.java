// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.oblivios.decoy.content.persona.PersonaProfile;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.PersonaInputs;
import org.oblivios.decoy.content.Seed;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.country.Packs;
import org.oblivios.decoy.content.lang.LanguagePack;
import org.oblivios.decoy.content.story.MonthCount;
import org.oblivios.decoy.content.story.Place;
import org.oblivios.decoy.content.story.PlaceKind;
import org.oblivios.decoy.content.story.Timeline;
import org.oblivios.decoy.content.story.VolumeCounts;
import org.oblivios.decoy.content.story.VolumeModel;

class CalendarAndTravelTest {
    private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");

    private static CountryPack nl() {
        return Packs.forDevice("NL", ZoneId.of("Europe/Amsterdam"), Locale.ROOT).pack();
    }

    private record Setup(Timeline timeline, People people, CalendarPlan calendar, TravelPlan travel) {}

    private static Setup setup(long n) {
        Persona p = org.oblivios.decoy.content.TestSetup.derive(Seed.of(n, 1),
                PersonaProfile.open(new PersonaInputs("Sam", "Example", "Utrecht", "NL", ZoneId.of("Europe/Amsterdam"), Locale.forLanguageTag("nl-NL"))), 2026);
        List<MonthCount> months = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            months.add(new MonthCount(YearMonth.of(2026, 10).minusMonths(29 - i), 60 + i * 5, 10, 8, 1_000_000L));
        }
        Timeline t = Timeline.generate(p, VolumeModel.fromCounts(new VolumeCounts(months), org.oblivios.decoy.content.Draws.of(Seed.of(n, 2))), nl(), NOW, org.oblivios.decoy.content.Draws.of(Seed.of(n, 3)));
        People people = PeopleSurface.generate(p, t, nl(), org.oblivios.decoy.content.Draws.of(Seed.of(n, 4)));
        return new Setup(t, people, CalendarSurface.generate(people, t, nl(), LanguagePack.of("nl"), org.oblivios.decoy.content.Draws.of(Seed.of(n, 5))), TravelSurface.generate(t, nl(), LanguagePack.of("nl"), org.oblivios.decoy.content.Draws.of(Seed.of(n, 6))));
    }

    @Test
    void birthdaysMatchContacts() {
        for (long n = 0; n < 10; n++) {
            Setup s = setup(n);
            for (Contact c : s.people().contacts()) {
                if (c.birthday() == null) {
                    continue;
                }
                boolean found = s.calendar().events().stream().anyMatch(e -> e.yearly()
                        && e.start().atZone(ZoneOffset.UTC).getMonth() == c.birthday().getMonth()
                        && e.start().atZone(ZoneOffset.UTC).getDayOfMonth() == c.birthday().getDayOfMonth()
                        && e.title().contains(c.name()));
                assertTrue(found, "no yearly event for the birthday of " + c.name());
            }
        }
    }

    @Test
    void holidaysOfCountryPresent() {
        Setup s = setup(1);
        int year = LocalDate.ofInstant(NOW, ZoneOffset.UTC).getYear();
        LocalDate phoneStart = LocalDate.ofInstant(s.timeline().phoneStart(), s.timeline().zone());
        LocalDate horizon = LocalDate.ofInstant(NOW, ZoneOffset.UTC).plusDays(CalendarSurface.HORIZON_DAYS);
        for (int y = year - 2; y <= year + 1; y++) {
            for (LocalDate h : nl().publicHolidays(y)) {
                if (h.isAfter(horizon) || h.isBefore(phoneStart)) {
                    continue;
                }
                boolean found = s.calendar().events().stream().anyMatch(e -> !e.yearly()
                        && e.start().equals(h.atStartOfDay(ZoneOffset.UTC).toInstant())
                        && e.title().equals(org.oblivios.decoy.content.lang.LanguagePack.of("nl")
                                .text(nl().holidayKey(h).orElseThrow())));
                assertTrue(found, "no event for the holiday " + h);
            }
        }
    }

    @Test
    void alarmsWeekdayPattern() {
        for (long n = 0; n < 20; n++) {
            Setup s = setup(n);
            Set<DayOfWeek> weekdays = EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
            boolean found = false;
            for (Alarm a : s.calendar().alarms()) {
                if (a.days().equals(weekdays)) {
                    long minutesBefore = Duration.between(a.time(), s.timeline().wakeTime()).toMinutes();
                    found |= minutesBefore >= 0 && minutesBefore <= 30;
                }
            }
            assertTrue(found, "no weekday alarm 0 to 30 minutes before the wake time " + s.timeline().wakeTime());
        }
    }

    @Test
    void tripPlacesHaveCoordinatesInCountryOrAbroad() {
        for (long n = 0; n < 20; n++) {
            Setup s = setup(n);
            assertFalse(s.travel().trips().isEmpty());
            for (Trip t : s.travel().trips()) {
                assertFalse(t.places().isEmpty());
                for (Place p : t.places()) {
                    assertTrue(p.kind() == PlaceKind.TRAVEL);
                    assertTrue(Math.abs(p.lat()) <= 90 && Math.abs(p.lon()) <= 180, "coordinates " + p);
                    assertFalse(p.lat() == 0 && p.lon() == 0, "null island");
                }
            }
        }
    }

    @Test
    void boardingPassOnlyForFlightTrips() {
        for (long n = 0; n < 30; n++) {
            Setup s = setup(n);
            for (String id : s.travel().boardingPassTrips()) {
                Trip trip = s.travel().trips().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
                assertTrue(trip.flight(), "boarding pass for a trip without a flight: " + id);
            }
        }
    }

    @Test
    void tripsStayInsideTheStoryAndDoNotOverlap() {
        for (long n = 0; n < 20; n++) {
            Setup s = setup(n);
            List<Trip> trips = new ArrayList<>(s.travel().trips());
            trips.sort(Comparator.comparing(Trip::from));
            for (int i = 0; i < trips.size(); i++) {
                Trip t = trips.get(i);
                assertTrue(t.from().isBefore(t.to()));
                assertFalse(t.from().isBefore(s.timeline().phoneStart()), "trip before the phone start");
                assertFalse(t.to().isAfter(s.timeline().now()), "trip after now");
                long days = Duration.between(t.from(), t.to()).toDays();
                assertTrue(days >= 1 && days <= 16, "trip of " + days + " days");
                if (i > 0) {
                    assertFalse(t.from().isBefore(trips.get(i - 1).to()), "overlapping trips");
                }
            }
        }
    }

    @Test
    void upcomingEntriesExistWithinTheHorizon() {
        // a calendar with nothing ahead is a tell; ahead means appointments and holidays, never past the horizon
        for (long n = 0; n < 20; n++) {
            Setup s = setup(n);
            Instant limit = NOW.plus(Duration.ofDays(CalendarSurface.HORIZON_DAYS));
            List<CalEvent> ahead = s.calendar().events().stream().filter(e -> !e.yearly() && e.start().isAfter(NOW)).toList();
            // a holiday sits at midnight UTC, an appointment in the waking hours
            long appointments = ahead.stream().filter(e -> !e.start().equals(e.start().truncatedTo(java.time.temporal.ChronoUnit.DAYS))).count();
            assertTrue(appointments >= 3 && appointments <= 8, "upcoming appointments " + appointments + " for seed " + n);
            for (CalEvent e : ahead) {
                assertFalse(e.start().isAfter(limit), "beyond the horizon: " + e);
            }
            assertFalse(s.calendar().events().stream().anyMatch(e -> e.yearly() && e.start().isAfter(NOW)));
        }
    }

    @Test
    void noEventBeyondTheHorizonOrBeforeThePhoneStart() {
        for (long n = 0; n < 15; n++) {
            Setup s = setup(n);
            for (CalEvent e : s.calendar().events()) {
                assertFalse(e.start().isAfter(s.timeline().now().plus(Duration.ofDays(CalendarSurface.HORIZON_DAYS))), "event beyond the horizon: " + e);
                if (!e.yearly()) {
                    assertFalse(e.start().isBefore(s.timeline().phoneStart().minus(Duration.ofDays(1))), "event before the phone start: " + e);
                }
            }
            for (Note note : s.calendar().notes()) {
                assertFalse(note.created().isAfter(s.timeline().now()));
                assertFalse(note.created().isBefore(s.timeline().phoneStart()));
            }
            assertTrue(s.calendar().events().size() > 40, "events " + s.calendar().events().size());
            assertTrue(s.calendar().notes().size() >= 3);
        }
    }

    @Test
    void sameSeedSamePlans() {
        Setup a = setup(8);
        Setup b = setup(8);
        assertEquals(a.calendar(), b.calendar());
        assertEquals(a.travel(), b.travel());
    }
}
