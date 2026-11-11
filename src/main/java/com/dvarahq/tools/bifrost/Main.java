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

import com.dvarahq.tools.bifrost.ImportReport.Kind;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code java -jar dvara-bifrost-importer.jar config.json -o out/}
 *
 * <p>Reads a Bifrost config.json and writes {@code gateway.yaml}, {@code import-report.md} and, when there is
 * something to put in it, {@code dvara-import.properties}. It reads only the file it is given, opens no network
 * connection and writes nothing outside the output folder.
 */
public final class Main {

    static final int OK = 0;
    static final int BAD_INPUT = 1;
    static final int USAGE = 2;
    static final int WOULD_OVERWRITE = 3;

    static final String GATEWAY_YAML = "gateway.yaml";
    static final String REPORT = "import-report.md";
    static final String PROPERTIES = "dvara-import.properties";

    private static final String HELP = """
            Usage: java -jar dvara-bifrost-importer.jar <config.json> [-o <folder>] [--force]

            Reads a Bifrost config.json and writes, into <folder> (default: dvara-import):
              gateway.yaml             the DVARA open-source gateway's configuration
              import-report.md         what maps cleanly, what changed, what has no equivalent, and what to do next
              dvara-import.properties  retries, backoff and timeouts, when the source sets any

            No secret value is copied: each key is written as ${NAME}, read from the environment.

            Options:
              -o, --out <folder>   where to write (created if missing)
              --force              replace files already in <folder>
              -h, --help           show this help
            """;

    private Main() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream stdout, PrintStream stderr) {
        String input = null;
        Path outDir = Path.of("dvara-import");
        boolean force = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-h", "--help" -> {
                    stdout.print(HELP);
                    return OK;
                }
                case "-o", "--out" -> {
                    if (i + 1 >= args.length) {
                        stderr.println("error: " + a + " needs a folder");
                        return USAGE;
                    }
                    outDir = Path.of(args[++i]);
                }
                case "--force" -> force = true;
                default -> {
                    if (a.startsWith("-")) {
                        stderr.println("error: unknown option " + a);
                        stderr.print(HELP);
                        return USAGE;
                    }
                    if (input != null) {
                        stderr.println("error: one config file at a time");
                        return USAGE;
                    }
                    input = a;
                }
            }
        }
        if (input == null) {
            stderr.print(HELP);
            return USAGE;
        }

        Path source = Path.of(input);
        String text;
        try {
            text = Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException e) {
            stderr.println("error: cannot read " + source + ": " + e.getMessage());
            return BAD_INPUT;
        }

        BifrostImporter.Result result;
        try {
            result = BifrostImporter.importJson(text);
        } catch (BifrostImporter.ImportException e) {
            stderr.println("error: " + source + ": " + e.getMessage());
            return BAD_INPUT;
        }

        String sourceName = source.getFileName().toString();
        String yaml = GatewayYamlWriter.write(result.config(), sourceName);
        String properties = GatewayYamlWriter.writeProperties(result.config(), sourceName);
        String report = result.report().toMarkdown(sourceName, !properties.isEmpty());

        List<String> names = new ArrayList<>(List.of(GATEWAY_YAML, REPORT));
        if (!properties.isEmpty()) {
            names.add(PROPERTIES);
        }
        if (!force) {
            for (String name : names) {
                if (Files.exists(outDir.resolve(name))) {
                    stderr.println("error: " + outDir.resolve(name) + " exists. Use --force to replace it.");
                    return WOULD_OVERWRITE;
                }
            }
        }
        try {
            Files.createDirectories(outDir);
            Files.writeString(outDir.resolve(GATEWAY_YAML), yaml, StandardCharsets.UTF_8);
            Files.writeString(outDir.resolve(REPORT), report, StandardCharsets.UTF_8);
            if (!properties.isEmpty()) {
                Files.writeString(outDir.resolve(PROPERTIES), properties, StandardCharsets.UTF_8);
            } else {
                Files.deleteIfExists(outDir.resolve(PROPERTIES));
            }
        } catch (IOException e) {
            stderr.println("error: cannot write to " + outDir + ": " + e.getMessage());
            return BAD_INPUT;
        }

        Map<Kind, Integer> counts = result.report().counts();
        stdout.println("Wrote " + String.join(", ", names) + " to " + outDir);
        stdout.println("  " + result.config().providers.size() + " providers, " + result.config().routes.size()
                + " routes, " + result.config().workspaces.size() + " workspaces, "
                + result.config().policies.size() + " policies");
        for (Kind k : Kind.values()) {
            stdout.println("  " + k.heading + ": " + counts.get(k));
        }
        stdout.println("Read " + outDir.resolve(REPORT) + " before you start the gateway.");
        return OK;
    }
}
