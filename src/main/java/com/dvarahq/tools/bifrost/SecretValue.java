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

import java.util.Map;
import java.util.regex.Pattern;

/**
 * A secret, as the output may hold it: the name of an environment variable, never a value.
 *
 * <p>There is no constructor that takes a value. A literal key found in the input is replaced by a
 * variable name before it reaches this class, so nothing downstream can write it out by mistake.
 */
record SecretValue(String envName) {

    /** The names the gateway's loader resolves. Anything else would be left in the file as text. */
    static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** Bifrost's way to name an environment variable in config.json. */
    static final String BIFROST_ENV_PREFIX = "env.";

    /** Bifrost's way to name a secret held in a vault. */
    static final String BIFROST_VAULT_PREFIX = "vault.";

    SecretValue {
        if (envName == null || !ENV_NAME.matcher(envName).matches()) {
            throw new IllegalArgumentException("not an environment variable name: " + envName);
        }
    }

    /** How {@code gateway.yaml} writes it: the gateway resolves {@code ${NAME}} at startup. */
    String yaml() {
        return "${" + envName + "}";
    }

    static boolean isValidName(String name) {
        return name != null && ENV_NAME.matcher(name).matches();
    }

    /** What a Bifrost secret field holds, read without ever keeping the value. */
    enum Kind {
        /** Not set, or an empty string. */
        NONE,
        /** {@code env.NAME}. */
        ENV,
        /** {@code vault.path}: the gateway does not read a vault. */
        VAULT,
        /** The secret itself, written in the file. */
        LITERAL
    }

    /**
     * A Bifrost secret field, classified. {@code envName} is set only for {@link Kind#ENV}; it may still be a
     * name the gateway cannot read, which the caller checks with {@link #isValidName}.
     */
    record Ref(Kind kind, String envName) {}

    /**
     * Reads a Bifrost secret field: a string ({@code env.NAME}, {@code vault.path} or the value itself), or the
     * object form Bifrost also accepts ({@code {"value": ..., "env_var": ..., "from_env": true}} or
     * {@code {"ref": ..., "type": ...}}).
     */
    static Ref classify(Object raw) {
        if (raw == null) {
            return new Ref(Kind.NONE, null);
        }
        if (raw instanceof Map<?, ?> m) {
            Object ref = m.get("ref");
            if (ref instanceof String r && !r.isBlank()) {
                return classify(r);
            }
            Object envVar = m.get("env_var");
            if (envVar instanceof String e && !e.isBlank() && Boolean.TRUE.equals(m.get("from_env"))) {
                return classify(e.startsWith(BIFROST_ENV_PREFIX) ? e : BIFROST_ENV_PREFIX + e);
            }
            return classify(m.get("value"));
        }
        String s = String.valueOf(raw).strip();
        if (s.isEmpty()) {
            return new Ref(Kind.NONE, null);
        }
        if (s.startsWith(BIFROST_ENV_PREFIX)) {
            return new Ref(Kind.ENV, s.substring(BIFROST_ENV_PREFIX.length()).strip());
        }
        if (s.startsWith(BIFROST_VAULT_PREFIX)) {
            return new Ref(Kind.VAULT, null);
        }
        return new Ref(Kind.LITERAL, null);
    }
}
