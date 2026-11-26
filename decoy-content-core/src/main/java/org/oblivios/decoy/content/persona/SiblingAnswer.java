// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.persona;

import org.oblivios.decoy.content.persona.PersonaProfile.Gender;

/** The answer about one brother or sister; a {@code null} field is open. */
public record SiblingAnswer(Integer age, Gender gender, String calledAs) {}
