// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.oblivios.decoy.content.Seed;

class ShuffleTest {
    @Test
    void keepsEveryElementOnce() {
        List<Integer> list = new ArrayList<>(IntStream.range(0, 200).boxed().toList());
        Shuffle.inPlace(list, Seed.of(1, 1).stream("shuffle"));
        assertEquals(IntStream.range(0, 200).boxed().toList(), list.stream().sorted().toList());
        assertNotEquals(IntStream.range(0, 200).boxed().toList(), list);
    }

    @Test
    void everyPositionIsReachedEvenly() {
        int[] first = new int[5];
        var r = Seed.of(2, 1).stream("shuffle");
        for (int i = 0; i < 10_000; i++) {
            List<Integer> list = new ArrayList<>(List.of(0, 1, 2, 3, 4));
            Shuffle.inPlace(list, r);
            first[list.get(0)]++;
        }
        for (int count : first) {
            assertTrue(count > 1700 && count < 2300, "first element counts " + java.util.Arrays.toString(first));
        }
    }

    @Test
    void sameStreamSameOrder() {
        List<Integer> a = new ArrayList<>(IntStream.range(0, 50).boxed().toList());
        List<Integer> b = new ArrayList<>(a);
        Shuffle.inPlace(a, Seed.of(3, 1).stream("shuffle"));
        Shuffle.inPlace(b, Seed.of(3, 1).stream("shuffle"));
        assertEquals(a, b);
    }

    @Test
    void emptyAndSingleListsAreFine() {
        List<Integer> none = new ArrayList<>();
        List<Integer> one = new ArrayList<>(List.of(7));
        Shuffle.inPlace(none, Seed.of(4, 1).stream("shuffle"));
        Shuffle.inPlace(one, Seed.of(4, 1).stream("shuffle"));
        assertEquals(List.of(), none);
        assertEquals(List.of(7), one);
    }
}
