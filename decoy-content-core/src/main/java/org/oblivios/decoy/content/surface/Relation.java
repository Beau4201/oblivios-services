// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

/** How a contact relates to the persona: one value per one-to-one relation of the design's taxonomy. */
public enum Relation {
    PARTNER, EX_PARTNER, DATE, PARENT, STEP_PARENT, GRANDPARENT, SIBLING, CHILD, IN_LAW, RELATIVE, BEST_FRIEND, FRIEND,
    OLD_FRIEND, HOUSEMATE, NEIGHBOUR, COLLEAGUE, MANAGER, REPORT, CLIENT, CLASSMATE, TEACHER, TEAMMATE, CHILDMINDER,
    PARENT_OF_FRIEND, LANDLORD, TENANT, TRADESPERSON, MARKETPLACE, ACQUAINTANCE;

    /** The index of the contact group in the language's {@code contact.groups}: family, friends, work, other. */
    public int groupIndex() {
        return switch (this) {
            case PARTNER, PARENT, STEP_PARENT, GRANDPARENT, SIBLING, CHILD, IN_LAW, RELATIVE -> 0;
            case BEST_FRIEND, FRIEND, OLD_FRIEND, HOUSEMATE -> 1;
            case COLLEAGUE, MANAGER, REPORT, CLIENT -> 2;
            default -> 3;
        };
    }
}
