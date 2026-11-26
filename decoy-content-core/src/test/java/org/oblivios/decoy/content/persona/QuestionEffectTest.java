// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.persona;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.oblivios.decoy.content.Draws;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.PersonaInputs;
import org.oblivios.decoy.content.Seed;
import org.oblivios.decoy.content.check.Checks;
import org.oblivios.decoy.content.check.PhoneViews;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.country.Packs;
import org.oblivios.decoy.content.data.DataSet;
import org.oblivios.decoy.content.harness.HarnessTest;
import org.oblivios.decoy.content.harness.PlanHarness;
import org.oblivios.decoy.content.plan.ContentPlan;

/** Every setup question visibly shapes the decoy (design, goals): a question that changes nothing is removed. */
class QuestionEffectTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 26);

    /**
     * Per question the {@link PhoneViews} components it drives, from the setup table of the design. Empty until the view
     * tasks convert their views; each adds its rows. A row the test shows false is a finding, never deleted to pass.
     */
    private static final Map<Question, Set<String>> DRIVES = Map.of();

    private static Persona derive(PersonaProfile p) {
        PersonaInputs in = p.inputs();
        CountryPack pack = Packs.forDevice(in.countryCode(), in.zone(), in.locale()).pack();
        return Persona.derive(Draws.of(Seed.of(9, 9)), p, TODAY, pack, DataSet.of(pack.code()));
    }

    @Test
    void everyQuestionHasAnAlternative() {
        for (Question changed : Question.values()) {
            PersonaProfile alternative = Profiles.alternative(changed);
            for (Question q : Question.values()) {
                Object before = Profiles.answer(Profiles.full(), q);
                Object after = Profiles.answer(alternative, q);
                if (q == changed) {
                    assertNotEquals(before, after, changed + " has no other answer");
                } else {
                    assertEquals(before, after, changed + " also changes " + q);
                }
            }
        }
    }

    @Test
    void everyAnswerHasAQuestion() {
        // the six values of the inputs hold three questions; the country, zone and locale are read from the device
        assertEquals(PersonaProfile.class.getRecordComponents().length - 1 + 3, Question.values().length);
        for (Question q : Question.values()) {
            assertEquals(true, q.screen() >= 2 && q.screen() <= 6, q + " is on screen " + q.screen());
        }
    }

    @Test
    void everyQuestionChangesThePersona() {
        Persona full = derive(Profiles.full());
        for (Question q : Question.values()) {
            assertNotEquals(full, derive(Profiles.alternative(q)), q + " changes nothing");
        }
    }

    @Test
    void everyQuestionDrivesItsViews() throws ReflectiveOperationException {
        for (Map.Entry<Question, Set<String>> row : DRIVES.entrySet()) {
            ContentPlan before = PlanHarness.plan(Profiles.full(), Seed.of(9, 9));
            ContentPlan after = PlanHarness.plan(Profiles.alternative(row.getKey()), Seed.of(9, 9));
            for (String view : row.getValue()) {
                assertNotEquals(component(PhoneViews.of(before), view), component(PhoneViews.of(after), view),
                        row.getKey() + " does not drive " + view);
            }
            for (ContentPlan p : new ContentPlan[] {before, after}) {
                assertEquals(java.util.List.of(),
                        Checks.all(p).stream().filter(v -> HarnessTest.MUST_PASS.contains(v.ruleId())).toList(), row.getKey().name());
            }
        }
    }

    private static Object component(PhoneViews v, String name) throws ReflectiveOperationException {
        for (RecordComponent c : PhoneViews.class.getRecordComponents()) {
            if (c.getName().equals(name)) {
                return c.getAccessor().invoke(v);
            }
        }
        throw new IllegalArgumentException("PhoneViews has no component " + name);
    }
}
