// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.pool;

/** An age range, both ends inclusive. */
public record AgeBand(int from, int to) {
    public AgeBand {
        if (from < 0 || from > to || to > 110) {
            throw new IllegalArgumentException("age band " + from + "-" + to);
        }
    }

    public boolean contains(int age) {
        return age >= from && age <= to;
    }
}
