// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.pool.gate;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.oblivios.decoy.content.pool.PoolConversation;
import org.oblivios.decoy.content.pool.PoolMessage;
import org.oblivios.decoy.content.pool.PoolSide;

/** Writes a conversation in pool format version 1, as {@code PoolReader} reads it. */
final class PoolWriter {
    private PoolWriter() {}

    static String write(PoolConversation c) {
        StringBuilder sb = new StringBuilder("#conv id=").append(c.id())
                .append(" sit=").append(c.situation())
                .append(" lang=").append(c.language())
                .append(" group=").append(c.group() == null ? "-" : c.group().name())
                .append(" rel=").append(sides(c, s -> s.relation() == null ? "-" : s.relation().name()))
                .append(" age=").append(sides(c, s -> s.age().from() + "-" + s.age().to()))
                .append(" gender=").append(sides(c, s -> s.gender().name()))
                .append(" reg=").append(sides(c, s -> s.register().name()))
                .append(" topics=").append(list(c.topics()))
                .append(" requires=").append(list(c.requires().stream().sorted().map(Enum::name).toList()))
                .append(" mix=").append(c.mix().isEmpty() ? "-" : c.mix())
                .append('\n');
        for (PoolMessage m : c.messages()) {
            sb.append(String.format(Locale.ROOT, "%d %02d:%02d %c ", m.day(), m.minute() / 60, m.minute() % 60, m.side()))
                    .append(m.text()).append('\n');
        }
        return sb.toString();
    }

    private static String sides(PoolConversation c, Function<PoolSide, String> label) {
        return c.sides().stream().map(label).collect(Collectors.joining("/"));
    }

    private static String list(List<String> values) {
        return values.isEmpty() ? "-" : String.join(",", values);
    }
}
