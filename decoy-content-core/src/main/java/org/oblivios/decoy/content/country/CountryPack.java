// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.country;

import java.time.LocalDate;
import java.util.List;
import java.util.random.RandomGenerator;

/** Everything country specific that the generators need. One plan uses one pack and never mixes two. */
public interface CountryPack {
    String code();

    String givenName(RandomGenerator r);

    String familyName(RandomGenerator r);

    /** A number from a verified free or fictional block; throws when the pack has none. */
    PhoneNumber mobileNumber(RandomGenerator r) throws NoVerifiedRange;

    List<Sender> senders(SenderKind kind);

    List<LocalDate> publicHolidays(int year);

    /** Templates of home Wi-Fi names; a {@code #} stands for one random digit. */
    List<String> homeWifiPatterns();
}
