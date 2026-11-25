// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.util.List;

/** History (the 90 days the browser keeps), expired cookies only (no valid session), and bookmarked domains. */
public record BrowserPlan(List<Visit> history, List<Cookie> cookies, List<String> bookmarks) {}
