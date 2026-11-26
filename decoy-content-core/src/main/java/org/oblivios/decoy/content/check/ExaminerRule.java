// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check;

import java.time.Instant;
import java.util.List;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.country.CountryPack;

/** A rule an examiner can judge from the views and the setup answers alone. */
public interface ExaminerRule {
    String id();

    void check(PhoneViews v, Persona persona, CountryPack pack, Instant now, List<Violation> out);
}
