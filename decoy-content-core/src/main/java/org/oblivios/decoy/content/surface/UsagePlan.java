// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Sessions of the retained window (28 days) and the last time each package was used. */
public record UsagePlan(List<UsageSession> sessions, Map<String, Instant> lastUsed) {}
