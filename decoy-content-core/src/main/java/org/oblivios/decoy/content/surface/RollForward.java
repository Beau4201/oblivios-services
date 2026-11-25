// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.random.RandomGenerator;
import org.oblivios.decoy.content.Seed;

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

    private RollForward() {}

    public static RollForwardPlan plan(ContentSnapshot snapshot, Instant now, Seed fresh) {
        Instant latest = snapshot.latestEvent();
        // a clock that moved back or an empty gap gives a gap of zero or less, hence no event
        if (snapshot.contacts().isEmpty()) {
            return new RollForwardPlan(List.of());
        }
        RandomGenerator r = fresh.stream("rollforward");
        ZoneId zone = snapshot.persona().inputs().zone();
        boolean dutch = "nl".equals(snapshot.persona().inputs().locale().getLanguage());
        List<String> firstNames = snapshot.contacts().stream().map(c -> c.name().split(" ")[0]).distinct().toList();
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
        for (int i = 0; i < sms; i++) {
            Instant when = drawn(r, latest, now, zone);
            if (when == null) {
                continue;
            }
            events.add(new RollForwardEvent("SMS", when,
                    contact(snapshot, r).id() + "|" + (r.nextBoolean() ? "IN" : "OUT") + "|" + SmsText.message(dutch, r, firstNames, List.of())));
        }
        events.sort(Comparator.comparing(RollForwardEvent::when).thenComparing(RollForwardEvent::kind).thenComparing(RollForwardEvent::detail));
        return new RollForwardPlan(events);
    }

    /** A waking-hours time strictly after {@code latest} and not after {@code now}; null when the draw landed outside the gap. */
    private static Instant drawn(RandomGenerator r, Instant latest, Instant now, ZoneId zone) {
        Instant when = Events.randomInstant(r, latest, now, zone, LocalTime.of(7, 30), LocalTime.of(22, 30));
        return when.isAfter(latest) && !when.isAfter(now) ? when : null;
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
