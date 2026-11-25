// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Notification history and the modification time of each package's cache directory. */
public record TracesPlan(List<NotificationRecord> notifications, Map<String, Instant> cacheDirTimes) {}
