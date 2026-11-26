// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.oblivios.decoy.content.persona.PersonaProfile.Gender;

class NameDataTest {
    private static List<String> file(String background) throws IOException {
        try (InputStream in = NameData.class.getResourceAsStream("/org/oblivios/decoy/content/data/names/" + background + ".tsv");
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return r.lines().toList();
        }
    }

    /** Computed here from the file, apart from the class under test: name to (period start to share) for one sex. */
    private static Map<String, Map<Integer, Double>> shares(String background, String sex) throws IOException {
        Map<Integer, Map<String, Long>> periods = new HashMap<>();
        for (String line : file(background)) {
            String[] c = line.split("\t");
            if (!line.startsWith("#") && c[0].equals("given") && c[1].equals(sex)) {
                periods.computeIfAbsent(Integer.parseInt(c[2]), k -> new HashMap<>()).merge(c[4], Long.parseLong(c[5]), Long::sum);
            }
        }
        Map<String, Map<Integer, Double>> out = new HashMap<>();
        periods.forEach((start, names) -> {
            double total = names.values().stream().mapToLong(Long::longValue).sum();
            names.forEach((name, n) -> out.computeIfAbsent(name, k -> new HashMap<>()).put(start, n / total));
        });
        return out;
    }

    private static double cohort(Map<String, Map<Integer, Double>> shares, String name, int periodStart) {
        Map<Integer, Double> of = shares.get(name);
        return of.getOrDefault(periodStart, 0.0) / of.values().stream().mapToDouble(Double::doubleValue).max().orElseThrow();
    }

    @Test
    void givenNamesFollowSexAndPeriod() throws IOException {
        NameData nl = NameData.load("NL");
        Map<String, Map<Integer, Double>> female = shares("NL", "F");
        SplittableRandom r = new SplittableRandom(1985);
        Set<String> drawn = new HashSet<>();
        for (int i = 0; i < 5_000; i++) {
            String name = nl.given(Gender.FEMALE, 1985, r);
            drawn.add(name);
            assertTrue(nl.genderOf(name).map(g -> g == Gender.FEMALE).orElse(true), name);
            assertTrue(cohort(female, name, 1980) >= 0.05, name + " hardly occurs around 1985");
        }
        assertTrue(drawn.size() > 100, "names drawn: " + drawn.size());
        for (String name : female.keySet().stream().sorted().limit(300).toList()) {
            assertEquals(cohort(female, name, 1980), nl.cohortShare(name, Gender.FEMALE, 1985), 1e-9, name);
        }
    }

    @Test
    void genderOfNeedsNineTenthsOfOneSex() {
        NameData d = NameData.parse("XA", new StringReader("""
                # source: s
                # licence: l
                # version: v
                given\tF\t1990\t1999\tAa\t99
                given\tM\t1990\t1999\tAa\t1
                given\tF\t1990\t1999\tBb\t1
                given\tM\t1990\t1999\tBb\t99
                given\tF\t1990\t1999\tCc\t50
                given\tM\t1990\t1999\tCc\t50
                family\t-\t-\t-\tDd\t1
                """));
        assertEquals(Optional.of(Gender.FEMALE), d.genderOf("Aa"));
        assertEquals(Optional.of(Gender.MALE), d.genderOf("Bb"));
        assertEquals(Optional.empty(), d.genderOf("Cc"));
        assertEquals(Optional.empty(), d.genderOf("Unknown"));
        for (int i = 0; i < 200; i++) {
            assertFalse(d.given(Gender.FEMALE, 1995, new SplittableRandom(i)).equals("Bb"), "a man's name for a woman");
        }
    }

    @Test
    void aBirthOutsideEveryPeriodTakesTheNearest() {
        NameData d = NameData.parse("XA", new StringReader("""
                # source: s
                # licence: l
                # version: v
                given\tF\t1950\t1959\tOld\t10
                given\tF\t1990\t1999\tNew\t10
                family\t-\t-\t-\tDd\t1
                """));
        assertEquals("Old", d.given(Gender.FEMALE, 1900, new SplittableRandom(1)));
        assertEquals("New", d.given(Gender.FEMALE, 2020, new SplittableRandom(1)));
        assertEquals("Old", d.given(Gender.FEMALE, 1964, new SplittableRandom(1)));
    }

    @Test
    void familyNamesWithAGenderedFormFollowTheGender() {
        NameData d = NameData.parse("XA", new StringReader("""
                # source: s
                # licence: l
                # version: v
                given\tF\t1990\t1999\tAa\t1
                family\tF\t-\t-\tShe\t1
                family\tM\t-\t-\tHe\t1
                """));
        for (int i = 0; i < 20; i++) {
            assertEquals("She", d.family(Gender.FEMALE, new SplittableRandom(i)));
            assertEquals("He", d.family(Gender.MALE, new SplittableRandom(i)));
        }
    }

    @Test
    void malformedOrOverlappingDataIsRefused() {
        String head = "# source: s\n# licence: l\n# version: v\n";
        String good = "given\tF\t1990\t1999\tAa\t1\nfamily\t-\t-\t-\tDd\t1\n";
        NameData.parse("XA", new StringReader(head + good)); // each bad case adds one fault to this
        for (String fault : List.of("given\tF\t1995\t2004\tBb\t1\n", "given\tF\t2000\t2009\tBb\n",
                "given\tX\t2000\t2009\tBb\t1\n", "given\tF\t2000\t2009\tBb\t0\n", "given\tF\t2009\t2000\tBb\t1\n",
                "other\tF\t2000\t2009\tBb\t1\n", "family\t-\t-\t-\t \t1\n")) {
            assertThrows(IllegalStateException.class, () -> NameData.parse("XA", new StringReader(head + good + fault)), fault);
        }
        for (String text : List.of(head + "given\tF\t1990\t1999\tAa\t1\n", head + "family\t-\t-\t-\tDd\t1\n", good)) {
            assertThrows(IllegalStateException.class, () -> NameData.parse("XA", new StringReader(text)), text);
        }
    }

    @Test
    void backgroundWithoutDataIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> NameData.load("ZZ"));
        assertEquals("no name data for background ZZ", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> NameData.load("../NL"));
    }

    @Test
    void onlyACountryWithAWholePackageHasAFile() {
        assertEquals(Set.of("NL"), NameData.backgrounds());
        for (String bg : NameData.backgrounds()) {
            NameData d = NameData.load(bg);
            SplittableRandom r = new SplittableRandom(7);
            for (Gender g : Gender.values()) {
                for (int year : new int[] {1935, 1975, 2000, 2019}) {
                    assertFalse(d.given(g, year, r).isBlank(), bg + " " + g + " " + year);
                }
                assertFalse(d.family(g, r).isBlank(), bg);
            }
        }
    }

    @Test
    void everyFileHasSourceLicenceAndVersion() throws IOException {
        for (String bg : NameData.backgrounds()) {
            List<String> head = file(bg).subList(0, 3);
            assertTrue(head.get(0).startsWith("# source: "), bg + " " + head);
            assertTrue(head.get(1).startsWith("# licence: "), bg + " " + head);
            assertTrue(head.get(2).startsWith("# version: "), bg + " " + head);
        }
    }

    @Test
    void versionIsTheFilesVersionLine() throws IOException {
        for (String bg : NameData.backgrounds()) {
            assertEquals(file(bg).get(2).substring("# version: ".length()), NameData.load(bg).version(), bg);
        }
    }

    @Test
    void sameDrawsSameNames() {
        NameData nl = NameData.load("NL");
        List<String> first = new ArrayList<>();
        List<String> second = new ArrayList<>();
        for (List<String> out : List.of(first, second)) {
            SplittableRandom r = new SplittableRandom(11);
            for (int i = 0; i < 50; i++) {
                out.add(nl.given(Gender.MALE, 1960, r) + " " + nl.family(Gender.MALE, r));
            }
        }
        assertEquals(first, second);
    }
}
