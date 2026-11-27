// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.chronicle;

public enum EventKind {
    WORK, SCHOOL, SLEEP, COMMUTE, BRING_CHILD, FETCH_CHILD, ACTIVITY, PUBLIC_HOLIDAY, SCHOOL_HOLIDAY, LEAVE, SICK, BIRTHDAY,
    TRIP, TRAVEL_LEG, BOOKING, APPOINTMENT, ERRAND, SHOP_VISIT, ORDER, DELIVERY, LOGIN, PAYMENT, CALL, MESSAGE, MEETUP, CHANGE;

    /** Whether an event of this kind marks days and leaves the persona's time open. */
    public boolean marksDays() {
        return this == PUBLIC_HOLIDAY || this == SCHOOL_HOLIDAY || this == LEAVE || this == SICK || this == BIRTHDAY;
    }
}
