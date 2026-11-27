// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.country;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything country specific that the generators need, read from a data file per country ({@link Packs#load}).
 * One plan uses one pack and never mixes two.
 */
public interface CountryPack {
    String code();

    /** The language of the country's service texts (BCP 47 tag); empty when the pack has none, as the neutral pack. */
    String language();

    /** The weight of each form of a child's family name, empty when the pack has no family rules. */
    Map<NameForm.Child, Integer> childNameForms();

    Map<NameForm.Married, Integer> marriedNameForms();

    List<Sender> senders(SenderKind kind);

    /** The supermarket chains the setup offers; empty when the pack has none. */
    List<String> supermarkets();

    /** The names of the shop senders that sell only online, so no place of a story bears them; a subset of the shops. */
    List<String> onlineShops();

    List<LocalDate> publicHolidays(int year);

    /** The key of the name of a public holiday of {@link #publicHolidays}; empty for an ordinary day. */
    Optional<String> holidayKey(LocalDate date);

    /** Templates of home Wi-Fi names; a {@code #} stands for one random digit. */
    List<String> homeWifiPatterns();

    /** A money amount written the country's way. */
    String amount(int value);

    /** The minimum and maximum number of group threads per decoy. */
    int[] mmsGroups();

    /** Web sites of the browser history: the global ones first, then the country's own. */
    List<String> sites();
}
