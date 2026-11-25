// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.country;

/** A city with its display name and approximate centre, as a country pack lists it. */
public record City(String name, double lat, double lon) {}
