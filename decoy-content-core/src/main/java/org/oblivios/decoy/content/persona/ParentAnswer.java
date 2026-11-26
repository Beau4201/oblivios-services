// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.persona;

import org.oblivios.decoy.content.persona.PersonaProfile.Gender;

/** The answer about one parent; a {@code null} field is open. {@code calledAs} is what the persona calls the parent. */
public record ParentAnswer(Gender gender, Boolean alive, String calledAs) {}
