// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check.rules;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.oblivios.decoy.content.check.OriginRule;
import org.oblivios.decoy.content.check.PhoneViews;
import org.oblivios.decoy.content.check.PlanStory;
import org.oblivios.decoy.content.check.Violation;
import org.oblivios.decoy.content.lang.LanguagePack;
import org.oblivios.decoy.content.style.StyleBook;
import org.oblivios.decoy.content.style.StyleVector;
import org.oblivios.decoy.content.surface.Relation;

/**
 * {@code R-REGISTER}: the persona writes a street word of its language in a conversation where its style towards the
 * contact is formal or neutral; a group is written in the persona's own style, as the picker matched it. An origin rule
 * because the style book and the reserve are no view.
 */
public final class RRegister implements OriginRule {
    private static final Pattern NOT_A_LETTER = Pattern.compile("[^\\p{L}]+");

    @Override
    public String id() {
        return "R-REGISTER";
    }

    @Override
    public void check(PhoneViews v, PlanStory story, List<Violation> out) {
        LanguagePack lang = LanguagePack.of(story.persona().language());
        // a language without a sourced list has no street words to judge by (ADR-336)
        Set<String> street = !lang.has("register.STREET.words") ? Set.of() : lang.list("register.STREET.words").stream()
                .map(w -> w.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        Map<String, Relation> relations = new HashMap<>();
        v.people().contacts().forEach(c -> relations.put(c.id(), c.relation()));
        // a word that is also a contact's name is that person, not the street word
        Set<String> names = v.people().contacts().stream()
                .flatMap(c -> Stream.of(NOT_A_LETTER.split(c.name().toLowerCase(Locale.ROOT))))
                .collect(Collectors.toSet());
        StyleBook styles = story.styles();
        Stream.concat(story.conversations().stream(), story.reserve().stream()).forEach(c -> {
            Relation relation = c.group() == null ? relations.get(c.contactIds().get(0)) : null;
            StyleVector own = relation == null ? styles.persona() : styles.personaTowards().getOrDefault(relation, styles.persona());
            if (own.register() != StyleVector.Register.FORMAL && own.register() != StyleVector.Register.NEUTRAL) {
                return;
            }
            c.messages().stream().filter(m -> !m.incoming()).forEach(m -> {
                for (String word : NOT_A_LETTER.split(m.body().toLowerCase(Locale.ROOT))) {
                    if (street.contains(word) && !names.contains(word)) {
                        out.add(new Violation(id(), "conversation " + c.poolId() + ": the persona writes " + own.register() + " there, and says '"
                                + word + "'"));
                    }
                }
            });
        });
    }
}
