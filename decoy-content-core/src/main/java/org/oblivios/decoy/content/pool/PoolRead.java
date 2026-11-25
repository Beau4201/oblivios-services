// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.pool;

import java.util.List;

/** What a pool file gave: the conversations that passed, and one error per skipped conversation, each with its line. */
public record PoolRead(List<PoolConversation> conversations, List<String> errors) {
    public PoolRead {
        conversations = List.copyOf(conversations);
        errors = List.copyOf(errors);
    }
}
