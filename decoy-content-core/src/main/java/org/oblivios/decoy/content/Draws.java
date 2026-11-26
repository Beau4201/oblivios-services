// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content;

import java.util.HashMap;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Every draw of a plan: the seed's streams, with the redraws of the review. A stream is named after the item it draws
 * for ({@code world.person.3.given}), so a redraw of an item moves only that item and its parts.
 */
public final class Draws {
    private final Seed seed;
    private final Map<String, Integer> salts;

    private Draws(Seed seed, Map<String, Integer> salts) {
        this.seed = seed;
        this.salts = salts;
    }

    public static Draws of(Seed seed) {
        return new Draws(seed, Map.of());
    }

    /** These draws with {@code item} and its parts drawn again; {@code salt} counts the redraws, 0 for none. */
    public Draws redraw(String item, int salt) {
        Map<String, Integer> next = new HashMap<>(salts);
        if (salt == 0) {
            next.remove(item);
        } else {
            next.put(item, salt);
        }
        return new Draws(seed, Map.copyOf(next));
    }

    /** A fresh generator for {@code name}, salted by the redraw of the longest item that {@code name} is or is a part of. */
    public RandomGenerator stream(String name) {
        for (String item = name; ; item = item.substring(0, item.lastIndexOf('.'))) {
            Integer salt = salts.get(item);
            if (salt != null) {
                return seed.stream(name + "#" + item + "#" + salt);
            }
            if (item.indexOf('.') < 0) {
                return seed.stream(name);
            }
        }
    }

    public Seed seed() {
        return seed;
    }
}
