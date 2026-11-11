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

/**
 * A base URL for the output: either a literal URL, which is not a secret and is copied, or a reference to an
 * environment variable, optionally followed by a path the gateway needs and Bifrost did not.
 *
 * @param literal the URL itself, or null when it comes from the environment
 * @param envName the variable that holds it, or null when it is literal
 * @param suffix  a path to add after the variable's value, such as {@code /openai} for Azure
 */
record UrlValue(String literal, String envName, String suffix) {

    static UrlValue literal(String url) {
        return new UrlValue(url, null, "");
    }

    static UrlValue fromEnv(String envName, String suffix) {
        if (!SecretValue.isValidName(envName)) {
            throw new IllegalArgumentException("not an environment variable name: " + envName);
        }
        return new UrlValue(null, envName, suffix == null ? "" : suffix);
    }

    boolean isLiteral() {
        return literal != null;
    }
}
