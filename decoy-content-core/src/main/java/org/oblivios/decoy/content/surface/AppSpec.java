// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

/**
 * An app the owner picked at setup for the decoy (ADR-317). {@code domain} is the service the app talks to, which
 * the browser history and vault can share. (The {@code needsPhone} field of the plan went with the sign-up assistant.)
 */
public record AppSpec(String pkg, String domain, boolean hasServer) {}
