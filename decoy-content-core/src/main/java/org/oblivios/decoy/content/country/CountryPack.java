// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.country;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Everything country specific that the generators need, read from a data file per country ({@link Packs#load}).
 * One plan uses one pack and never mixes two.
 */
public interface CountryPack {
    String code();

    /** The language of the country's service texts (BCP 47 tag); empty when the pack has none, as the neutral pack. */
    String language();

    String givenName(RandomGenerator r);

    String familyName(RandomGenerator r);

    /** Every given name of the pack; the gate of the conversation pool keeps them out of the pool. */
    List<String> givenNames();

    List<String> familyNames();

    /** A number from a verified free or fictional block; throws when the pack has none. */
    PhoneNumber mobileNumber(RandomGenerator r) throws NoVerifiedRange;

    List<Sender> senders(SenderKind kind);

    List<LocalDate> publicHolidays(int year);

    /** The local name of a public holiday of {@link #publicHolidays}; empty for an ordinary day or a holiday without a name. */
    Optional<String> holidayName(LocalDate date);

    /** Templates of home Wi-Fi names; a {@code #} stands for one random digit. */
    List<String> homeWifiPatterns();

    /** The centre of a city of the country, by name, ignoring case and surrounding spaces. */
    Optional<double[]> city(String name);

    /** The centre of the capital; throws {@link IllegalArgumentException} when the pack has no place data. */
    double[] capital();

    /** A money amount written the country's way. */
    String amount(int value);

    /** The minimum and maximum number of group threads per decoy. */
    int[] mmsGroups();

    /** Web sites of the browser history: the global ones first, then the country's own. */
    List<String> sites();

    /** Cities of a trip inside the country; empty when the pack has none, so every trip is a flight. */
    List<City> domesticTrips();

    /** Cities of a trip abroad. */
    List<City> abroadTrips();
}
