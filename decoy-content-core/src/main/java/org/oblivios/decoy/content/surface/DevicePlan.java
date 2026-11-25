// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

public record DevicePlan(String deviceName, List<String> languages, List<String> keyboardLayouts, List<String> learnedWords,
        List<String> ringtones, LocalTime dndFrom, LocalTime dndTo, Map<String, String> accessibility,
        Map<String, String> defaultApps, String backupState) {}
