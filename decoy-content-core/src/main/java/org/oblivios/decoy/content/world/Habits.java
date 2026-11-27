// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.world;

import java.util.Objects;
import org.oblivios.decoy.content.origin.Origin;

/** The persona's own behaviour, drawn once and kept over all months. */
public record Habits(LabelHabit labels, boolean savesInternational, double calendarShare, double photoShare,
        boolean screenshots, boolean paymentTexts, double callBackShare, double unreadShare, boolean dnd, double fontScale,
        boolean renamesDevice, boolean contactGroups, int favourites, boolean notes, Rhythm rhythm, double rankExponent,
        Activity activity, Tidiness tidiness, Origin origin) {

    /** How the persona saves people. A nickname, signs and a word of context are never drawn: no source holds them. */
    public enum LabelHabit { GIVEN, FULL, NICKNAME, SIGNS, CONTEXT }

    public Habits {
        Objects.requireNonNull(origin, "origin");
    }
}
