// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.world;

import java.util.Objects;
import org.oblivios.decoy.content.origin.Origin;

/**
 * The one bank, operator and supermarket of the life, and the name of the home network. {@code energy},
 * {@code insurer} and {@code workSsid} are empty until a source gives their names (ADR-336).
 */
public record StandingChoices(String bank, String operator, String supermarket, String energy, String insurer,
        String homeSsid, String workSsid, Origin origin) {
    public StandingChoices {
        Objects.requireNonNull(origin, "origin");
    }
}
