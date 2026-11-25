// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.oblivios.decoy.content.Seed;
import org.oblivios.decoy.content.lang.LanguagePack;

class SmsTextTest {
    private static final List<String> NAMES = List.of("Emma", "Daan", "Lotte", "Bram", "Femke", "Joost", "Noor", "Stijn");
    private static final List<String> PLACES = List.of("Cafe de Jong", "Jumbo", "Cafe Bakker");
    private static final Pattern TIME = Pattern.compile("\\b\\d{2}:\\d{2}\\b");
    private static final Pattern MINUTES = Pattern.compile("(?<![:\\d])\\d+ \\p{L}+");
    private static final Pattern WORD = Pattern.compile("\\p{L}+");

    private static List<LanguagePack> packs() {
        return LanguagePack.available().stream().sorted().map(LanguagePack::of).toList();
    }

    private static List<String> draw(LanguagePack lang, List<String> places, int count, long seed) {
        var r = Seed.of(seed, 1).stream("sms");
        return java.util.stream.IntStream.range(0, count).mapToObj(i -> SmsText.message(lang, r, NAMES, places)).toList();
    }

    private static boolean namesADay(String text) {
        for (LanguagePack p : packs()) {
            for (String d : p.list("weekdays")) {
                if (Pattern.compile("(?i)\\b" + Pattern.quote(d) + "\\b").matcher(text).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int slotsIn(String text, List<String> places) {
        int slots = 0;
        slots += NAMES.stream().anyMatch(text::contains) ? 1 : 0;
        slots += places.stream().anyMatch(text::contains) ? 1 : 0;
        slots += namesADay(text) ? 1 : 0;
        slots += TIME.matcher(text).find() ? 1 : 0;
        slots += MINUTES.matcher(text).find() ? 1 : 0;
        return slots;
    }

    private static int words(String text) {
        return text.trim().split("\\s+").length;
    }

    private static Set<String> wordsOf(List<String> texts) {
        Set<String> out = new HashSet<>();
        for (String t : texts) {
            Matcher m = WORD.matcher(t.replaceAll("\\{[a-z]+}", " "));
            while (m.find()) {
                out.add(m.group().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    @Test
    void everyLongMessageCarriesAtLeastTwoSlotsOfThePersonasWorld() {
        for (LanguagePack lang : packs()) {
            for (String m : draw(lang, PLACES, 3000, 1)) {
                if (words(m) >= 4) {
                    assertTrue(slotsIn(m, PLACES) >= 2, "a fixed sentence: " + m);
                }
            }
        }
    }

    @Test
    void noTokenIsLeftUnresolvedAndNoPlaceIsInventedWithoutPlaces() {
        for (LanguagePack lang : packs()) {
            for (String m : draw(lang, List.of(), 3000, 2)) {
                assertFalse(m.contains("{") || m.contains("}"), m);
                assertFalse(PLACES.stream().anyMatch(m::contains), m);
            }
        }
    }

    @Test
    void aboutAQuarterAreShortUniversalReplies() {
        for (LanguagePack lang : packs()) {
            List<String> messages = draw(lang, PLACES, 4000, 3);
            long shortOnes = messages.stream().filter(m -> words(m) <= 2).count();
            assertTrue(shortOnes >= 800 && shortOnes <= 1400, "short replies " + shortOnes + " of 4000");
            assertTrue(messages.stream().filter(m -> words(m) <= 2).allMatch(m -> slotsIn(m, PLACES) == 0));
        }
    }

    @Test
    void textsAreVariedNotAFixedList() {
        for (LanguagePack lang : packs()) {
            Set<String> distinct = new HashSet<>(draw(lang, PLACES, 2000, 4));
            assertTrue(distinct.size() > 800, "distinct texts " + distinct.size() + " of 2000");
        }
    }

    @Test
    void languagesAreNotMixed() {
        for (LanguagePack lang : packs()) {
            List<String> own = new ArrayList<>(lang.list("sms.short"));
            own.addAll(lang.list("sms.templates"));
            own.addAll(lang.list("weekdays"));
            own.addAll(NAMES);
            own.addAll(PLACES);
            Set<String> allowed = wordsOf(own);
            for (String m : draw(lang, PLACES, 2000, 5)) {
                for (String w : wordsOf(List.of(m))) {
                    assertTrue(allowed.contains(w), lang.tag() + " message has a foreign word '" + w + "': " + m);
                }
            }
        }
    }

    @Test
    void withoutNamesNoMessageNeedsOne() {
        var r = Seed.of(8, 1).stream("sms");
        List<LanguagePack> packs = packs();
        for (int i = 0; i < 3000; i++) {
            String m = SmsText.message(packs.get(i % packs.size()), r, List.of(), PLACES);
            assertFalse(m.contains("{") || NAMES.stream().anyMatch(m::contains), m);
        }
    }

    @Test
    void timesInAMessageAreInWakingHours() {
        for (LanguagePack lang : packs()) {
            for (String m : draw(lang, PLACES, 3000, 7)) {
                var found = TIME.matcher(m);
                while (found.find()) {
                    int hour = Integer.parseInt(found.group().substring(0, 2));
                    assertTrue(hour >= 7 && hour <= 22, m);
                }
            }
        }
    }

    @Test
    void sameStreamSameTexts() {
        LanguagePack lang = packs().get(0);
        assertEquals(draw(lang, PLACES, 50, 6), draw(lang, PLACES, 50, 6));
    }
}
