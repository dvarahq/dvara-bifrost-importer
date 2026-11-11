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

import com.dvarahq.tools.bifrost.DvaraConfig.Fallback;
import com.dvarahq.tools.bifrost.DvaraConfig.ModelLimit;
import com.dvarahq.tools.bifrost.DvaraConfig.Policy;
import com.dvarahq.tools.bifrost.DvaraConfig.Provider;
import com.dvarahq.tools.bifrost.DvaraConfig.Route;
import com.dvarahq.tools.bifrost.DvaraConfig.WeightedProvider;
import com.dvarahq.tools.bifrost.DvaraConfig.Workspace;

import java.util.Map;

/**
 * Writes {@link DvaraConfig} as {@code gateway.yaml} text, with comments a person can follow.
 *
 * <p>Written by hand rather than through a YAML library so the file can carry comments, and so that each value
 * is written one known way. Text values are double-quoted, with {@code $} escaped: the gateway replaces
 * {@code ${NAME}} in the raw file before it parses it, so a model name or URL that happened to contain one
 * would otherwise be read as a variable. Variable references are the only unquoted {@code ${...}} in the file.
 */
final class GatewayYamlWriter {

    private GatewayYamlWriter() {}

    static String write(DvaraConfig config, String sourceName) {
        StringBuilder y = new StringBuilder();
        y.append("# gateway.yaml for the DVARA open-source gateway, made by dvara-bifrost-importer from ")
                .append(oneLine(sourceName)).append(".\n");
        y.append("# Read import-report.md before you use it: it lists what did not carry over, and why.\n");
        y.append("# No secret is in this file. The gateway reads each ${NAME} from its environment at startup.\n\n");

        writeProviders(y, config);
        writeWorkspaces(y, config);
        writeRoutes(y, config);
        writeModelLimits(y, config);
        writePolicies(y, config);

        y.append("# api_keys: none yet. Until you add one, the gateway starts and refuses every call.\n");
        y.append("# Mint one with: java -jar dvara-gateway-server-<version>-app.jar --generate-key --name <name>"
                + " --workspace default\n");
        y.append("# and paste the api_keys entry it prints here. The file holds the key's SHA-256, never the key.\n");
        return y.toString();
    }

    private static void writeProviders(StringBuilder y, DvaraConfig config) {
        if (config.providers.isEmpty()) {
            y.append("# providers: none could be mapped. See import-report.md.\n\n");
            return;
        }
        y.append("providers:\n");
        for (Provider p : config.providers.values()) {
            y.append("  - type: ").append(p.type().yamlType).append('\n');
            if (p.apiKey() != null) {
                y.append("    api_key: ").append(p.apiKey().yaml()).append('\n');
            }
            if (p.baseUrl() != null) {
                y.append("    base_url: ").append(url(p.baseUrl())).append('\n');
            }
            if (p.accessKey() != null) {
                y.append("    access_key: ").append(p.accessKey().yaml()).append('\n');
            }
            if (p.secretKey() != null) {
                y.append("    secret_key: ").append(p.secretKey().yaml()).append('\n');
            }
            if (p.region() != null) {
                y.append("    region: ").append(url(p.region())).append('\n');
            }
        }
        y.append('\n');
    }

    private static void writeWorkspaces(StringBuilder y, DvaraConfig config) {
        y.append("workspaces:\n");
        for (Workspace w : config.workspaces) {
            y.append("  - id: ").append(quote(w.id())).append('\n');
            if (w.name() != null) {
                y.append("    name: ").append(quote(w.name())).append('\n');
            }
            Map<String, Object> metadata = w.metadata();
            if (metadata.isEmpty()) {
                y.append("    # Without these, PII and prompt-injection detections are logged and the request goes"
                        + " through.\n");
                y.append("    # metadata:\n");
                y.append("    #   pii.action: REDACT\n");
                y.append("    #   guardrail.action: BLOCK\n");
            } else {
                y.append("    metadata:\n");
                metadata.forEach((k, v) -> y.append("      ").append(quote(k)).append(": ")
                        .append(v instanceof Number n ? n.toString() : quote(String.valueOf(v))).append('\n'));
            }
        }
        y.append('\n');
    }

    private static void writeRoutes(StringBuilder y, DvaraConfig config) {
        if (config.routes.isEmpty()) {
            return;
        }
        y.append("# Each route matches the model a caller sends. pinned_model_version, when set, is the model the\n");
        y.append("# provider is sent instead: that is how a Bifrost provider/model name or key alias carries over.\n");
        y.append("routes:\n");
        for (Route r : config.routes) {
            y.append("  - id: ").append(quote(r.id())).append('\n');
            y.append("    model: ").append(quote(r.model())).append('\n');
            // Weighted even with one provider: the default strategy picks a provider by the model's name, which
            // would ignore this route's provider for a model it does not recognise.
            y.append("    strategy: weighted\n");
            y.append("    providers:\n");
            for (WeightedProvider p : r.providers()) {
                y.append("      - provider: ").append(p.type().yamlType).append('\n');
                y.append("        weight: ").append(p.weight()).append('\n');
            }
            if (r.pinnedModel() != null) {
                y.append("    pinned_model_version: ").append(quote(r.pinnedModel())).append('\n');
            }
            if (!r.fallbacks().isEmpty()) {
                y.append("    fallbacks:\n");
                for (Fallback f : r.fallbacks()) {
                    y.append("      - provider: ").append(f.type().yamlType).append('\n');
                    y.append("        model: ").append(quote(f.model())).append('\n');
                }
            }
        }
        y.append('\n');
    }

    private static void writeModelLimits(StringBuilder y, DvaraConfig config) {
        if (config.modelLimits.isEmpty()) {
            return;
        }
        y.append("model_limits:\n");
        for (ModelLimit l : config.modelLimits) {
            y.append("  - model: ").append(quote(l.model())).append('\n');
            y.append("    provider: ").append(l.provider().yamlType).append('\n');
            if (l.contextTokens() != null) {
                y.append("    context_tokens: ").append(l.contextTokens()).append('\n');
            }
            if (l.outputTokens() != null) {
                y.append("    output_tokens: ").append(l.outputTokens()).append('\n');
            }
        }
        y.append('\n');
    }

    private static void writePolicies(StringBuilder y, DvaraConfig config) {
        if (config.policies.isEmpty()) {
            return;
        }
        y.append("# A policy checks the model the caller names and the model the route sends, so both are listed.\n");
        y.append("policies:\n");
        for (Policy p : config.policies) {
            y.append("  - id: ").append(quote(p.id())).append('\n');
            if (p.workspace() != null) {
                y.append("    workspace: ").append(quote(p.workspace())).append('\n');
            }
            y.append("    status: ACTIVE\n");
            y.append("    dsl: |\n");
            for (String line : p.dsl().split("\n")) {
                y.append("      ").append(line).append('\n');
            }
        }
        y.append('\n');
    }

    /** The properties file for settings gateway.yaml cannot carry; empty when there are none. */
    static String writeProperties(DvaraConfig config, String sourceName) {
        if (config.properties.isEmpty()) {
            return "";
        }
        StringBuilder p = new StringBuilder();
        p.append("# Gateway settings from ").append(oneLine(sourceName))
                .append(" that gateway.yaml cannot carry. Pass this file with\n");
        p.append("# --spring.config.additional-location=file:dvara-import.properties\n");
        config.properties.forEach((k, v) -> p.append(k).append('=').append(v).append('\n'));
        return p.toString();
    }

    private static String url(UrlValue value) {
        if (value.isLiteral()) {
            return quote(value.literal());
        }
        return "${" + value.envName() + "}" + value.suffix();
    }

    /** A YAML double-quoted scalar. {@code $} is escaped so the gateway never reads it as a variable. */
    static String quote(String s) {
        StringBuilder q = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> q.append("\\\"");
                case '\\' -> q.append("\\\\");
                case '\n' -> q.append("\\n");
                case '\r' -> q.append("\\r");
                case '\t' -> q.append("\\t");
                case '$' -> q.append("\\u0024");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        q.append(String.format("\\u%04x", (int) c));
                    } else {
                        q.append(c);
                    }
                }
            }
        }
        return q.append('"').toString();
    }

    private static String oneLine(String s) {
        return s.replaceAll("[\\r\\n]+", " ");
    }
}
