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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A strict JSON reader (RFC 8259) that builds plain data: {@link Map} (in file order), {@link List},
 * {@link String}, {@link Long} or {@link Double}, {@link Boolean} and null.
 *
 * <p>Written here so the tool has no runtime dependency. It builds no objects from the input, and it
 * reports a duplicate key, which Go's decoder would silently let the last one win.
 */
final class Json {

    /** Input that is not JSON. The message never quotes the input, which may hold a secret. */
    static final class ParseException extends RuntimeException {
        ParseException(String message) {
            super(message);
        }
    }

    /** Deeper than any real config; stops a crafted file from exhausting the stack. */
    private static final int MAX_DEPTH = 200;

    private final String s;
    private int pos;
    private int depth;
    private final List<String> duplicates = new ArrayList<>();

    private Json(String s) {
        this.s = s;
    }

    /** The parsed value, and the keys that appeared twice in one object (the last one is kept, as in Go). */
    record Parsed(Object value, List<String> duplicateKeys) {}

    static Parsed parse(String text) {
        Json j = new Json(text.startsWith("﻿") ? text.substring(1) : text);
        j.skipWhitespace();
        Object value = j.value("");
        j.skipWhitespace();
        if (j.pos != j.s.length()) {
            throw j.error("unexpected text after the end of the document");
        }
        return new Parsed(value, List.copyOf(j.duplicates));
    }

    private Object value(String path) {
        if (pos >= s.length()) {
            throw error("unexpected end of the document");
        }
        char c = s.charAt(pos);
        return switch (c) {
            case '{' -> object(path);
            case '[' -> array(path);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) {
                    yield number();
                }
                throw error("unexpected character");
            }
        };
    }

    private Map<String, Object> object(String path) {
        enter();
        pos++;
        Map<String, Object> map = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            depth--;
            return map;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("expected a key in double quotes");
            }
            String key = string();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            String childPath = path.isEmpty() ? key : path + "." + key;
            Object v = value(childPath);
            if (map.containsKey(key)) {
                map.remove(key);
                duplicates.add(childPath);
            }
            map.put(key, v);
            skipWhitespace();
            char c = next();
            if (c == '}') {
                depth--;
                return map;
            }
            if (c != ',') {
                throw error("expected ',' or '}'");
            }
        }
    }

    private List<Object> array(String path) {
        enter();
        pos++;
        List<Object> list = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            depth--;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(value(path + "[" + list.size() + "]"));
            skipWhitespace();
            char c = next();
            if (c == ']') {
                depth--;
                return list;
            }
            if (c != ',') {
                throw error("expected ',' or ']'");
            }
        }
    }

    private String string() {
        pos++;
        StringBuilder b = new StringBuilder();
        while (true) {
            if (pos >= s.length()) {
                throw error("a string is not closed");
            }
            char c = s.charAt(pos++);
            if (c == '"') {
                return b.toString();
            }
            if (c < 0x20) {
                throw error("a control character inside a string");
            }
            if (c != '\\') {
                b.append(c);
                continue;
            }
            char e = next();
            switch (e) {
                case '"' -> b.append('"');
                case '\\' -> b.append('\\');
                case '/' -> b.append('/');
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                case 'u' -> {
                    if (pos + 4 > s.length()) {
                        throw error("a \\u escape is cut short");
                    }
                    try {
                        b.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("a \\u escape is not four hex digits");
                    }
                    pos += 4;
                }
                default -> throw error("an unknown escape in a string");
            }
        }
    }

    private Object number() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        if (peek() == '0') {
            pos++;
        } else if (isDigit(peek())) {
            while (isDigit(peek())) {
                pos++;
            }
        } else {
            throw error("a number has no digits");
        }
        boolean integral = true;
        if (peek() == '.') {
            integral = false;
            pos++;
            if (!isDigit(peek())) {
                throw error("a number has no digits after '.'");
            }
            while (isDigit(peek())) {
                pos++;
            }
        }
        if (peek() == 'e' || peek() == 'E') {
            integral = false;
            pos++;
            if (peek() == '+' || peek() == '-') {
                pos++;
            }
            if (!isDigit(peek())) {
                throw error("a number has no digits in its exponent");
            }
            while (isDigit(peek())) {
                pos++;
            }
        }
        String text = s.substring(start, pos);
        if (integral) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ex) {
                return Double.parseDouble(text);
            }
        }
        return Double.parseDouble(text);
    }

    private Object literal(String word, Object value) {
        if (!s.startsWith(word, pos)) {
            throw error("unexpected character");
        }
        pos += word.length();
        return value;
    }

    private void enter() {
        if (++depth > MAX_DEPTH) {
            throw error("nested more than " + MAX_DEPTH + " levels deep");
        }
    }

    private void skipWhitespace() {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return;
            }
            pos++;
        }
    }

    private char peek() {
        return pos < s.length() ? s.charAt(pos) : '\0';
    }

    private char next() {
        if (pos >= s.length()) {
            throw error("unexpected end of the document");
        }
        return s.charAt(pos++);
    }

    private void expect(char c) {
        if (next() != c) {
            throw error("expected '" + c + "'");
        }
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** Names the line and column, never the text there. */
    private ParseException error(String what) {
        int line = 1;
        int col = 1;
        for (int i = 0; i < Math.min(pos, s.length()); i++) {
            if (s.charAt(i) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        return new ParseException(what + " at line " + line + ", column " + col);
    }
}
