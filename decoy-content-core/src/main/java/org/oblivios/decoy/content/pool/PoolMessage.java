// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.pool;

/** One message of a pool conversation: its day from the start, its minute of the day, its writer and its text. */
public record PoolMessage(int day, int minute, char side, String text) {}
