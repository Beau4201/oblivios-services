// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

/** A saved login of the decoy vault. The username is a handle, never an address of a real mail domain. */
public record VaultEntry(String domain, String username, String password) {}
