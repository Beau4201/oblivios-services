// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check.rules;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.check.Checks;
import org.oblivios.decoy.content.check.ExaminerRule;
import org.oblivios.decoy.content.check.PhoneViews;
import org.oblivios.decoy.content.check.Violation;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.lang.LanguagePack;
import org.oblivios.decoy.content.persona.PersonaProfile.Fact;
import org.oblivios.decoy.content.surface.BluetoothPair;
import org.oblivios.decoy.content.surface.CalEvent;
import org.oblivios.decoy.content.surface.Note;

/**
 * {@code F-PRESUMES}: a calendar title, a note or a Bluetooth name holds a word of {@code presumes.<FACT>} of the
 * persona's language while the persona lacks that fact. A word matches as whole words, ignoring case.
 */
public final class FPresumes implements ExaminerRule {
    static final List<Fact> FACTS = List.of(Fact.CAR, Fact.SCHOOL_CHILDREN, Fact.WORKS, Fact.DOG, Fact.CAT);

    @Override
    public String id() {
        return "F-PRESUMES";
    }

    @Override
    public void check(PhoneViews v, Persona persona, CountryPack pack, Instant now, List<Violation> out) {
        List<String> texts = new ArrayList<>();
        v.calendar().events().stream().map(CalEvent::title).forEach(texts::add);
        v.calendar().notes().stream().map(Note::text).forEach(texts::add);
        v.network().bluetooth().stream().map(BluetoothPair::name).forEach(texts::add);
        LanguagePack lang = LanguagePack.of(persona.language());
        Set<Fact> has = persona.facts();
        for (Fact fact : FACTS) {
            if (has.contains(fact)) {
                continue;
            }
            for (String word : lang.list("presumes." + fact.name())) {
                for (String text : texts) {
                    if (Checks.hasPhrase(text, word)) {
                        out.add(new Violation(id(), "'" + text + "' presumes " + fact + ", which the persona lacks"));
                    }
                }
            }
        }
    }
}
