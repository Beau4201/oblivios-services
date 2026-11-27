// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.chronicle;

import java.util.EnumSet;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.oblivios.decoy.content.world.Habits;

/**
 * What an event leaves on the phone, by the habits of the persona. Some traces always exist, and some exist only after
 * their cause: a code text belongs to a log-in, a parcel text to a delivery, which only an order makes, and a payment
 * text to a payment. The cause of a log-in, order, booking or payment online is a visit in the browser, unless it was
 * made on another device. The shares that no habit holds are {@code [assumption]}.
 */
public final class Traces {
    private static final double CODE_BY_TEXT = 0.5;
    private static final double PARCEL_BY_TEXT = 0.6;
    private static final double PARCEL_BY_MAIL = 0.5;
    private static final double CONFIRMATION_KEPT = 0.2;

    private Traces() {}

    public static Set<Trace> of(Event e, Habits h, RandomGenerator r) {
        Set<Trace> out = EnumSet.noneOf(Trace.class);
        switch (e.kind()) {
            case CALL -> out.add(Trace.CALL);
            case MESSAGE -> out.add(Trace.MESSAGE);
            case ORDER, BOOKING -> {
                out.add(Trace.MAIL);
                if (h.screenshots() && r.nextDouble() < CONFIRMATION_KEPT) {
                    out.add(Trace.SCREENSHOT);
                }
                cause(out, h, r);
            }
            case LOGIN -> {
                if (r.nextDouble() < CODE_BY_TEXT) {
                    out.add(Trace.CODE_SMS);
                }
                cause(out, h, r);
            }
            case DELIVERY -> {
                if (r.nextDouble() < PARCEL_BY_TEXT) {
                    out.add(Trace.PARCEL_SMS);
                }
                if (out.isEmpty() || r.nextDouble() < PARCEL_BY_MAIL) {
                    out.add(Trace.MAIL);
                }
            }
            case PAYMENT -> {
                if (h.paymentTexts()) {
                    out.add(Trace.PAYMENT_SMS);
                }
                // a payment with a place was made there, one without was made online
                if (e.placeId().isEmpty()) {
                    cause(out, h, r);
                }
            }
            case ACTIVITY, MEETUP, BIRTHDAY, TRIP -> {
                calendar(out, h, r);
                if (r.nextDouble() < h.photoShare()) {
                    out.add(Trace.PHOTO);
                }
            }
            case APPOINTMENT -> calendar(out, h, r);
            default -> {
                // routine, days off and errands leave nothing of their own
            }
        }
        return out;
    }

    private static void calendar(Set<Trace> out, Habits h, RandomGenerator r) {
        if (r.nextDouble() < h.calendarShare()) {
            out.add(Trace.CALENDAR);
        }
    }

    private static void cause(Set<Trace> out, Habits h, RandomGenerator r) {
        if (r.nextDouble() >= h.tidiness().offPhoneCauses()) {
            out.add(Trace.VISIT);
        }
    }
}
