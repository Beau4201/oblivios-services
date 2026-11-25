// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.pool;

import java.util.List;
import java.util.Set;
import org.oblivios.decoy.content.persona.PersonaProfile.Fact;

/**
 * One generated conversation of the pool with its labels. {@code group} is {@code null} for a one-to-one
 * conversation; {@code mix} is the language mixed in, empty when none; {@code requires} the facts it presumes.
 */
public record PoolConversation(String id, String situation, String language, GroupKind group, List<PoolSide> sides,
        List<String> topics, Set<Fact> requires, String mix, List<PoolMessage> messages) {

    public PoolConversation {
        sides = List.copyOf(sides);
        topics = List.copyOf(topics);
        requires = Set.copyOf(requires);
        messages = List.copyOf(messages);
    }

    public boolean isGroup() {
        return group != null;
    }
}
