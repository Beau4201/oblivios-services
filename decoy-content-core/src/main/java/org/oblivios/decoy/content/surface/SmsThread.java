// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.util.List;
import org.oblivios.decoy.content.country.SenderKind;

/**
 * One conversation. For a person the address is the contact's number and {@code senderKind} is null; for a
 * service the address is the sender's name (shown as plain text) and {@code senderKind} says which kind.
 */
public record SmsThread(String address, String title, SenderKind senderKind, List<SmsMessage> messages) {}
