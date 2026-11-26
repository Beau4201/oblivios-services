// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.persona;

/** One answer of the setup screens 2 to 6 (design, "The setup"); its name identifies the answer in an origin. */
public enum Question {
    GIVEN_NAME(2), FAMILY_NAME(2), GENDER(2), BIRTH_YEAR(2), NATIONALITY(2), CITY(2), LANGUAGES(2), MIX_LANGUAGE(2),
    BACKGROUND(2),
    RELATIONSHIP(3), PARTNER_AT_HOME(3), CHILDREN(3), PETS(3), HOUSING(3), HOUSEHOLD(3),
    JOB(4), JOB_FIELD(4), WORK_PATTERN(4), TRANSPORT(4), ACTIVITIES(4), INTERESTS(4),
    PARENTS(5), PARENTS_TOGETHER(5), SIBLINGS(5), FRIEND_GROUPS(5), CIRCLE(5),
    BANK(6), OPERATOR(6), SUPERMARKET(6), TEXTING(6), VOICE_NOTES(6), STYLE(6), HOLIDAYS(6), TRIPS(6);

    private final int screen;

    Question(int screen) {
        this.screen = screen;
    }

    /** The setup screen that asks it. */
    public int screen() {
        return screen;
    }
}
