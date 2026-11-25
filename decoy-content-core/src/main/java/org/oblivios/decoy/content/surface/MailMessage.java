// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.time.Instant;

/**
 * One message to append to the decoy mailbox. {@code from} is the display name of a sender of the country pack, never
 * a person; the writer builds the address, and a message appended from a brand carries no DKIM signature, which the
 * writer's record states as a limit (Task 17).
 */
public record MailMessage(String from, String subject, String body, Instant date) {}
