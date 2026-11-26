// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.persona;

import java.time.YearMonth;
import org.oblivios.decoy.content.persona.PersonaProfile.TravelMode;

/** One trip of the past year: its month, length, country code and means of travel. */
public record TripAnswer(YearMonth month, int days, String country, TravelMode mode) {}
