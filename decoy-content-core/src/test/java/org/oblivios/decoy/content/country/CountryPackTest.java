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
import org.junit.jupiter.api.Test;

class CountryPackTest {

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
    void countryWithoutNumberDataFallsBackToNeutral() {
        CountryPack noRange = pack("code", "ZZ", "senders.BANK", "ZedBank");
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
    void everyPackFileIsListedWithItsFamilyRules() throws Exception {
        java.nio.file.Path dir = java.nio.file.Path.of(Packs.class.getResource("/org/oblivios/decoy/content/data/country/XX.properties").toURI())
                .getParent();
        java.util.Set<String> files = new java.util.TreeSet<>();
        try (java.util.stream.Stream<java.nio.file.Path> list = java.nio.file.Files.list(dir)) {
            list.map(f -> f.getFileName().toString()).filter(f -> f.matches("[A-Z]{2}\\.properties")).forEach(f -> files.add(f.substring(0, 2)));
        }
        assertEquals(files, new java.util.TreeSet<>(Packs.all().stream().map(CountryPack::code).toList()));
        for (CountryPack pack : Packs.all()) {
            assertEquals(3, pack.childNameForms().size(), pack.code());
            assertEquals(4, pack.marriedNameForms().size(), pack.code());
        }
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
    void homeWifiPatternsExist() {
        assertFalse(nl().homeWifiPatterns().isEmpty());
        assertFalse(Packs.neutral().homeWifiPatterns().isEmpty());
    }
}
