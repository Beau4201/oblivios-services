// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check.rules;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.oblivios.decoy.content.chronicle.Event;
import org.oblivios.decoy.content.chronicle.EventKind;
import org.oblivios.decoy.content.chronicle.Trace;
import org.oblivios.decoy.content.check.Checks;
import org.oblivios.decoy.content.check.OriginRule;
import org.oblivios.decoy.content.check.PhoneViews;
import org.oblivios.decoy.content.check.PlanStory;
import org.oblivios.decoy.content.check.Violation;
import org.oblivios.decoy.content.country.Sender;
import org.oblivios.decoy.content.country.SenderKind;
import org.oblivios.decoy.content.origin.Origin;
import org.oblivios.decoy.content.surface.MailMessage;
import org.oblivios.decoy.content.surface.SmsMessage;
import org.oblivios.decoy.content.surface.SmsThread;

/**
 * {@code S-CAUSE}: a code without a log-in, a parcel without an order, a receipt without a purchase. In the story, a
 * trace on an event that cannot leave it; in the views, a text of a parcel carrier without a delivery of that carrier
 * within 2 days, a text with a code of 6 digits without a log-in of that sender in the 10 minutes before, and a mail of
 * a shop without an order or payment there in the day before. A view item names no event yet, so it is matched by its
 * sender and time; the spans and what counts as a code are {@code [assumption]}. One violation per kind of fault.
 */
public final class SCause implements OriginRule {
    private static final Duration PARCEL = Duration.ofDays(2);
    private static final Duration CODE_AFTER = Duration.ofMinutes(10);
    private static final Duration RECEIPT_AFTER = Duration.ofDays(1);
    private static final Pattern CODE = Pattern.compile("(?<!\\d)\\d{6}(?!\\d)");

    @Override
    public String id() {
        return "S-CAUSE";
    }

    @Override
    public void check(PhoneViews v, PlanStory story, List<Violation> out) {
        List<Event> events = story.chronicle().events();
        Map<String, Event> byId = events.stream().collect(Collectors.toMap(Event::id, Function.identity(), (a, b) -> a));
        Map<String, Object[]> faults = new LinkedHashMap<>();
        for (Event e : events) {
            Event cause = e.origin().kind() == Origin.Kind.EVENT ? byId.get(e.origin().id()) : null;
            EventKind of = cause == null ? null : cause.kind();
            if (e.traces().contains(Trace.PARCEL_SMS) && !(e.kind() == EventKind.DELIVERY && of == EventKind.ORDER)) {
                fault(faults, "parcel texts of events that no order caused", e);
            }
            if (e.traces().contains(Trace.CODE_SMS) && e.kind() != EventKind.LOGIN) {
                fault(faults, "codes of events that are no log-in", e);
            }
            if (e.traces().contains(Trace.PAYMENT_SMS)
                    && !(e.kind() == EventKind.PAYMENT && (of == EventKind.ORDER || of == EventKind.SHOP_VISIT))) {
                fault(faults, "payment texts of events without a purchase", e);
            }
        }
        List<String> shops = Checks.packOf(story.persona()).senders(SenderKind.SHOP).stream().map(Sender::name).toList();
        for (SmsThread t : v.people().threads()) {
            if (t.senderKind() == null) {
                continue;
            }
            for (SmsMessage m : t.messages()) {
                if (!m.incoming()) {
                    continue;
                }
                if (t.senderKind() == SenderKind.PARCEL) {
                    if (events.stream().noneMatch(e -> e.traces().contains(Trace.PARCEL_SMS)
                            && of(e, t.address(), m.at().minus(PARCEL), m.at().plus(PARCEL)))) {
                        fault(faults, "parcel texts without a delivery", t.address() + " at " + m.at());
                    }
                } else if (t.senderKind() != SenderKind.SHOP && CODE.matcher(m.body()).find()
                        && events.stream().noneMatch(e -> e.traces().contains(Trace.CODE_SMS)
                                && of(e, t.address(), m.at().minus(CODE_AFTER), m.at()))) {
                    fault(faults, "codes without a log-in", t.address() + " at " + m.at());
                }
            }
        }
        for (MailMessage m : v.mail().messages()) {
            if (shops.contains(m.from()) && events.stream()
                    .noneMatch(e -> (e.kind() == EventKind.ORDER || e.kind() == EventKind.PAYMENT)
                            && of(e, m.from(), m.date().minus(RECEIPT_AFTER), m.date()))) {
                fault(faults, "mails of a shop without a purchase", m.from() + " at " + m.date());
            }
        }
        faults.forEach((what, seen) -> out.add(new Violation(id(), seen[0] + " " + what + ", first: " + Checks.shown(seen[1]))));
    }

    private static boolean of(Event e, String company, Instant from, Instant to) {
        return company.equals(e.details().get("company")) && !e.start().isBefore(from) && !e.start().isAfter(to);
    }

    private static void fault(Map<String, Object[]> faults, String what, Object first) {
        Object[] seen = faults.computeIfAbsent(what, k -> new Object[] {0, first});
        seen[0] = (Integer) seen[0] + 1;
    }
}
