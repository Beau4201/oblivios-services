// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.origin;

/** A record that states a fact and names where the fact comes from; the origin is never null. */
public interface Sourced {
    Origin origin();
}
