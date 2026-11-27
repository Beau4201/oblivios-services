// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.chronicle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.oblivios.decoy.content.Seed;
import org.oblivios.decoy.content.chronicle.RoutineTest.Built;
import org.oblivios.decoy.content.origin.Origin;
import org.oblivios.decoy.content.world.Habits;
import org.oblivios.decoy.content.world.Tidiness;

class TracesTest {

    /** The persona's habits with every share and yes-or-no habit of the traces set to one value. */
    private static Habits habits(Habits h, boolean yes, double share, double offPhone) {
        Tidiness t = h.tidiness();
        return new Habits(h.labels(), h.savesInternational(), share, share, yes, yes, h.callBackShare(), h.unreadShare(),
                h.dnd(), h.fontScale(), h.renamesDevice(), h.contactGroups(), h.favourites(), h.notes(), h.rhythm(),
                h.rankExponent(), h.activity(),
                new Tidiness(t.unusedContacts(), t.unexplainedMessages(), t.fullTraceEvents(), offPhone), h.origin());
    }

    private static Chronicle chronicle(long seed, boolean yes, double share) {
        Built b = SmallEventsTest.life(seed);
        return SmallEventsTest.complete(b, SmallEventsTest.with(b.world(), habits(b.world().habits(), yes, share, 0.2)), seed);
    }

    private static List<Event> leaving(Chronicle c, Trace trace) {
        return c.events().stream().filter(e -> e.traces().contains(trace)).toList();
    }

    @Test
    void everyParcelHasAnOrder() {
        for (long seed = 1; seed <= 4; seed++) {
            Chronicle c = chronicle(seed, true, 0.5);
            List<Event> texts = leaving(c, Trace.PARCEL_SMS);
            assertTrue(!texts.isEmpty(), "parcel texts");
            for (Event e : texts) {
                assertEquals(EventKind.DELIVERY, e.kind(), e.toString());
                Event order = c.event(e.origin().id()).orElseThrow();
                assertEquals(EventKind.ORDER, order.kind(), e.toString());
                assertTrue(order.start().isBefore(e.start()), e.toString());
            }
        }
    }

    @Test
    void everyCodeHasALogin() {
        Chronicle c = chronicle(1, true, 0.5);
        List<Event> codes = leaving(c, Trace.CODE_SMS);
        assertTrue(!codes.isEmpty(), "codes");
        codes.forEach(e -> assertEquals(EventKind.LOGIN, e.kind(), e.toString()));
    }

    @Test
    void tracesFollowTheHabits() {
        Chronicle without = chronicle(2, false, 0);
        for (Trace t : List.of(Trace.PAYMENT_SMS, Trace.SCREENSHOT, Trace.CALENDAR, Trace.PHOTO)) {
            assertEquals(List.of(), leaving(without, t), t.name());
        }
        Chronicle with = chronicle(2, true, 1);
        for (Trace t : List.of(Trace.PAYMENT_SMS, Trace.SCREENSHOT, Trace.CALENDAR, Trace.PHOTO)) {
            assertTrue(!leaving(with, t).isEmpty(), t.name());
        }
        leaving(with, Trace.PAYMENT_SMS).forEach(e -> assertEquals(EventKind.PAYMENT, e.kind()));
    }

    @Test
    void alwaysTracesAlwaysExist() {
        Chronicle c = chronicle(3, false, 0);
        Map<EventKind, Trace> always = Map.of(EventKind.CALL, Trace.CALL, EventKind.MESSAGE, Trace.MESSAGE, EventKind.ORDER,
                Trace.MAIL, EventKind.BOOKING, Trace.MAIL);
        for (Event e : c.events()) {
            if (always.containsKey(e.kind())) {
                assertTrue(e.traces().contains(always.get(e.kind())), e.toString());
            }
            if (e.kind() == EventKind.DELIVERY) {
                assertTrue(e.traces().contains(Trace.PARCEL_SMS) || e.traces().contains(Trace.MAIL),
                        "a delivery nobody told of: " + e);
            }
        }
    }

    private static Event made(EventKind kind, String placeId) {
        Instant at = Instant.parse("2026-05-04T10:00:00Z");
        return new Event("e", kind, at, at, RoutineTest.NOW.atZone(java.time.ZoneId.of("Europe/Amsterdam")).getZone(), placeId,
                List.of(), Event.Layer.SMALL, Set.of(), Map.of(), Origin.world("self"));
    }

    @Test
    void someCausesAreOffThePhone() {
        Habits h = habits(SmallEventsTest.life(1).world().habits(), true, 0.5, 0.2);
        RandomGenerator r = Seed.of(4, 4).stream("off");
        for (EventKind kind : List.of(EventKind.ORDER, EventKind.LOGIN, EventKind.PAYMENT)) {
            int off = 0;
            for (int i = 0; i < 4_000; i++) {
                off += Traces.of(made(kind, ""), h, r).contains(Trace.VISIT) ? 0 : 1;
            }
            assertEquals(0.2, off / 4_000.0, 0.05, kind.name());
        }
        for (int i = 0; i < 200; i++) {
            assertTrue(!Traces.of(made(EventKind.PAYMENT, "shop"), h, r).contains(Trace.VISIT), "a payment in a shop");
        }
    }
}
