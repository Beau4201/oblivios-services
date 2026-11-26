// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check;

import java.util.List;

/** A rule over many plans: a value that a population of decoys shares, or a spread it lacks. */
public interface PopulationRule {
    String id();

    void check(List<PhoneViews> plans, List<Violation> out);
}
