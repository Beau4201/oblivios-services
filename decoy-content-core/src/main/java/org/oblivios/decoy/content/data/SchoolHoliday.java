// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.data;

import java.time.LocalDate;

/**
 * Days off, from and to inclusive: a school holiday of a region, or one kept holiday, whose name is its id and whose
 * region is empty.
 */
public record SchoolHoliday(String name, String region, LocalDate from, LocalDate to) {}
