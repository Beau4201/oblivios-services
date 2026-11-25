// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.pool;

import java.util.List;
import java.util.Set;
import org.oblivios.decoy.content.persona.PersonaProfile.Fact;
import org.oblivios.decoy.content.surface.SmsMessage;

/**
 * A pool conversation placed in the plan: its slots filled, its messages dated. {@code contactIds} holds the one
 * contact of a one-to-one conversation, or the members of a group in side order. {@code rewritten} is true once the
 * {@link Rewriter} has rewritten it; a plan for a device holds no other (validator rule {@code R-UNREWRITTEN}).
 */
public record FilledConversation(String poolId, String situation, GroupKind group, List<String> contactIds, Set<Fact> requires,
        List<SmsMessage> messages, boolean rewritten) {
    public FilledConversation {
        contactIds = List.copyOf(contactIds);
        requires = Set.copyOf(requires);
        messages = List.copyOf(messages);
    }
}
