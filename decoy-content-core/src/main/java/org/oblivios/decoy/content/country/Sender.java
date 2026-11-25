// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.country;

/** A service that sends messages; the name is shown as plain text, never with a logo (sources record). */
public record Sender(String name, SenderKind kind) {}
