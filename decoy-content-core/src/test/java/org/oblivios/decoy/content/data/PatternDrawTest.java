// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class PatternDrawTest {
    @Test
    void drawnStringsMatchTheirPattern() {
        SplittableRandom r = new SplittableRandom(1);
        int territories = 0;
        for (String code : PhoneNumbers.countries()) {
            PhoneNumbers.Shape mobile = PhoneNumbers.of(code).mobileShape();
            if (mobile == null) {
                continue;
            }
            territories++;
            Pattern p = Pattern.compile(mobile.pattern());
            for (int i = 0; i < 10_000; i++) {
                String s = PatternDraw.draw(mobile.pattern(), mobile.lengths(), r);
                assertTrue(p.matcher(s).matches() && mobile.lengths().contains(s.length()), code + ": " + s);
            }
        }
        assertEquals(244, territories);
    }

    @Test
    void everyStringOfASmallPatternIsDrawn() {
        SplittableRandom r = new SplittableRandom(2);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2_000; i++) {
            seen.add(PatternDraw.draw("1(?:2[3-4]|5)\\d{0,1}6?", Set.of(2, 3), r));
        }
        Set<String> wanted = new HashSet<>(Set.of("15", "156", "123", "124"));
        for (int d = 0; d <= 9; d++) {
            wanted.add("15" + d);
        }
        assertEquals(wanted, seen);
    }

    @Test
    void unsupportedConstructIsRefused() {
        SplittableRandom r = new SplittableRandom(3);
        for (String bad : new String[] {"(\\d)\\1", "\\d+", "\\d*", ".\\d", "(?=1)\\d", "[^1]", "\\w", "(?:1", "1)", "\\d{2,"}) {
            assertThrows(IllegalArgumentException.class, () -> PatternDraw.draw(bad, Set.of(2), r), bad);
        }
    }

    @Test
    void aLengthThePatternCannotHaveIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> PatternDraw.draw("\\d{3}", Set.of(4), new SplittableRandom(4)));
    }
}
