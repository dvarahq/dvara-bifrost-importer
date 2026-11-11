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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonTest {

    @Test
    void readsPlainDataInFileOrder() {
        Json.Parsed p = Json.parse("""
                {"b": 1, "a": [true, false, null, -2.5e1, 0, "x"], "c": {"d": "e"}}""");
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) p.value();
        assertThat(m.keySet()).containsExactly("b", "a", "c");
        assertThat(m.get("b")).isEqualTo(1L);
        assertThat(m.get("a")).isEqualTo(java.util.Arrays.asList(true, false, null, -25.0, 0L, "x"));
        assertThat(m.get("c")).isEqualTo(Map.of("d", "e"));
        assertThat(p.duplicateKeys()).isEmpty();
    }

    @Test
    void readsEveryEscape() {
        Object v = Json.parse("\"a\\\"b\\\\c\\/d\\n\\t\\u00e9\\b\\f\\r\"").value();
        assertThat(v).isEqualTo("a\"b\\c/d\n\té\b\f\r");
    }

    @Test
    void aDuplicateKeyKeepsTheLastValueAndIsNamed() {
        Json.Parsed p = Json.parse("{\"a\": {\"k\": 1, \"k\": 2}}");
        assertThat(((Map<?, ?>) ((Map<?, ?>) p.value()).get("a")).get("k")).isEqualTo(2L);
        assertThat(p.duplicateKeys()).containsExactly("a.k");
    }

    @Test
    void aByteOrderMarkIsAllowed() {
        assertThat(Json.parse("\uFEFF{}").value()).isEqualTo(Map.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "{\"a\" 1}", "{\"a\": 1,}", "[1,]", "{'a': 1}", "{\"a\": 01}", "{\"a\": tru}",
            "{} {}", "{\"a\": \"line\nbreak\"}", "\"\\x\"", "{\"a\": .5}", "// comment\n{}"})
    void invalidJsonIsRefused(String text) {
        assertThatThrownBy(() -> Json.parse(text)).isInstanceOf(Json.ParseException.class);
    }

    @Test
    void anErrorNamesTheLineAndColumnButNeverTheText() {
        assertThatThrownBy(() -> Json.parse("{\n  \"value\": sk-THE-SECRET\n}"))
                .hasMessageContaining("line 2, column 12")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("THE-SECRET"));
    }

    @Test
    void deepNestingIsRefusedRatherThanOverflowingTheStack() {
        String deep = "[".repeat(10_000) + "]".repeat(10_000);
        assertThatThrownBy(() -> Json.parse(deep)).hasMessageContaining("nested more than");
    }
}
