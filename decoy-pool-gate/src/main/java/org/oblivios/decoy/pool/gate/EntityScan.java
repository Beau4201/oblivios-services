// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.pool.gate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.country.Packs;
import org.oblivios.decoy.content.lang.LanguagePack;
import org.oblivios.decoy.content.pool.PoolConversation;
import org.oblivios.decoy.content.pool.PoolSchema;

/**
 * What a pool conversation may not carry outside a slot: a given or family name of any country pack, as a whole word,
 * and anything that could reach a real person. A name counts as the pack writes it, with its capital, because many
 * family names are ordinary words in lower case; a name that is also a stop word of the language is no finding. At the
 * start of a sentence a capital says nothing, so a family name that stands there alone ("Lang geleden") is no finding
 * either, unless the sentence names it again; a given name there still is.
 */
public final class EntityScan {
    // shortcut: a name written in lower case, or a family name alone at the start of a sentence, passes this scan; the
    // judge reads for names outside slots. A word list per language would let the scan ignore case.
    private static final Map<String, Names> NAMES = new ConcurrentHashMap<>();
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?…])\\s+");

    /** Every name of the packs, and the one-word family names that are no given name of any pack. */
    private record Names(Set<String> all, Set<String> familyOnly, int longest) {}

    private EntityScan() {}

    public static List<String> findings(PoolConversation c) {
        Names names = NAMES.computeIfAbsent(c.language(), EntityScan::namesFor);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < c.messages().size(); i++) {
            String text = c.messages().get(i).text();
            int number = i + 1;
            PoolSchema.contactData(text).forEach(found -> out.add("message " + number + ": " + found));
            Set<String> found = new TreeSet<>();
            for (String sentence : SENTENCE_END.split(text)) {
                found.addAll(namesIn(Words.of(sentence), names));
            }
            found.forEach(name -> out.add("message " + number + ": the name " + name));
        }
        return out;
    }

    private static Set<String> namesIn(List<String> words, Names names) {
        Set<String> found = new TreeSet<>(Words.found(words, names.all(), names.longest()));
        if (!words.isEmpty() && names.familyOnly().contains(words.get(0))
                && !Words.found(words.subList(1, words.size()), names.all(), names.longest()).contains(words.get(0))) {
            found.remove(words.get(0));
        }
        return found;
    }

    private static Names namesFor(String language) {
        Set<String> stop = LanguagePack.available().contains(language)
                ? new HashSet<>(LanguagePack.of(language).list("stopwords"))
                : Set.of();
        Set<String> all = new HashSet<>();
        Set<String> given = new HashSet<>();
        Set<String> family = new HashSet<>();
        for (CountryPack pack : Packs.all()) {
            pack.givenNames().forEach(name -> given.add(capital(name)));
            pack.familyNames().forEach(name -> family.add(capital(name)));
            Stream.concat(pack.givenNames().stream(), pack.familyNames().stream())
                    .filter(name -> !stop.contains(name.toLowerCase(Locale.ROOT)))
                    .forEach(name -> {
                        all.add(name);
                        // "de Jong" starts a sentence as "De Jong"
                        all.add(capital(name));
                    });
        }
        family.removeIf(name -> name.contains(" ") || given.contains(name));
        return new Names(all, family, Words.longest(all));
    }

    private static String capital(String name) {
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
