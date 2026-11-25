// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.country;

/** A pack has no number range that is verified as free or fictional; it must not invent numbers. */
public final class NoVerifiedRange extends Exception {
    public NoVerifiedRange(String message) {
        super(message);
    }
}
