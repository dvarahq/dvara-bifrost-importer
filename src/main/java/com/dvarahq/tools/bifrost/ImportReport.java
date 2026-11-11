/*
 * Copyright 2026 DVARA Labs, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dvarahq.tools.bifrost;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The diff report: every part of the Bifrost file, and what became of it.
 *
 * <p>Nothing in the input is dropped without a line here. A key the importer does not know is reported as
 * not carried over, rather than passed by in silence.
 */
final class ImportReport {

    enum Kind {
        /** Carried over and behaves the same. */
        MAPPED("Maps cleanly"),
        /** Carried over, but it behaves differently. Each line says how. */
        CHANGED("Maps, with a difference"),
        /** Not carried over: no equivalent in the open gateway. Each line says why. */
        NOT_MAPPED("No equivalent"),
        /** Something the operator has to do before the gateway serves traffic. */
        ACTION("You need to"),
        /** What the gateway does that the Bifrost file did not ask for. */
        ADDED("What DVARA adds");

        final String heading;

        Kind(String heading) {
            this.heading = heading;
        }
    }

    /**
     * One line of the report.
     *
     * @param where the part of the Bifrost file, such as {@code providers.openai.keys[1] (openai-secondary)}
     * @param text  what happened, and for anything not carried over, why
     */
    record Finding(Kind kind, String where, String text) {}

    private final List<Finding> findings = new ArrayList<>();

    void add(Kind kind, String where, String text) {
        findings.add(new Finding(kind, where, text));
    }

    List<Finding> findings() {
        return List.copyOf(findings);
    }

    List<Finding> of(Kind kind) {
        return findings.stream().filter(f -> f.kind() == kind).toList();
    }

    Map<Kind, Integer> counts() {
        Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
        for (Kind k : Kind.values()) {
            counts.put(k, 0);
        }
        findings.forEach(f -> counts.merge(f.kind(), 1, Integer::sum));
        return counts;
    }

    /** The report as Markdown, for {@code import-report.md}. */
    String toMarkdown(String sourceName, boolean propertiesWritten) {
        StringBuilder md = new StringBuilder();
        md.append("# Bifrost import report\n\n");
        md.append("Source: `").append(sourceName).append("`\n\n");
        md.append("Written: `gateway.yaml`");
        if (propertiesWritten) {
            md.append(" and `dvara-import.properties`");
        }
        md.append(". No secret value was copied from the source: each key is named by its environment variable.\n\n");

        Map<Kind, Integer> counts = counts();
        md.append("| | Count |\n|---|---:|\n");
        for (Kind k : Kind.values()) {
            md.append("| ").append(k.heading).append(" | ").append(counts.get(k)).append(" |\n");
        }
        md.append('\n');

        for (Kind k : new Kind[] {Kind.ACTION, Kind.NOT_MAPPED, Kind.CHANGED, Kind.MAPPED, Kind.ADDED}) {
            List<Finding> list = of(k);
            if (list.isEmpty()) {
                continue;
            }
            md.append("## ").append(k.heading).append("\n\n");
            for (Finding f : list) {
                md.append("- ");
                if (f.where() != null && !f.where().isBlank()) {
                    md.append("`").append(f.where().replace("`", "'")).append("`: ");
                }
                md.append(f.text()).append('\n');
            }
            md.append('\n');
        }
        return md.toString();
    }
}
