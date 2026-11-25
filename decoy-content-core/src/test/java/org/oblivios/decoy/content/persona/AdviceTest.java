// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.persona;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.oblivios.decoy.content.persona.Advice.Field;
import org.oblivios.decoy.content.persona.Advice.Situation;

class AdviceTest {

    @Test
    void strangerOnlyNeedsAgeAndGender() {
        assertEquals(EnumSet.of(Field.GENDER, Field.BIRTH_YEAR), Advice.mustMatch(Set.of(Situation.STRANGER)));
    }

    @Test
    void idCheckNeedsNameBirthYearGenderNationality() {
        assertEquals(EnumSet.of(Field.NAME, Field.BIRTH_YEAR, Field.GENDER, Field.NATIONALITY),
                Advice.mustMatch(Set.of(Situation.SEES_ID)));
    }

    @Test
    void knowsOwnerNeedsStyleAndCircle() {
        Set<Field> f = Advice.mustMatch(Set.of(Situation.KNOWS_OWNER));
        assertEquals(EnumSet.of(Field.NAME, Field.GENDER, Field.BIRTH_YEAR, Field.CITY, Field.LANGUAGES, Field.JOB, Field.CIRCLE,
                Field.STYLE), f);
    }

    @Test
    void recordsAddInterests() {
        Set<Field> expected = EnumSet.copyOf(Advice.mustMatch(Set.of(Situation.KNOWS_OWNER)));
        expected.add(Field.INTERESTS);
        assertEquals(expected, Advice.mustMatch(Set.of(Situation.HAS_RECORDS)));
    }

    @Test
    void situationsUnite() {
        Set<Field> f = Advice.mustMatch(Set.of(Situation.STRANGER, Situation.SEES_ID));
        assertEquals(EnumSet.of(Field.NAME, Field.BIRTH_YEAR, Field.GENDER, Field.NATIONALITY), f);
        assertTrue(Advice.mustMatch(Set.of(Situation.SEES_ID, Situation.HAS_RECORDS)).contains(Field.NATIONALITY));
        assertTrue(Advice.mustMatch(Set.of(Situation.SEES_ID, Situation.HAS_RECORDS)).contains(Field.INTERESTS));
    }

    @Test
    void deviationsAreAdvisedFieldsNotKeptAsOwn() {
        Set<Field> d = Advice.deviations(Set.of(Field.NAME, Field.GENDER), Set.of(Situation.SEES_ID));
        assertEquals(EnumSet.of(Field.BIRTH_YEAR, Field.NATIONALITY), d);
        assertEquals(Set.of(), Advice.deviations(EnumSet.allOf(Field.class), Set.of(Situation.HAS_RECORDS)));
    }

    @Test
    void noSituationNoAdvice() {
        assertEquals(Set.of(), Advice.mustMatch(Set.of()));
        assertEquals(Set.of(), Advice.deviations(Set.of(), Set.of()));
    }
}
