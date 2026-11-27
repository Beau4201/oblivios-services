// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.lang.LanguagePack;
import org.oblivios.decoy.content.story.PlaceKind;
import org.oblivios.decoy.content.surface.Relation;
import org.oblivios.decoy.content.world.Habits.LabelHabit;

/**
 * How the persona saved each person and what it calls them. {@code taken} holds labels in lower case: those given out,
 * and the given names that two people bear, so that neither is saved by that name alone.
 */
public final class Labels {
    private Labels() {}

    static String key(String label) {
        return label.trim().toLowerCase(Locale.ROOT);
    }

    /** Reserves every given name that more than one of these bears. */
    public static void reserve(List<String> givenNames, Set<String> taken) {
        Map<String, Integer> count = new HashMap<>();
        givenNames.forEach(g -> count.merge(key(g), 1, Integer::sum));
        count.forEach((name, n) -> {
            if (n > 1) {
                taken.add(name);
            }
        });
    }

    static boolean elder(Relation r) {
        return r == Relation.PARENT || r == Relation.GRANDPARENT;
    }

    static String capital(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    /**
     * What the persona calls the person in a message: the owner's answer, else for a parent or grandparent one of the
     * language's words for them (each as likely, {@code [assumption]}), else the given name.
     */
    public static String addressedAs(WorldPerson person, String calledAs, LanguagePack lang, RandomGenerator r) {
        if (!calledAs.isBlank()) {
            return calledAs.trim();
        }
        String key = "kin." + person.relation() + "." + person.gender();
        if (elder(person.relation()) && lang.has(key)) {
            List<String> words = lang.list(key);
            return words.get(r.nextInt(words.size()));
        }
        return person.given();
    }

    /**
     * The label under which the persona saved the person, added to {@code taken}: the word for a parent or grandparent,
     * else the habit's form; a label already taken gets what tells the two apart.
     */
    public static String label(WorldPerson person, Persona persona, LabelHabit habit, LanguagePack lang, Set<String> taken,
            RandomGenerator r) {
        List<String> forms = new ArrayList<>();
        String full = person.usedFamily().isBlank() ? person.given() : person.given() + " " + person.usedFamily();
        if (elder(person.relation()) && !person.addressedAs().equals(person.given())) {
            String word = capital(person.addressedAs());
            forms.add(word);
            forms.add(word + " " + person.given());
            forms.add(word + " " + person.usedFamily());
        } else if (habit == LabelHabit.FULL) {
            forms.add(full);
        } else {
            forms.add(person.given());
            forms.add(full);
        }
        for (String form : forms) {
            if (taken.add(key(form))) {
                return form;
            }
        }
        for (int i = 2; ; i++) {
            if (taken.add(key(full + " " + i))) {
                return full + " " + i;
            }
        }
    }

    /** The label of the practice of a role ({@code label.role.<ROLE>}), where a source gives the language's word. */
    public static Optional<String> roleLabel(PlaceKind kind, LanguagePack lang) {
        String key = "label.role." + kind;
        return lang.has(key) ? Optional.of(capital(lang.text(key))) : Optional.empty();
    }
}
