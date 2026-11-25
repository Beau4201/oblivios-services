// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.pool.gate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** The words of a text as the gates read them: slots and media markers are no words. */
final class Words {
    private static final Pattern SLOT_OR_MARKER = Pattern.compile("\\{[A-Z_]+}|\\[[^\\]]*]");
    private static final Pattern BETWEEN = Pattern.compile("[^\\p{L}\\p{N}]+");

    private Words() {}

    /** The words of the text, their case kept. */
    static List<String> of(String text) {
        return Arrays.stream(BETWEEN.split(SLOT_OR_MARKER.matcher(text).replaceAll(" "))).filter(w -> !w.isEmpty()).toList();
    }

    /** Every phrase of {@code phrases} that the words hold, whole words only; a phrase has its words one space apart. */
    static Set<String> found(List<String> words, Set<String> phrases, int longest) {
        Set<String> out = new TreeSet<>();
        for (int from = 0; from < words.size(); from++) {
            for (int to = from + 1; to <= Math.min(words.size(), from + longest); to++) {
                String phrase = String.join(" ", words.subList(from, to));
                if (phrases.contains(phrase)) {
                    out.add(phrase);
                }
            }
        }
        return out;
    }

    static int longest(Set<String> phrases) {
        return phrases.stream().mapToInt(p -> p.split(" ").length).max().orElse(0);
    }

    /** Lower case with the apostrophes taken out, so that a word and its stop-word spelling meet. */
    static List<String> folded(List<String> texts) {
        List<String> out = new ArrayList<>();
        for (String t : texts) {
            out.addAll(of(t.toLowerCase(java.util.Locale.ROOT).replace("'", "").replace("’", "")));
        }
        return out;
    }
}
