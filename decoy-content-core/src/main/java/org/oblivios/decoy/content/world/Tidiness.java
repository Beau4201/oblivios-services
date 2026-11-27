// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.world;

/** The loose ends of this persona's phone, each a share: a real phone is never wholly tidy (ADR-337). */
public record Tidiness(double unusedContacts, double unexplainedMessages, double fullTraceEvents, double offPhoneCauses) {}
