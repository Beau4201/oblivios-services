// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.util.List;

public record CalendarPlan(List<CalEvent> events, List<Alarm> alarms, List<Note> notes) {}
