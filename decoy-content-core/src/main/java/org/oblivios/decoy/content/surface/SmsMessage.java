// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.time.Instant;

/** One message; {@code from} is the contact id of the sender of an incoming group message, {@code null} otherwise. */
public record SmsMessage(Instant at, boolean incoming, String body, boolean read, String from) {}
