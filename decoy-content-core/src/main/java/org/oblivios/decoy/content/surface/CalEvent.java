// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.time.Instant;

/** A calendar entry. A birthday or holiday is an all-day entry at UTC midnight (the Android convention). */
public record CalEvent(String title, Instant start, boolean yearly) {}
