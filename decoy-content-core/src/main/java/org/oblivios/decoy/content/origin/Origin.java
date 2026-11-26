// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.origin;

import java.util.Objects;

/**
 * Where a fact of the phone comes from: a setup answer, an entry of the world (a person, a place, a standing choice) or
 * an event of the chronicle (life story design, L1). The id names the answer, the entry or the event.
 */
public record Origin(Kind kind, String id) {
    public enum Kind { ANSWER, WORLD, EVENT }

    public Origin {
        Objects.requireNonNull(kind, "kind");
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("origin: an origin needs an id");
        }
    }

    public static Origin answer(String question) {
        return new Origin(Kind.ANSWER, question);
    }

    public static Origin world(String id) {
        return new Origin(Kind.WORLD, id);
    }

    public static Origin event(String id) {
        return new Origin(Kind.EVENT, id);
    }
}
