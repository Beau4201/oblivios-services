// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.time.Instant;

/**
 * One event the roll-forward adds. {@code kind} is {@code CALL} or {@code SMS}; {@code detail} is
 * {@code contactId|TYPE|seconds} for a call ({@link CallType} name) and {@code contactId|IN or OUT|text} for a message.
 */
public record RollForwardEvent(String kind, Instant when, String detail) {}
