// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.oblivios.decoy.content.Seed;
import org.oblivios.decoy.content.pool.FilledConversation;

/**
 * The events a decoy gains while it was not used: calls and messages with its contacts between the latest event and
 * now. The count follows the gap at a fixed rate and is capped (60 messages, 40 calls), so a gap of months adds a
 * believable amount and never a flood; a clock that moved back, an empty gap or no contact adds nothing. No event
 * lies at or before the latest event or after now.
 */
public final class RollForward {
    static final int MAX_SMS = 60;
    static final int MAX_CALLS = 40;
    private static final double SMS_PER_DAY = 0.5;
    private static final double CALLS_PER_DAY = 0.25;
    private static final LocalTime WAKE = LocalTime.of(7, 30);
    private static final LocalTime SLEEP = LocalTime.of(22, 30);

    private RollForward() {}

    /**
     * {@code reserve} holds the rewritten conversations kept back at setup; the messages take their texts only from it,
     * whole conversations in their own order and with their own gaps, moved into waking hours.
     */
    public static RollForwardPlan plan(ContentSnapshot snapshot, Instant now, Seed fresh, List<FilledConversation> reserve) {
        Instant latest = snapshot.latestEvent();
        // a clock that moved back or an empty gap gives a gap of zero or less, hence no event
        if (snapshot.contacts().isEmpty()) {
            return new RollForwardPlan(List.of());
        }
        RandomGenerator r = fresh.stream("rollforward");
        ZoneId zone = snapshot.persona().inputs().zone();
        double gapDays = Duration.between(latest, now).toMinutes() / 1440.0;
        int calls = (int) Math.min(MAX_CALLS, Math.round(gapDays * CALLS_PER_DAY * (0.8 + 0.4 * r.nextDouble())));
        int sms = (int) Math.min(MAX_SMS, Math.round(gapDays * SMS_PER_DAY * (0.8 + 0.4 * r.nextDouble())));

        List<RollForwardEvent> events = new ArrayList<>();
        for (int i = 0; i < calls; i++) {
            Instant when = drawn(r, latest, now, zone);
            if (when == null) {
                continue;
            }
            int roll = r.nextInt(100);
            CallType type = roll < 40 ? CallType.INCOMING : roll < 80 ? CallType.OUTGOING : CallType.MISSED;
            int seconds = type == CallType.MISSED ? 0 : (int) Math.min(3600, 5 + Math.abs(r.nextGaussian()) * 240);
            events.add(new RollForwardEvent("CALL", when, contact(snapshot, r).id() + "|" + type + "|" + seconds));
        }
        Set<String> ids = new HashSet<>();
        snapshot.contacts().forEach(c -> ids.add(c.id()));
        for (FilledConversation c : reserve) {
            if (sms <= 0) {
                break;
            }
            if (!c.rewritten() || c.group() != null || c.contactIds().size() != 1 || !ids.contains(c.contactIds().get(0))) {
                continue;
            }
            Instant at = drawn(r, latest, now, zone);
            if (at == null) {
                continue;
            }
            Instant before = c.messages().get(0).at();
            for (SmsMessage m : c.messages()) {
                at = awake(at.plus(Duration.between(before, m.at())), zone);
                before = m.at();
                if (at.isAfter(now) || sms <= 0) {
                    break;
                }
                events.add(new RollForwardEvent("SMS", at, c.contactIds().get(0) + "|" + (m.incoming() ? "IN" : "OUT") + "|" + m.body()));
                sms--;
            }
        }
        events.sort(Comparator.comparing(RollForwardEvent::when).thenComparing(RollForwardEvent::kind).thenComparing(RollForwardEvent::detail));
        return new RollForwardPlan(events);
    }

    /** A waking-hours time strictly after {@code latest} and not after {@code now}; null when the draw landed outside the gap. */
    private static Instant drawn(RandomGenerator r, Instant latest, Instant now, ZoneId zone) {
        Instant when = Events.randomInstant(r, latest, now, zone, WAKE, SLEEP);
        return when.isAfter(latest) && !when.isAfter(now) ? when : null;
    }

    /** The same instant when it lies in waking hours (07:30 to 22:30), else the next 07:30. */
    private static Instant awake(Instant at, ZoneId zone) {
        ZonedDateTime local = at.atZone(zone);
        LocalTime time = local.toLocalTime();
        if (time.isBefore(WAKE)) {
            return local.with(WAKE).toInstant();
        }
        if (time.isAfter(SLEEP)) {
            return local.plusDays(1).with(WAKE).toInstant();
        }
        return at;
    }

    /** Favourites are chosen about four times as often as the others. */
    private static Contact contact(ContentSnapshot snapshot, RandomGenerator r) {
        List<Contact> contacts = snapshot.contacts();
        for (int attempt = 0; attempt < 8; attempt++) {
            Contact c = contacts.get(r.nextInt(contacts.size()));
            if (c.favourite() || r.nextInt(4) == 0) {
                return c;
            }
        }
        return contacts.get(r.nextInt(contacts.size()));
    }
}
