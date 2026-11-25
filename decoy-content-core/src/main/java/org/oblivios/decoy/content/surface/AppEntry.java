// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.time.Instant;

public record AppEntry(String pkg, Instant installed, String version, long dataBytes, long cacheBytes) {}
