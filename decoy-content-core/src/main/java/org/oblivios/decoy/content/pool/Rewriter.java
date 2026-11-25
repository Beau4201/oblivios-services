// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.pool;

import java.util.List;
import org.oblivios.decoy.content.style.StyleVector;

/**
 * Rewrites a filled conversation in the styles of its writers, so that no stored text equals its pool source. The local
 * model implements it on the device; the library has none (the design of the local model runtime).
 */
public interface Rewriter {
    /** {@code owner} is the persona's style in this conversation, {@code others} those of the other sides in side order. */
    FilledConversation rewrite(FilledConversation c, StyleVector owner, List<StyleVector> others, String language);
}
