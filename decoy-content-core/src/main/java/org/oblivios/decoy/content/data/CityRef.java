// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.data;

/** A city of the place data: a municipality, with the middle of where its people live and its school region. */
public record CityRef(String municipality, String name, double lat, double lon, String schoolRegion) {}
