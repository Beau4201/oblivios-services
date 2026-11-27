// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

/** How a contact relates to the persona: one value per one-to-one relation of the design's taxonomy. */
public enum Relation {
    PARTNER, EX_PARTNER, DATE, PARENT, STEP_PARENT, GRANDPARENT, SIBLING, CHILD, IN_LAW, RELATIVE, BEST_FRIEND, FRIEND,
    OLD_FRIEND, HOUSEMATE, NEIGHBOUR, COLLEAGUE, MANAGER, REPORT, CLIENT, CLASSMATE, TEACHER, TEAMMATE, CHILDMINDER,
    PARENT_OF_FRIEND, LANDLORD, TENANT, TRADESPERSON, MARKETPLACE, ACQUAINTANCE,
    /** The persona itself, a person of the world; no contact and no side of a conversation has it. */
    SELF;

    public boolean isFamily() {
        return switch (this) {
            case PARTNER, PARENT, STEP_PARENT, GRANDPARENT, SIBLING, CHILD, IN_LAW, RELATIVE -> true;
            default -> false;
        };
    }
}
