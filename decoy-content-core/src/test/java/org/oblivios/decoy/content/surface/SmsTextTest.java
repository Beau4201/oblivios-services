// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.oblivios.decoy.content.Seed;

class SmsTextTest {
    private static final List<String> NAMES = List.of("Emma", "Daan", "Lotte", "Bram", "Femke", "Joost", "Noor", "Stijn");
    private static final List<String> PLACES = List.of("Cafe de Jong", "Jumbo", "Cafe Bakker");
    private static final Pattern TIME = Pattern.compile("\\b\\d{2}:\\d{2}\\b");
    private static final Pattern MINUTES = Pattern.compile("\\b\\d+ (minuten|minutes)\\b");
    private static final Pattern DAY = Pattern.compile("(?i)\\b(maandag|dinsdag|woensdag|donderdag|vrijdag|zaterdag|zondag|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b");

    private static List<String> draw(boolean dutch, List<String> places, int count, long seed) {
        var r = Seed.of(seed, 1).stream("sms");
        return java.util.stream.IntStream.range(0, count).mapToObj(i -> SmsText.message(dutch, r, NAMES, places)).toList();
    }

    private static int slotsIn(String text, List<String> places) {
        int slots = 0;
        slots += NAMES.stream().anyMatch(text::contains) ? 1 : 0;
        slots += places.stream().anyMatch(text::contains) ? 1 : 0;
        slots += DAY.matcher(text).find() ? 1 : 0;
        slots += TIME.matcher(text).find() ? 1 : 0;
        slots += MINUTES.matcher(text).find() ? 1 : 0;
        return slots;
    }

    private static int words(String text) {
        return text.trim().split("\\s+").length;
    }

    @Test
    void everyLongMessageCarriesAtLeastTwoSlotsOfThePersonasWorld() {
        for (boolean dutch : new boolean[] {true, false}) {
            for (String m : draw(dutch, PLACES, 3000, 1)) {
                if (words(m) >= 4) {
                    assertTrue(slotsIn(m, PLACES) >= 2, "a fixed sentence: " + m);
                }
            }
        }
    }

    @Test
    void noTokenIsLeftUnresolvedAndNoPlaceIsInventedWithoutPlaces() {
        for (boolean dutch : new boolean[] {true, false}) {
            for (String m : draw(dutch, List.of(), 3000, 2)) {
                assertFalse(m.contains("{") || m.contains("}"), m);
                assertFalse(PLACES.stream().anyMatch(m::contains), m);
            }
        }
    }

    @Test
    void aboutAQuarterAreShortUniversalReplies() {
        List<String> messages = draw(true, PLACES, 4000, 3);
        long shortOnes = messages.stream().filter(m -> words(m) <= 2).count();
        assertTrue(shortOnes >= 800 && shortOnes <= 1400, "short replies " + shortOnes + " of 4000");
        assertTrue(messages.stream().filter(m -> words(m) <= 2).allMatch(m -> slotsIn(m, PLACES) == 0));
    }

    @Test
    void textsAreVariedNotAFixedList() {
        for (boolean dutch : new boolean[] {true, false}) {
            Set<String> distinct = new HashSet<>(draw(dutch, PLACES, 2000, 4));
            assertTrue(distinct.size() > 800, "distinct texts " + distinct.size() + " of 2000");
        }
    }

    @Test
    void languagesAreNotMixed() {
        Pattern english = Pattern.compile("(?i)\\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday|minutes)\\b");
        Pattern dutch = Pattern.compile("(?i)\\b(maandag|dinsdag|woensdag|donderdag|vrijdag|zaterdag|zondag|minuten)\\b");
        assertTrue(draw(true, PLACES, 2000, 5).stream().noneMatch(m -> english.matcher(m).find()));
        assertTrue(draw(false, PLACES, 2000, 5).stream().noneMatch(m -> dutch.matcher(m).find()));
    }

    @Test
    void withoutNamesNoMessageNeedsOne() {
        var r = Seed.of(8, 1).stream("sms");
        for (int i = 0; i < 3000; i++) {
            String m = SmsText.message(i % 2 == 0, r, List.of(), PLACES);
            assertFalse(m.contains("{") || NAMES.stream().anyMatch(m::contains), m);
        }
    }

    @Test
    void timesInAMessageAreInWakingHours() {
        for (boolean dutch : new boolean[] {true, false}) {
            for (String m : draw(dutch, PLACES, 3000, 7)) {
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
        assertEquals(draw(true, PLACES, 50, 6), draw(true, PLACES, 50, 6));
    }
}
