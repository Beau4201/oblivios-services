// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.oblivios.decoy.content.check.RuleFixtures.ago;
import static org.oblivios.decoy.content.check.RuleFixtures.bare;
import static org.oblivios.decoy.content.check.RuleFixtures.contact;
import static org.oblivios.decoy.content.check.RuleFixtures.number;
import static org.oblivios.decoy.content.check.RuleFixtures.people;
import static org.oblivios.decoy.content.check.RuleFixtures.persona;
import static org.oblivios.decoy.content.check.RuleFixtures.planted;
import static org.oblivios.decoy.content.check.RuleFixtures.trip;
import static org.oblivios.decoy.content.check.RuleFixtures.with;

import static org.oblivios.decoy.content.check.RuleFixtures.chronicle;
import static org.oblivios.decoy.content.check.RuleFixtures.event;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.oblivios.decoy.content.check.rules.GOrigin;
import org.oblivios.decoy.content.chronicle.EventKind;
import org.oblivios.decoy.content.origin.Origin;
import org.oblivios.decoy.content.origin.Sourced;
import org.oblivios.decoy.content.persona.PersonaProfile.JobKind;
import org.oblivios.decoy.content.plan.ContentPlan;
import org.oblivios.decoy.content.surface.CalEvent;
import org.oblivios.decoy.content.surface.CalendarPlan;
import org.oblivios.decoy.content.surface.Call;
import org.oblivios.decoy.content.surface.CallType;
import org.oblivios.decoy.content.surface.Contact;
import org.oblivios.decoy.content.surface.Relation;
import org.oblivios.decoy.content.surface.TravelPlan;

class OriginRulesTest {
    /** A test item that names an origin; no story of today holds one. */
    private record Stated(Origin origin) implements Sourced {}

    private static int count(ContentPlan p, String id) {
        return planted(p).only(id).size();
    }

    /** A night from {@code from} to {@code to} and one outgoing call of the persona at {@code call}. */
    private static ContentPlan night(String from, String to, String call) {
        return with(bare(), chronicle(event("night", EventKind.SLEEP, Instant.parse(from), Instant.parse(to))),
                people(List.of(), List.of(new Call(number(1), Instant.parse(call), 60, CallType.OUTGOING)), List.of()));
    }

    @Test
    void callWhileAsleepIsReported() {
        assertEquals(1, count(night("2026-11-20T22:00:00Z", "2026-11-21T06:00:00Z", "2026-11-21T02:00:00Z"), "T-ASLEEP"));
    }

    @Test
    void callAtTheEdgeOfSleepPasses() {
        assertEquals(0, count(night("2026-11-20T22:00:00Z", "2026-11-21T06:00:00Z", "2026-11-20T22:10:00Z"), "T-ASLEEP"));
        assertEquals(0, count(night("2026-11-20T22:00:00Z", "2026-11-21T06:00:00Z", "2026-11-21T07:00:00Z"), "T-ASLEEP"));
    }

    /** An office persona, Monday to Friday, whose chronicle holds one event of {@code kind} on {@code day}. */
    private static ContentPlan dayWith(EventKind kind, String day) {
        return with(bare(persona(b -> b.job(JobKind.OFFICE))), chronicle(event("e", kind, Instant.parse(day + "T07:00:00Z"),
                Instant.parse(day + "T15:00:00Z"))));
    }

    @Test
    void workOnAPublicHolidayIsReported() {
        assertEquals(1, count(dayWith(EventKind.WORK, "2026-04-27"), "T-DAY-KIND"));
        assertEquals(0, count(dayWith(EventKind.WORK, "2026-04-28"), "T-DAY-KIND"));
    }

    @Test
    void workOnADayWithoutWorkIsReported() {
        assertEquals(1, count(dayWith(EventKind.WORK, "2026-11-21"), "T-DAY-KIND"));
    }

    @Test
    void schoolInTheSummerHolidayIsReported() {
        assertEquals(1, count(dayWith(EventKind.BRING_CHILD, "2026-07-27"), "T-DAY-KIND"));
        assertEquals(0, count(dayWith(EventKind.BRING_CHILD, "2026-11-24"), "T-DAY-KIND"));
    }

    @Test
    void workOnALeaveDayIsReported() {
        Instant start = Instant.parse("2026-11-24T07:00:00Z");
        Instant end = Instant.parse("2026-11-24T15:00:00Z");
        assertEquals(1, count(with(bare(persona(b -> b.job(JobKind.OFFICE))),
                chronicle(event("work", EventKind.WORK, start, end), event("leave", EventKind.LEAVE, start, end))), "T-DAY-KIND"));
    }

    @Test
    void itemWithoutOriginIsReported() {
        ContentPlan p = with(bare(), people(List.of(), List.of(new Call(number(1), ago(2, 0), 30, CallType.INCOMING)), List.of()));
        List<Violation> found = planted(p).only("G-ORIGIN");
        assertTrue(found.stream().anyMatch(v -> v.detail().contains("Call")), "found " + found);
    }

    @Test
    void unresolvedOriginIsReported() {
        Origin nowhere = Origin.world("person.nobody");
        List<Violation> out = new ArrayList<>();
        GOrigin.judge(List.of(new Stated(nowhere)), PlanStory.of(bare()), out);
        assertEquals(1, out.size());
        assertEquals(List.of(nowhere), out.get(0).facts());
    }

    @Test
    void acquaintancesCalledMostIsReported() {
        List<Contact> contacts = new ArrayList<>();
        contacts.add(contact("c0", "Partner Example", number(0), Relation.PARTNER));
        for (int i = 1; i <= 12; i++) {
            contacts.add(contact("c" + i, "Person" + i + " Example", number(i), Relation.ACQUAINTANCE));
        }
        List<Call> toAcquaintance = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            toAcquaintance.add(new Call(number(12), ago(1 + i, i), 60, CallType.INCOMING));
        }
        List<Violation> found = planted(with(bare(), people(contacts, toAcquaintance, List.of()))).only("C-CLOSENESS");
        assertTrue(found.stream().anyMatch(v -> v.detail().contains("acquaintances")), "found " + found);
        assertTrue(found.stream().anyMatch(v -> v.detail().contains("partner")), "found " + found);
        // each of the ten closest called this month, the partner most: no closeness violation
        List<Call> close = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            for (int k = 0; k <= (i == 0 ? 5 : 1); k++) {
                close.add(new Call(number(i), ago(1 + k, i), 60, CallType.OUTGOING));
            }
        }
        close.add(new Call(number(12), ago(3, 1), 60, CallType.INCOMING));
        assertEquals(0, count(with(bare(), people(contacts, close, List.of())), "C-CLOSENESS"));
    }

    @Test
    void appointmentDuringATripIsReported() {
        CalendarPlan during = new CalendarPlan(List.of(new CalEvent("Meeting", ago(20, 0), false)), List.of(), List.of());
        assertEquals(1, count(with(bare(), trip(), during), "T-AWAY"));
        CalendarPlan after = new CalendarPlan(List.of(new CalEvent("Meeting", ago(10, 0), false)), List.of(), List.of());
        assertEquals(0, count(with(bare(), trip(), after), "T-AWAY"));
    }

    @Test
    void onlineShopAsAPlaceIsReported() {
        String online = Checks.packOf(bare().persona()).onlineShops().get(0);
        TravelPlan shop = RuleFixtures.shopTrip(online);
        TravelPlan market = RuleFixtures.shopTrip("Market Example");
        // the shops of today's timeline are drawn from the pack's senders, so the base may hold an online one already
        int before = count(bare(), "S-PHYSICAL");
        assertEquals(before + 1, count(with(bare(), shop), "S-PHYSICAL"));
        assertEquals(before, count(with(bare(), market), "S-PHYSICAL"));
    }

    @Test
    void workPlaceWithoutAJobIsReported() {
        assertEquals(1, count(bare(persona(b -> b.job(JobKind.RETIRED))), "O-PRESUMES"));
        assertEquals(0, count(bare(persona(b -> b.job(JobKind.OFFICE))), "O-PRESUMES"));
    }
}
