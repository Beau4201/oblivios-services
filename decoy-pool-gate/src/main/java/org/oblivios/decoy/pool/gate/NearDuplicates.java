// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.pool.gate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.oblivios.decoy.content.pool.PoolConversation;
import org.oblivios.decoy.content.pool.PoolMessage;

/**
 * Drops near duplicates by the Jaccard likeness of the word 4-grams of two conversations. An index from each 4-gram to
 * the kept conversations that hold it means a conversation is compared only with those that share one.
 */
public final class NearDuplicates {
    private static final int GRAM = 4;

    private NearDuplicates() {}

    /** The input in its order without each conversation whose likeness with an earlier kept one reaches {@code threshold}. */
    public static List<PoolConversation> dedupe(List<PoolConversation> in, double threshold) {
        List<PoolConversation> kept = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        Map<String, List<Integer>> index = new HashMap<>();
        for (PoolConversation c : in) {
            Set<String> grams = grams(c);
            Map<Integer, Integer> shared = new HashMap<>();
            for (String g : grams) {
                index.getOrDefault(g, List.of()).forEach(k -> shared.merge(k, 1, Integer::sum));
            }
            boolean duplicate = shared.entrySet().stream()
                    .anyMatch(e -> e.getValue() >= threshold * (grams.size() + sizes.get(e.getKey()) - e.getValue()));
            if (!duplicate) {
                int at = kept.size();
                grams.forEach(g -> index.computeIfAbsent(g, k -> new ArrayList<>()).add(at));
                sizes.add(grams.size());
                kept.add(c);
            }
        }
        return kept;
    }

    private static Set<String> grams(PoolConversation c) {
        List<String> words = Words.folded(c.messages().stream().map(PoolMessage::text).toList());
        Set<String> out = new HashSet<>();
        for (int i = 0; i + GRAM <= words.size(); i++) {
            out.add(String.join(" ", words.subList(i, i + GRAM)));
        }
        return out;
    }
}
