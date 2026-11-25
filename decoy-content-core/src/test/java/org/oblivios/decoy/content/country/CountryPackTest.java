// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.country;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class CountryPackTest {

    /** The free mobile blocks of the ACM register, read 2026-11-25, in the register's own notation. */
    private static final String[] ACM_FREE_BLOCKS = {
        "062-5780000 t/m 062-5999999", "062-1010000 t/m 062-1099999", "062-1720000 t/m 062-1799999",
        "062-7660000 t/m 062-7739999", "062-7750000 t/m 062-7809999", "061-5970000 t/m 061-5999999",
    };

    private static boolean inAcmFreeBlock(String e164) {
        if (!e164.startsWith("+316") || e164.length() != 12) {
            return false;
        }
        long national = Long.parseLong(e164.substring(3)); // 6xxxxxxxx, the leading 0 dropped
        for (String block : ACM_FREE_BLOCKS) {
            String[] ends = block.split(" t/m ");
            long from = Long.parseLong(ends[0].replace("-", "")) ; // 0625780000
            long to = Long.parseLong(ends[1].replace("-", ""));
            if (national >= from && national <= to) {
                return true;
            }
        }
        return false;
    }

    /** A pack whose numbers have no verified range, as for a country the register was not read for. */
    private static final class NoRangePack implements CountryPack {
        public String code() { return "ZZ"; }
        public String givenName(RandomGenerator r) { return "Zed"; }
        public String familyName(RandomGenerator r) { return "Zeta"; }
        public PhoneNumber mobileNumber(RandomGenerator r) throws NoVerifiedRange { throw new NoVerifiedRange("none"); }
        public List<Sender> senders(SenderKind kind) { return List.of(new Sender("ZedBank", kind)); }
        public List<LocalDate> publicHolidays(int year) { return List.of(); }
        public List<String> homeWifiPatterns() { return List.of("ZED-####"); }
    }

    @Test
    void unknownCountryFallsBackToNeutral() {
        PackChoice c = Packs.forDevice("FR", ZoneId.of("Europe/Paris"), Locale.FRANCE);
        assertTrue(c.fellBack());
        assertSame(Packs.neutral(), c.pack());
    }

    @Test
    void countryWithoutVerifiedRangeFallsBackToNeutral() {
        PackChoice c = Packs.forDevice(List.of(new NoRangePack()), "ZZ", ZoneId.of("UTC"), Locale.ROOT);
        assertTrue(c.fellBack());
        assertSame(Packs.neutral(), c.pack());
    }

    @Test
    void knownCountryWithRangeIsChosenWithoutFallback() {
        PackChoice c = Packs.forDevice("NL", ZoneId.of("Europe/Amsterdam"), Locale.forLanguageTag("nl-NL"));
        assertFalse(c.fellBack());
        assertEquals("NL", c.pack().code());
    }

    @Test
    void noPackMixing() {
        List<CountryPack> all = List.of(Packs.neutral(), Packs.forDevice("NL", ZoneId.of("UTC"), Locale.ROOT).pack());
        for (SenderKind kind : SenderKind.values()) {
            if (kind == SenderKind.GLOBAL) {
                continue;
            }
            Set<Sender> seen = new HashSet<>();
            for (CountryPack p : all) {
                for (Sender s : p.senders(kind)) {
                    assertTrue(seen.add(s), "sender shared between packs: " + s);
                }
            }
        }
    }

    @Test
    void neutralHasNoLocalSenders() {
        for (SenderKind kind : SenderKind.values()) {
            if (kind == SenderKind.GLOBAL) {
                assertFalse(Packs.neutral().senders(kind).isEmpty());
            } else {
                assertTrue(Packs.neutral().senders(kind).isEmpty(), kind + " must be empty for the neutral pack");
            }
        }
    }

    @Test
    void neutralPackHasNoNumbersOfItsOwn() {
        assertThrows(NoVerifiedRange.class, () -> Packs.neutral().mobileNumber(new SplittableRandom(1)));
    }

    @Test
    void dutchNumbersAreInVerifiedRange() throws NoVerifiedRange {
        CountryPack nl = Packs.forDevice("NL", ZoneId.of("UTC"), Locale.ROOT).pack();
        SplittableRandom r = new SplittableRandom(42);
        Set<String> distinct = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            String n = nl.mobileNumber(r).e164();
            assertTrue(inAcmFreeBlock(n), "outside the free blocks: " + n);
            distinct.add(n);
        }
        assertTrue(distinct.size() > 4900, "numbers repeat too much: " + distinct.size());
    }

    @Test
    void dutchNumbersSpreadOverAllVerifiedBlocks() throws NoVerifiedRange {
        CountryPack nl = Packs.forDevice("NL", ZoneId.of("UTC"), Locale.ROOT).pack();
        SplittableRandom r = new SplittableRandom(7);
        Set<String> prefixes = new HashSet<>();
        for (int i = 0; i < 20000; i++) {
            prefixes.add(nl.mobileNumber(r).e164().substring(0, 7)); // +316xxx
        }
        assertTrue(prefixes.size() >= 4, "only " + prefixes.size() + " three-digit prefixes used");
    }

    @Test
    void dutchHolidaysInclude27April2026() {
        CountryPack nl = Packs.forDevice("NL", ZoneId.of("UTC"), Locale.ROOT).pack();
        assertTrue(nl.publicHolidays(2026).contains(LocalDate.of(2026, 4, 27)));
    }

    @Test
    void dutchHolidaysFollowEaster2026() {
        List<LocalDate> h = Packs.forDevice("NL", ZoneId.of("UTC"), Locale.ROOT).pack().publicHolidays(2026);
        assertTrue(h.contains(LocalDate.of(2026, 4, 3)), "Good Friday");
        assertTrue(h.contains(LocalDate.of(2026, 4, 6)), "Easter Monday");
        assertTrue(h.contains(LocalDate.of(2026, 5, 14)), "Ascension");
        assertTrue(h.contains(LocalDate.of(2026, 5, 25)), "Whit Monday");
        assertTrue(h.contains(LocalDate.of(2026, 12, 25)), "Christmas Day");
    }

    @Test
    void kingsDayMovesToTheSaturdayWhenTheTwentySeventhIsASunday() {
        List<LocalDate> h = Packs.forDevice("NL", ZoneId.of("UTC"), Locale.ROOT).pack().publicHolidays(2025);
        assertTrue(h.contains(LocalDate.of(2025, 4, 26)));
        assertFalse(h.contains(LocalDate.of(2025, 4, 27)));
    }

    @Test
    void namesVaryAcrossDraws() {
        CountryPack nl = Packs.forDevice("NL", ZoneId.of("UTC"), Locale.ROOT).pack();
        SplittableRandom r = new SplittableRandom(3);
        Set<String> given = new HashSet<>();
        Set<String> family = new HashSet<>();
        for (int i = 0; i < 400; i++) {
            given.add(nl.givenName(r));
            family.add(nl.familyName(r));
        }
        assertTrue(given.size() >= 40, "given names: " + given.size());
        assertTrue(family.size() >= 40, "family names: " + family.size());
    }

    @Test
    void homeWifiPatternsExist() {
        assertFalse(Packs.forDevice("NL", ZoneId.of("UTC"), Locale.ROOT).pack().homeWifiPatterns().isEmpty());
        assertFalse(Packs.neutral().homeWifiPatterns().isEmpty());
    }
}
