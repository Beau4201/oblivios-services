// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check;

import java.util.List;

/** A rule that needs the story: an item has an origin, and agrees with it. */
public interface OriginRule {
    String id();

    void check(PhoneViews v, PlanStory story, List<Violation> out);
}
