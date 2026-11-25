// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Set;

public record Alarm(LocalTime time, Set<DayOfWeek> days) {}
