// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.pool.gate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.oblivios.decoy.content.lang.LanguagePack;
import org.oblivios.decoy.content.pool.PoolConversation;
import org.oblivios.decoy.content.pool.PoolSchema;

/**
 * What a pool conversation may not carry outside a slot: a name of the scan list of its language, as a whole word, and
 * anything that could reach a real person. The scan list is the most common given and family names of the language's
 * country ({@code scan-names-<tag>.txt}), its own list, so that the library's name data does not reject ordinary words.
 * A name counts with its capital, because many family names are ordinary words in lower case; a name that is also a stop
 * word of the language is no finding. At the start of a sentence a capital says nothing, so a family name that stands
 * there alone ("Lang geleden") is no finding either, unless the sentence names it again; a given name there still is.
 */
public final class EntityScan {
    // shortcut: a name written in lower case, or a family name alone at the start of a sentence, passes this scan; the
    // judge reads for names outside slots. A word list per language would let the scan ignore case.
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?…])\\s+");
    private static final Pattern FILE_NAME = Pattern.compile("scan-names-([a-z]{2,3})\\.txt");

    /** Every name of one language's list, and the one-word family names that are no given name of the list. */
    public record Names(String language, Set<String> all, Set<String> familyOnly, int longest) {
        static Names of(String language, Set<String> given, Set<String> family) {
            Set<String> stop = LanguagePack.available().contains(language)
                    ? new HashSet<>(LanguagePack.of(language).list("stopwords"))
                    : Set.of();
            Set<String> all = new HashSet<>();
            Set<String> givenForms = new HashSet<>();
            Set<String> familyForms = new HashSet<>();
            given.forEach(name -> givenForms.add(capital(name)));
            family.forEach(name -> familyForms.add(capital(name)));
            Stream.concat(given.stream(), family.stream())
                    .filter(name -> !stop.contains(name.toLowerCase(Locale.ROOT)))
                    .forEach(name -> {
                        all.add(name);
                        // "de Jong" starts a sentence as "De Jong"
                        all.add(capital(name));
                    });
            familyForms.removeIf(name -> name.contains(" ") || givenForms.contains(name));
            return new Names(language, all, familyForms, Words.longest(all));
        }

        /** Reads {@code scan-names-<tag>.txt}: lines {@code given<TAB>name} or {@code family<TAB>name}. */
        static Names read(Path file) throws IOException {
            Matcher tag = FILE_NAME.matcher(String.valueOf(file.getFileName()));
            if (!tag.matches()) {
                throw new IOException("a name list is called scan-names-<language>.txt, not " + file.getFileName());
            }
            Set<String> given = new HashSet<>();
            Set<String> family = new HashSet<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] cells = line.split("\t", -1);
                if (cells.length != 2 || cells[1].isBlank() || !cells[0].equals("given") && !cells[0].equals("family")) {
                    throw new IOException(file.getFileName() + ": not 'given<TAB>name' or 'family<TAB>name': " + line);
                }
                (cells[0].equals("given") ? given : family).add(cells[1].trim());
            }
            if (given.isEmpty() && family.isEmpty()) {
                throw new IOException(file.getFileName() + " holds no name");
            }
            return of(tag.group(1), given, family);
        }
    }

    private EntityScan() {}

    public static List<String> findings(PoolConversation c, Names names) {
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

    private static String capital(String name) {
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
