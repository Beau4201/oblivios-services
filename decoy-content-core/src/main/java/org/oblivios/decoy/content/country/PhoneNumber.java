// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.country;

/** A decoy number in E.164 form. It never belongs to a real person: packs draw only from verified free blocks. */
public record PhoneNumber(String e164) {}
