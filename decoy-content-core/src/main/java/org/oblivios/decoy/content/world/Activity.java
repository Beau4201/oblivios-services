// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.world;

/** How much of each thing this persona does, each a factor on the usual amount. */
public record Activity(double calls, double texts, double photoBurst, double notes, double browsing, double callLength) {}
