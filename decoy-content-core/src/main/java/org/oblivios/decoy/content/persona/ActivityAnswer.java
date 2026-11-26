// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.persona;

import java.time.DayOfWeek;
import java.time.LocalTime;
import org.oblivios.decoy.content.persona.PersonaProfile.ActivityKind;
import org.oblivios.decoy.content.persona.PersonaProfile.FriendGroup;

/** One fixed weekly activity; {@code group} is the friend group met there, or {@code null} for none. */
public record ActivityAnswer(ActivityKind kind, DayOfWeek day, LocalTime start, int minutes, FriendGroup group) {}
