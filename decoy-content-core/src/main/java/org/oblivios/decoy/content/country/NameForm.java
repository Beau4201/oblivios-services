// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.country;

/** The forms a family name can take, which a country pack weighs. */
public final class NameForm {
    private NameForm() {}

    /** The name of a child: one parent's, or both in a free order. */
    public enum Child { FATHER, MOTHER, BOTH }

    /** The name a married person uses: the own, the partner's, or both with the one named first. */
    public enum Married { OWN, PARTNER, PARTNER_OWN, OWN_PARTNER }
}
