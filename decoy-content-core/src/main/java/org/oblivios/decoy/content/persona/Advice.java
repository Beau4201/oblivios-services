// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.persona;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Which fields of the persona must match the owner, per situation in which the decoy is shown (the advice table of
 * the persona design). The setup shows it per field; the owner decides.
 */
public final class Advice {

    /** Who looks at the decoy. */
    public enum Situation { KNOWS_OWNER, SEES_ID, HAS_RECORDS, STRANGER }

    public enum Field { NAME, GENDER, BIRTH_YEAR, NATIONALITY, COUNTRY, CITY, LANGUAGES, JOB, CIRCLE, STYLE, INTERESTS }

    private static final Map<Situation, Set<Field>> TABLE = new EnumMap<>(Situation.class);

    static {
        Set<Field> knows = EnumSet.of(Field.NAME, Field.GENDER, Field.BIRTH_YEAR, Field.CITY, Field.LANGUAGES, Field.JOB,
                Field.CIRCLE, Field.STYLE);
        Set<Field> records = EnumSet.copyOf(knows);
        records.add(Field.INTERESTS);
        TABLE.put(Situation.KNOWS_OWNER, Collections.unmodifiableSet(knows));
        TABLE.put(Situation.SEES_ID, Collections.unmodifiableSet(EnumSet.of(Field.NAME, Field.BIRTH_YEAR, Field.GENDER, Field.NATIONALITY)));
        TABLE.put(Situation.HAS_RECORDS, Collections.unmodifiableSet(records));
        TABLE.put(Situation.STRANGER, Collections.unmodifiableSet(EnumSet.of(Field.GENDER, Field.BIRTH_YEAR)));
    }

    private Advice() {}

    /** The fields that must match the owner in any of the given situations (their union). */
    public static Set<Field> mustMatch(Set<Situation> s) {
        Set<Field> out = EnumSet.noneOf(Field.class);
        for (Situation x : s) {
            out.addAll(TABLE.get(x));
        }
        return out;
    }

    /** The advised fields that the owner did not keep as the owner's own. */
    public static Set<Field> deviations(Set<Field> keptAsOwn, Set<Situation> s) {
        Set<Field> out = mustMatch(s);
        out.removeAll(keptAsOwn);
        return out;
    }
}
