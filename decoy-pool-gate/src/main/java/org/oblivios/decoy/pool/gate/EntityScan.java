// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.pool.gate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.country.Packs;
import org.oblivios.decoy.content.lang.LanguagePack;
import org.oblivios.decoy.content.pool.PoolConversation;
import org.oblivios.decoy.content.pool.PoolSchema;

/**
 * What a pool conversation may not carry outside a slot: a given or family name of any country pack, as a whole word,
 * and anything that could reach a real person. A name counts as the pack writes it, with its capital, because many
 * family names are ordinary words in lower case; a name that is also a stop word of the language is no finding.
 */
public final class EntityScan {
    // shortcut: a name written in lower case passes this scan; the judge reads for names outside slots. A word list per
    // language would let the scan ignore case.
    private static final Map<String, Set<String>> NAMES = new ConcurrentHashMap<>();

    private EntityScan() {}

    public static List<String> findings(PoolConversation c) {
        Set<String> names = NAMES.computeIfAbsent(c.language(), EntityScan::namesFor);
        int longest = Words.longest(names);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < c.messages().size(); i++) {
            String text = c.messages().get(i).text();
            int number = i + 1;
            PoolSchema.contactData(text).forEach(found -> out.add("message " + number + ": " + found));
            Words.found(Words.of(text), names, longest).forEach(name -> out.add("message " + number + ": the name " + name));
        }
        return out;
    }

    private static Set<String> namesFor(String language) {
        Set<String> stop = LanguagePack.available().contains(language)
                ? new HashSet<>(LanguagePack.of(language).list("stopwords"))
                : Set.of();
        Set<String> out = new HashSet<>();
        for (CountryPack pack : Packs.all()) {
            Stream.concat(pack.givenNames().stream(), pack.familyNames().stream())
                    .filter(name -> !stop.contains(name.toLowerCase(Locale.ROOT)))
                    .forEach(name -> {
                        out.add(name);
                        // "de Jong" starts a sentence as "De Jong"
                        out.add(Character.toUpperCase(name.charAt(0)) + name.substring(1));
                    });
        }
        return out;
    }
}
