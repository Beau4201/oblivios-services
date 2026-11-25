// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content;

import java.time.ZoneId;
import java.util.Locale;

/** What the owner and the device give at setup: the decoy is the owner without the sensitive items (ADR-315 (1)). */
public record PersonaInputs(String ownerName, String city, String countryCode, ZoneId zone, Locale locale) {}
