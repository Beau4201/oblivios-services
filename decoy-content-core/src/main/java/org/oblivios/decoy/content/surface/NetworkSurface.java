// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import org.oblivios.decoy.content.story.Place;
import org.oblivios.decoy.content.story.PlaceKind;

/** Saved Wi-Fi networks at the places of the story, and paired Bluetooth accessories. */
public final class NetworkSurface {
    private static final List<String> ACCESSORIES = List.of("Car", "Headphones", "Watch", "Speaker", "Earbuds");

    private NetworkSurface() {}

    public static NetworkPlan generate(SurfaceContext ctx) {
        RandomGenerator r = ctx.seed().stream("network");
        List<WifiNetwork> wifi = new ArrayList<>();
        List<String> patterns = ctx.pack().homeWifiPatterns();
        for (Place place : ctx.timeline().places()) {
            String ssid;
            switch (place.kind()) {
                case HOME -> ssid = fill(patterns.get(r.nextInt(patterns.size())), r);
                case WORK -> ssid = fill("WORK-####", r);
                case CAFE -> ssid = place.name() + " guest";
                default -> {
                    continue;
                }
            }
            wifi.add(new WifiNetwork(ssid, place, Events.randomInstant(r, ctx.timeline().phoneStart(), ctx.now(), ctx.timeline().zone(),
                    LocalTime.of(8, 0), LocalTime.of(20, 0))));
        }
        if (ctx.currentSsid() != null && wifi.stream().noneMatch(w -> w.ssid().equals(ctx.currentSsid()))) {
            wifi.add(new WifiNetwork(ctx.currentSsid(), null, ctx.timeline().phoneStart().plus(Duration.ofDays(r.nextInt(5)))));
        }

        List<BluetoothPair> bluetooth = new ArrayList<>();
        int count = 1 + r.nextInt(4);
        List<String> names = new ArrayList<>(ACCESSORIES);
        for (int i = 0; i < count; i++) {
            String name = names.remove(r.nextInt(names.size()));
            byte[] mac = new byte[6];
            for (int k = 0; k < 6; k++) {
                mac[k] = (byte) r.nextInt(256);
            }
            mac[0] = (byte) ((mac[0] & 0xFC) | 0x02); // locally administered, unicast
            Instant pairedAt = Events.randomInstant(r, ctx.timeline().phoneStart(), ctx.now(), ctx.timeline().zone(),
                    LocalTime.of(8, 0), LocalTime.of(22, 0));
            bluetooth.add(new BluetoothPair(name, hex(mac), pairedAt));
        }
        return new NetworkPlan(wifi, bluetooth);
    }

    private static String fill(String pattern, RandomGenerator r) {
        StringBuilder sb = new StringBuilder();
        for (char ch : pattern.toCharArray()) {
            sb.append(ch == '#' ? (char) ('0' + r.nextInt(10)) : ch);
        }
        return sb.toString();
    }

    private static String hex(byte[] mac) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mac.length; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(String.format("%02X", mac[i] & 0xFF));
        }
        return sb.toString();
    }
}
