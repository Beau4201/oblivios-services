// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.country;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import java.util.Properties;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class CountryPackTest {

    /** The free mobile blocks of the ACM register, read 2026-11-25, as first and last number of each block. */
    private static final String[] ACM_FREE_BLOCKS = {
        "062-5780000 to 062-5999999", "062-1010000 to 062-1099999", "062-1720000 to 062-1799999",
        "062-7660000 to 062-7739999", "062-7750000 to 062-7809999", "061-5970000 to 061-5999999",
    };

    private static boolean inAcmFreeBlock(String e164) {
        if (!e164.startsWith("+316") || e164.length() != 12) {
            return false;
        }
        long national = Long.parseLong(e164.substring(3)); // 6xxxxxxxx, the leading 0 dropped
        for (String block : ACM_FREE_BLOCKS) {
            String[] ends = block.split(" to ");
            long from = Long.parseLong(ends[0].replace("-", "")); // 0625780000
            long to = Long.parseLong(ends[1].replace("-", ""));
            if (national >= from && national <= to) {
                return true;
            }
        }
        return false;
    }

    private static CountryPack nl() {
        return Packs.load("NL").orElseThrow();
    }

    private static DataPack pack(String... keyValues) {
        Properties p = new Properties();
        for (int i = 0; i < keyValues.length; i += 2) {
            p.setProperty(keyValues[i], keyValues[i + 1]);
        }
        return new DataPack(p);
    }

    @Test
    void unknownCountryFallsBackToNeutral() {
        PackChoice c = Packs.forDevice("FR", ZoneId.of("Europe/Paris"), Locale.FRANCE);
        assertTrue(c.fellBack());
        assertSame(Packs.neutral(), c.pack());
    }

    @Test
    void countryWithoutVerifiedRangeFallsBackToNeutral() {
        CountryPack noRange = pack("code", "ZZ", "given", "Zed", "family", "Zeta", "senders.BANK", "ZedBank");
        PackChoice c = Packs.forDevice(List.of(noRange), "ZZ", ZoneId.of("UTC"), Locale.ROOT);
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
        List<CountryPack> all = List.of(Packs.neutral(), nl());
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
    void neutralHasNoVerifiedRange() {
        assertThrows(NoVerifiedRange.class, () -> Packs.load("XX").get().mobileNumber(new SplittableRandom(2)));
    }

    @Test
    void nlNumbersAreInVerifiedRange() throws NoVerifiedRange {
        CountryPack nl = nl();
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
    void nlNumbersSpreadOverAllVerifiedBlocks() throws NoVerifiedRange {
        CountryPack nl = nl();
        SplittableRandom r = new SplittableRandom(7);
        Set<String> prefixes = new HashSet<>();
        for (int i = 0; i < 20000; i++) {
            prefixes.add(nl.mobileNumber(r).e164().substring(0, 7)); // +316xxx
        }
        assertTrue(prefixes.size() >= 4, "only " + prefixes.size() + " three-digit prefixes used");
    }

    @Test
    void blocksAreParsedInclusive() throws NoVerifiedRange {
        CountryPack p = pack("code", "ZZ", "mobile.prefix", "+31", "mobile.blocks", "600000000-600000001");
        SplittableRandom r = new SplittableRandom(5);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            seen.add(p.mobileNumber(r).e164());
        }
        assertEquals(Set.of("+31600000000", "+31600000001"), seen);
    }

    @Test
    void unknownKeyIsRejected() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> pack("code", "ZZ", "colour", "blue"));
        assertTrue(e.getMessage().contains("colour"), e.getMessage());
    }

    @Test
    void nlHolidaysInclude27April2026() {
        assertTrue(nl().publicHolidays(2026).contains(LocalDate.of(2026, 4, 27)));
    }

    @Test
    void nlHolidaysFollowEaster2026() {
        List<LocalDate> h = nl().publicHolidays(2026);
        assertTrue(h.contains(LocalDate.of(2026, 4, 3)), "Good Friday");
        assertTrue(h.contains(LocalDate.of(2026, 4, 6)), "Easter Monday");
        assertTrue(h.contains(LocalDate.of(2026, 5, 14)), "Ascension");
        assertTrue(h.contains(LocalDate.of(2026, 5, 25)), "Whit Monday");
        assertTrue(h.contains(LocalDate.of(2026, 12, 25)), "Christmas Day");
    }

    @Test
    void sundayBackRuleMovesToTheSaturday() {
        List<LocalDate> h = nl().publicHolidays(2025);
        assertTrue(h.contains(LocalDate.of(2025, 4, 26)));
        assertFalse(h.contains(LocalDate.of(2025, 4, 27)));
    }

    @Test
    void holidayNameIsEmptyForAnOrdinaryDay() {
        assertTrue(nl().holidayName(LocalDate.of(2026, 3, 10)).isEmpty());
        assertTrue(nl().holidayName(LocalDate.of(2026, 4, 27)).isPresent());
    }

    @Test
    void cityLookupIgnoresCaseAndSpaces() {
        double[] plain = nl().city("den haag").orElseThrow();
        assertArrayEquals(plain, nl().city(" Den Haag ").orElseThrow());
        assertTrue(nl().city("Atlantis").isEmpty());
        assertTrue(nl().city(null).isEmpty());
    }

    @Test
    void neutralHasNoCapital() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Packs.neutral().capital());
        assertEquals("no place data for country XX", e.getMessage());
    }

    @Test
    void everyPackFileIsListedWithItsNames() throws Exception {
        java.nio.file.Path dir = java.nio.file.Path.of(Packs.class.getResource("/org/oblivios/decoy/content/data/country/XX.properties").toURI())
                .getParent();
        java.util.Set<String> files = new java.util.TreeSet<>();
        try (java.util.stream.Stream<java.nio.file.Path> list = java.nio.file.Files.list(dir)) {
            list.map(f -> f.getFileName().toString()).filter(f -> f.matches("[A-Z]{2}\\.properties")).forEach(f -> files.add(f.substring(0, 2)));
        }
        assertEquals(files, new java.util.TreeSet<>(Packs.all().stream().map(CountryPack::code).toList()));
        assertTrue(nl().givenNames().contains("Sanne"));
        assertTrue(nl().familyNames().contains("de Jong"));
        assertTrue(Packs.neutral().givenNames().contains("Alex"));
    }

    @Test
    void everyPackInheritsTheGlobalSenders() {
        assertEquals(Packs.load("XX").get().senders(SenderKind.GLOBAL), nl().senders(SenderKind.GLOBAL));
        assertFalse(nl().senders(SenderKind.GLOBAL).isEmpty());
    }

    @Test
    void mmsGroupRangeIsRead() {
        assertArrayEquals(new int[] {0, 2}, nl().mmsGroups());
        assertArrayEquals(new int[] {0, 1}, Packs.neutral().mmsGroups());
    }

    @Test
    void amountUsesThePattern() {
        assertTrue(nl().amount(12).contains("12"));
        assertEquals("12", Packs.neutral().amount(12));
    }

    @Test
    void packLanguageIsRead() {
        assertEquals("nl", nl().language());
        assertEquals("", Packs.neutral().language());
    }

    @Test
    void namesVaryAcrossDraws() {
        CountryPack nl = nl();
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
        assertFalse(nl().homeWifiPatterns().isEmpty());
        assertFalse(Packs.neutral().homeWifiPatterns().isEmpty());
    }
}
