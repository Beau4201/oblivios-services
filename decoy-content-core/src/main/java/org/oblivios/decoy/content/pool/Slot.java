// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.pool;

/**
 * A placeholder in a pool text, written {@code {NAME_A}}: names, places and clubs are never written into the pool, the
 * plan fills them. {@code NAME_A} to {@code NAME_H} name the sides, {@code NAME_OTHER} someone outside the conversation.
 */
public enum Slot {
    NAME_A, NAME_B, NAME_C, NAME_D, NAME_E, NAME_F, NAME_G, NAME_H, NAME_OTHER, PLACE_CAFE, PLACE_SHOP, SUPERMARKET, CITY, CLUB, DAY,
    TIME, AMOUNT
}
