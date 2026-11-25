// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.pool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The conversations picked for one plan: per contact id, the group threads, the reserve the roll-forward draws its
 * texts from, and a warning per contact that got fewer conversations than its relation asks.
 */
public record Picked(Map<String, List<FilledConversation>> byContact, List<FilledConversation> groups, List<FilledConversation> reserve,
        List<String> warnings) {
    public Picked {
        byContact = Collections.unmodifiableMap(new LinkedHashMap<>(byContact)); // keeps the contact order
        groups = List.copyOf(groups);
        reserve = List.copyOf(reserve);
        warnings = List.copyOf(warnings);
    }
}
