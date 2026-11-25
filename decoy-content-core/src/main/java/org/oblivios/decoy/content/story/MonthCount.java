// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.story;

import java.time.YearMonth;

/** The owner's media of one month, counted per category on the owner's device; bytes is the sum of their sizes. */
public record MonthCount(YearMonth month, int camera, int screenshots, int saved, long bytes) {}
