// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.util.Collections;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Fisher-Yates over a {@link RandomGenerator}. {@code Collections.shuffle(List, RandomGenerator)} is Java 21 API, and
 * the platform runtime (ART) may not have it; this class needs only {@code nextInt(int)}. Whether
 * {@code RandomGenerator} itself exists in the built tree's core library is read at Task 15 (the module build).
 */
public final class Shuffle {
    private Shuffle() {}

    public static void inPlace(List<?> list, RandomGenerator r) {
        for (int i = list.size() - 1; i > 0; i--) {
            Collections.swap(list, i, r.nextInt(i + 1));
        }
    }
}
