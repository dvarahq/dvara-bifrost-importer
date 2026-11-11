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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/** The Bifrost configs under {@code src/test/resources/fixtures}, written after Bifrost's documented examples. */
final class Fixtures {

    static final List<String> ALL = List.of("quickstart.json", "load-balancing.json", "self-hosted.json",
            "restricted.json", "enterprise.json");

    /** Literal secrets planted in {@code enterprise.json}; none may appear in any output. */
    static final List<String> PLANTED_SECRETS = List.of("LITERAL-OPENAI-SECRET-0001", "LITERAL-PG-PASSWORD-0002",
            "LITERAL-ENCRYPTION-0003", "LITERAL-ADMIN-PASSWORD-0004", "LITERAL-VK-SECRET-0005",
            "LITERAL-HEADER-SECRET-0006", "LITERAL-PROXY-PASSWORD-0007", "LITERAL-URL-SECRET-0008",
            "LITERAL-OTEL-SECRET-0009", "LITERAL-GUARDRAIL-SECRET-0010", "LITERAL-AWS-SECRET-0011",
            "LITERAL-SETUP-TOKEN-0012");

    private Fixtures() {}

    static String read(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("no fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Path path(String name) {
        try {
            return Path.of(Fixtures.class.getResource("/fixtures/" + name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    static BifrostImporter.Result importFixture(String name) {
        return BifrostImporter.importJson(read(name));
    }
}
