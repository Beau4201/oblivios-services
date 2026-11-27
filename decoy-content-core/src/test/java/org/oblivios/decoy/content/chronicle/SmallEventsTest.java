// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.chronicle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.oblivios.decoy.content.Draws;
import org.oblivios.decoy.content.Seed;
import org.oblivios.decoy.content.chronicle.RoutineTest.Built;
import org.oblivios.decoy.content.data.DataSet;
import org.oblivios.decoy.content.data.Spreads;
import org.oblivios.decoy.content.origin.Origin;
import org.oblivios.decoy.content.persona.PersonaProfile.JobKind;
import org.oblivios.decoy.content.world.Activity;
import org.oblivios.decoy.content.world.Habits;
import org.oblivios.decoy.content.world.World;
import org.oblivios.decoy.content.world.WorldPerson;

class SmallEventsTest {
    private static final Spreads SPREADS = Spreads.load("NL");

    static Built life(long seed) {
        return RoutineTest.build("utrecht", 12, seed, p -> p.job(JobKind.OFFICE).trips(List.of()));
    }

    static Chronicle complete(Built b, World w, long seed) {
        return ChronicleBuilder.complete(b.chronicle(), List.of(), w, DataSet.of("NL"), Draws.of(Seed.of(seed, 99)));
    }

    static World with(World w, Habits h) {
        return new World(w.self(), w.people(), w.pets(), w.places(), w.choices(), h);
    }

    private static World active(World w, double calls, double texts, double browsing) {
        Habits h = w.habits();
        Activity a = h.activity();
        Activity next = new Activity(calls, texts, a.photoBurst(), a.notes(), browsing, a.callLength());
        return with(w, new Habits(h.labels(), h.savesInternational(), h.calendarShare(), h.photoShare(), h.screenshots(),
                h.paymentTexts(), h.callBackShare(), h.unreadShare(), h.dnd(), h.fontScale(), h.renamesDevice(),
                h.contactGroups(), h.favourites(), h.notes(), h.rhythm(), h.rankExponent(), next, h.tidiness(), h.origin()));
    }

    private static List<Event> small(Chronicle c, EventKind kind) {
        return c.events().stream().filter(e -> e.layer() == Event.Layer.SMALL && e.kind() == kind).toList();
    }

    private static double weeks(Chronicle c) {
        return ChronoUnit.DAYS.between(c.phoneStart(), c.now()) / 7.0;
    }

    @Test
    void completeKeepsTheFrame() {
        Built b = life(1);
        Chronicle c = complete(b, b.world(), 1);
        assertEquals(b.chronicle().events().stream().map(Event::id).toList(),
                c.events().stream().filter(e -> e.layer() != Event.Layer.SMALL).map(Event::id).toList());
        assertEquals(c.events().size(), c.events().stream().map(Event::id).distinct().count(), "ids are unique");
        List<Event> sorted = c.events().stream().sorted(Comparator.comparing(Event::start).thenComparing(Event::id)).toList();
        assertEquals(sorted, c.events());
        assertTrue(c.events().stream().allMatch(e -> e.start().isBefore(c.now())));
    }

    @Test
    void smallEventsFitFreeTime() {
        for (long seed = 1; seed <= 4; seed++) {
            Built b = life(seed);
            Chronicle c = complete(b, b.world(), seed);
            Availability frame = b.chronicle().availability();
            List<Event> visits = small(c, EventKind.SHOP_VISIT);
            assertFalse(visits.isEmpty(), "shop visits");
            Instant before = Instant.MIN;
            for (Event v : visits) {
                assertTrue(frame.free(v.start(), v.end()), "a visit over the routine: " + v);
                assertFalse(v.start().isBefore(before), "two visits at once: " + v);
                assertTrue(b.world().place(v.placeId()).isPresent(), v.toString());
                before = v.end();
            }
            Availability all = c.availability();
            for (EventKind kind : List.of(EventKind.CALL, EventKind.MESSAGE, EventKind.ORDER, EventKind.LOGIN)) {
                List<Event> events = small(c, kind);
                assertFalse(events.isEmpty(), kind.name());
                for (Event e : events) {
                    assertTrue(all.free(e.start()), "while busy: " + e);
                }
            }
        }
    }

    @Test
    void closePeopleGetMoreCalls() {
        int near = 0;
        int far = 0;
        for (long seed = 1; seed <= 4; seed++) {
            Built b = life(seed);
            Chronicle c = complete(b, b.world(), seed);
            List<WorldPerson> reachable = b.world().people().stream().filter(p -> p.phone() != null)
                    .sorted(Comparator.comparingInt(WorldPerson::closeness)).toList();
            Map<String, Long> calls = small(c, EventKind.CALL).stream()
                    .collect(Collectors.groupingBy(e -> e.people().get(0), Collectors.counting()));
            assertTrue(calls.keySet().stream().allMatch(id -> reachable.stream().anyMatch(p -> p.id().equals(id))));
            for (int i = 0; i < 5; i++) {
                near += calls.getOrDefault(reachable.get(i).id(), 0L).intValue();
                far += calls.getOrDefault(reachable.get(reachable.size() - 1 - i).id(), 0L).intValue();
            }
        }
        assertTrue(near > 3 * far, "closest five " + near + ", farthest five " + far);
    }

    @Test
    void someContactsAreNeverCalled() {
        Built b = life(2);
        Chronicle c = complete(b, b.world(), 2);
        long reachable = b.world().people().stream().filter(p -> p.phone() != null).count();
        long reached = c.events().stream().filter(e -> e.kind() == EventKind.CALL || e.kind() == EventKind.MESSAGE)
                .flatMap(e -> e.people().stream()).distinct().count();
        long unused = Math.round(reachable * b.world().habits().tidiness().unusedContacts());
        assertTrue(reached <= reachable - unused, reached + " of " + reachable + " reached, " + unused + " left unused");
    }

    @Test
    void ordersAreDeliveredAfterwards() {
        Built b = life(3);
        Chronicle c = complete(b, b.world(), 3);
        List<Event> orders = small(c, EventKind.ORDER);
        List<Event> deliveries = small(c, EventKind.DELIVERY);
        assertFalse(deliveries.isEmpty());
        for (Event d : deliveries) {
            Event order = c.event(d.origin().id()).orElseThrow();
            assertEquals(EventKind.ORDER, order.kind(), d.toString());
            assertTrue(d.start().isAfter(order.start()), d.toString());
            assertTrue(Duration.between(order.start(), d.start()).toDays() <= 6, order + " then " + d);
            assertFalse(d.details().get("company").isEmpty());
        }
        for (Event o : orders) {
            long n = deliveries.stream().filter(d -> d.origin().equals(Origin.event(o.id()))).count();
            if (o.start().isBefore(c.now().minus(Duration.ofDays(6)))) {
                assertEquals(1, n, o.toString());
            }
            assertTrue(small(c, EventKind.PAYMENT).stream().anyMatch(p -> p.origin().equals(Origin.event(o.id()))),
                    "an order without a payment: " + o);
        }
    }

    @Test
    void everyShopVisitIsPaidInTheShop() {
        Built b = life(3);
        Chronicle c = complete(b, b.world(), 3);
        for (Event v : small(c, EventKind.SHOP_VISIT)) {
            Event paid = small(c, EventKind.PAYMENT).stream().filter(p -> p.origin().equals(Origin.event(v.id()))).findFirst()
                    .orElseThrow();
            assertEquals(v.placeId(), paid.placeId());
            assertFalse(paid.start().isBefore(v.start()));
            assertFalse(paid.start().isAfter(v.end()));
        }
    }

    @Test
    void countsLieInTheSpreadRanges() {
        for (long seed = 1; seed <= 6; seed++) {
            Built b = life(seed);
            Chronicle c = complete(b, active(b.world(), 1, 1, 1), seed);
            within(small(c, EventKind.CALL).size() / weeks(c), "small.calls.perWeek", seed);
            within(small(c, EventKind.MESSAGE).size() / weeks(c), "small.messages.perWeek", seed);
            within(small(c, EventKind.SHOP_VISIT).size() / weeks(c), "small.shopVisits.perWeek", seed);
            within(small(c, EventKind.ORDER).size() / (weeks(c) * 12 / 52), "small.orders.perMonth", seed);
            within(small(c, EventKind.LOGIN).size() / (weeks(c) * 12 / 52), "small.logins.perMonth", seed);
        }
    }

    private static void within(double found, String key, long seed) {
        double low = SPREADS.value(key + ".low");
        double high = SPREADS.value(key + ".high");
        // the days raise and lower the habit, so the mean may leave the range by a quarter
        assertTrue(found >= low * 0.75 && found <= high * 1.25, "seed " + seed + " " + key + ": " + found);
    }

    @Test
    void amountsFollowThePersonasActivity() {
        Built b = life(4);
        Chronicle little = complete(b, active(b.world(), 0.5, 0.5, 0.5), 4);
        Chronicle much = complete(b, active(b.world(), 2, 2, 2), 4);
        for (EventKind kind : List.of(EventKind.CALL, EventKind.MESSAGE, EventKind.ORDER, EventKind.LOGIN)) {
            double ratio = (double) small(much, kind).size() / small(little, kind).size();
            assertTrue(ratio > 2.5 && ratio < 5.5, kind + ": " + ratio);
        }
    }

    @Test
    void sameDrawsSameSmallEvents() {
        Built b = life(5);
        assertEquals(complete(b, b.world(), 5), complete(b, b.world(), 5));
    }

    private static double burstiness(List<Instant> times) {
        List<Instant> sorted = times.stream().sorted().toList();
        double[] gaps = new double[sorted.size() - 1];
        double sum = 0;
        for (int i = 0; i < gaps.length; i++) {
            gaps[i] = Duration.between(sorted.get(i), sorted.get(i + 1)).toMillis() / 1000.0;
            sum += gaps[i];
        }
        double mean = sum / gaps.length;
        double squares = 0;
        for (double g : gaps) {
            squares += (g - mean) * (g - mean);
        }
        double sd = Math.sqrt(squares / gaps.length);
        return (sd - mean) / (sd + mean);
    }

    @Test
    void smallEventsComeInBursts() {
        Built b = life(6);
        Chronicle c = complete(b, b.world(), 6);
        List<Event> events = new ArrayList<>(small(c, EventKind.CALL));
        events.addAll(small(c, EventKind.MESSAGE));
        Map<LocalDate, Long> perDay = events.stream().collect(
                Collectors.groupingBy(e -> LocalDate.ofInstant(e.start(), b.zone()), TreeMap::new, Collectors.counting()));
        Availability free = c.availability();
        double[] independent = new double[100];
        for (int set = 0; set < independent.length; set++) {
            RandomGenerator r = Seed.of(set, 7).stream("independent");
            List<Instant> drawn = new ArrayList<>();
            perDay.forEach((day, n) -> {
                for (int i = 0; i < n; i++) {
                    drawn.addAll(Bursts.times(1, day, b.zone(), b.world().habits().rhythm(), free, r));
                }
            });
            independent[set] = burstiness(drawn);
        }
        java.util.Arrays.sort(independent);
        double found = burstiness(events.stream().map(Event::start).toList());
        assertTrue(found > independent[94], "burstiness " + found + ", 95th percentile of draws " + independent[94]);
    }

    @Test
    void burstsKeepToFreeTime() {
        Built b = life(7);
        Availability free = b.chronicle().availability();
        RandomGenerator r = Seed.of(7, 7).stream("bursts");
        LocalDate first = LocalDate.of(2026, 3, 20);
        long wholeSeconds = 0;
        long all = 0;
        for (LocalDate day = first; day.isBefore(first.plusDays(20)); day = day.plusDays(1)) {
            List<Instant> times = Bursts.times(30, day, b.zone(), b.world().habits().rhythm(), free, r);
            assertEquals(30, times.size(), day.toString());
            assertEquals(times.stream().sorted().distinct().toList(), times);
            for (Instant t : times) {
                assertTrue(free.free(t), day + " " + t);
                assertEquals(day, LocalDate.ofInstant(t, b.zone()));
                wholeSeconds += t.getNano() == 0 ? 1 : 0;
                all++;
            }
        }
        assertTrue(wholeSeconds < all / 20, "instants carry milliseconds: " + wholeSeconds + " of " + all);
    }
}
