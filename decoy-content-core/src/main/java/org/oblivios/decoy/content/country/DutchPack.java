// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.country;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * The Netherlands. Numbers come only from the blocks that the ACM public number register showed as free
 * ("vrij") for mobile numbers on 2026-11-25 (docs/research/decoy-content-sources.md). A free block can be
 * allocated later: re-read the register before relying on these.
 */
public final class DutchPack implements CountryPack {
    /** National numbers without the leading 0, inclusive: 062-5780000 t/m 062-5999999 is 625780000..625999999. */
    private static final long[][] FREE_BLOCKS = {
        {625780000L, 625999999L}, {621010000L, 621099999L}, {621720000L, 621799999L},
        {627660000L, 627739999L}, {627750000L, 627809999L}, {615970000L, 615999999L},
    };

    // shortcut: hand-written lists of common names, no dataset; upgrade to a CC0 dataset (Wikidata) when coverage matters.
    private static final List<String> GIVEN = List.of(
            "Daan", "Sem", "Lucas", "Finn", "Levi", "Milan", "Jesse", "Luuk", "Thijs", "Bram", "Sam", "Noah", "Tim",
            "Ruben", "Jasper", "Joost", "Niels", "Stijn", "Wouter", "Pieter", "Maarten", "Rick", "Kevin", "Bas", "Gijs",
            "Emma", "Sophie", "Julia", "Anna", "Sara", "Lotte", "Fleur", "Eva", "Noor", "Mila", "Tess", "Lisa", "Femke",
            "Anouk", "Iris", "Merel", "Sanne", "Maud", "Nina", "Roos", "Bente", "Esmee", "Marloes", "Karin", "Ingrid");
    private static final List<String> FAMILY = List.of(
            "de Jong", "Jansen", "de Vries", "van den Berg", "van Dijk", "Bakker", "Janssen", "Visser", "Smit",
            "Meijer", "de Boer", "Mulder", "de Groot", "Bos", "Vos", "Peters", "Hendriks", "van Leeuwen", "Dekker",
            "Brouwer", "de Wit", "Dijkstra", "Smits", "de Graaf", "van der Meer", "van der Linden", "Kok", "Jacobs",
            "de Haan", "Vermeulen", "van den Heuvel", "van der Veen", "van den Broek", "de Bruijn", "de Bruin",
            "van der Heijden", "Schouten", "van Beek", "Willems", "van Vliet", "van de Ven", "Hoekstra", "Maas",
            "Verhoeven", "Koster", "Prins", "Blom", "Huisman", "Peeters", "Kuipers");

    private static final List<Sender> BANKS = senders(SenderKind.BANK, "ING", "Rabobank", "ABN AMRO", "SNS", "Knab");
    private static final List<Sender> PARCEL = senders(SenderKind.PARCEL, "PostNL", "DHL", "DPD", "GLS");
    private static final List<Sender> OPERATORS = senders(SenderKind.OPERATOR, "KPN", "Odido", "VodafoneZiggo", "Simyo");
    private static final List<Sender> SHOPS = senders(SenderKind.SHOP, "bol", "Coolblue", "Albert Heijn", "HEMA", "Jumbo");

    private static List<Sender> senders(SenderKind kind, String... names) {
        List<Sender> out = new ArrayList<>();
        for (String n : names) {
            out.add(new Sender(n, kind));
        }
        return List.copyOf(out);
    }

    @Override
    public String code() {
        return "NL";
    }

    @Override
    public String givenName(RandomGenerator r) {
        return GIVEN.get(r.nextInt(GIVEN.size()));
    }

    @Override
    public String familyName(RandomGenerator r) {
        return FAMILY.get(r.nextInt(FAMILY.size()));
    }

    @Override
    public PhoneNumber mobileNumber(RandomGenerator r) {
        long total = 0;
        for (long[] b : FREE_BLOCKS) {
            total += b[1] - b[0] + 1;
        }
        long pick = r.nextLong(total);
        for (long[] b : FREE_BLOCKS) {
            long size = b[1] - b[0] + 1;
            if (pick < size) {
                return new PhoneNumber("+31" + (b[0] + pick));
            }
            pick -= size;
        }
        throw new IllegalStateException("unreachable: pick is below the total");
    }

    @Override
    public List<Sender> senders(SenderKind kind) {
        return switch (kind) {
            case BANK -> BANKS;
            case PARCEL -> PARCEL;
            case OPERATOR -> OPERATORS;
            case SHOP -> SHOPS;
            case GLOBAL -> NeutralPack.GLOBAL;
        };
    }

    @Override
    public List<LocalDate> publicHolidays(int year) {
        LocalDate easter = easter(year);
        LocalDate kings = LocalDate.of(year, 4, 27);
        if (kings.getDayOfWeek() == DayOfWeek.SUNDAY) {
            kings = kings.minusDays(1);
        }
        return List.of(
                LocalDate.of(year, 1, 1), easter.minusDays(2), easter, easter.plusDays(1), kings,
                LocalDate.of(year, 5, 5), easter.plusDays(39), easter.plusDays(49), easter.plusDays(50),
                LocalDate.of(year, 12, 25), LocalDate.of(year, 12, 26));
    }

    private static final List<String> HOLIDAY_NAMES = List.of(
            "Nieuwjaarsdag", "Goede Vrijdag", "Eerste Paasdag", "Tweede Paasdag", "Koningsdag", "Bevrijdingsdag",
            "Hemelvaartsdag", "Eerste Pinksterdag", "Tweede Pinksterdag", "Eerste Kerstdag", "Tweede Kerstdag");

    @Override
    public String holidayName(LocalDate date) {
        int i = publicHolidays(date.getYear()).indexOf(date);
        return i < 0 ? "Feestdag" : HOLIDAY_NAMES.get(i);
    }

    @Override
    public List<String> homeWifiPatterns() {
        return List.of("Ziggo#######", "KPN######", "Odido-######", "FRITZ!Box ####", "TP-LINK_####");
    }

    /** Easter Sunday by the anonymous Gregorian algorithm. */
    static LocalDate easter(int y) {
        int a = y % 19, b = y / 100, c = y % 100, d = b / 4, e = b % 4, f = (b + 8) / 25, g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30, i = c / 4, k = c % 4, l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(y, month, day);
    }
}
