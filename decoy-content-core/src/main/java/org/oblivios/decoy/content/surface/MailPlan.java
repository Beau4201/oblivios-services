// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.util.List;
import java.util.Optional;

/** The mail to append, oldest first, or the notice that refuses the mailbox step (no message at all). */
public record MailPlan(List<MailMessage> messages, Optional<String> refusedNotice) {
    public MailPlan {
        messages = List.copyOf(messages);
    }
}
