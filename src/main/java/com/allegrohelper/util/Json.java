package com.allegrohelper.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON parser and writer.
 *
 * <p>Parsing produces {@link Map} (objects, insertion-ordered), {@link List}
 * (arrays), {@link String}, {@link Double} (numbers), {@link Boolean} and
 * {@code null}. Writing accepts the same types and produces two-space indented
 * output: non-ASCII characters are emitted literally and integral numbers are
 * printed without a decimal point.
 */
public final class Json {

    /** Not instantiable: the class is a namespace for {@link #parse} and {@link #write}. */
    private Json() {
    }

    /**
     * Parses any JSON value.
     *
     * @throws IllegalArgumentException on malformed input, including trailing
     *         characters after the value
     */
    public static Object parse(String s) {
        return new Parser(s).parseTopLevel();
    }

    /**
     * Parses JSON expected to be an object — {@code data.json} and the API
     * responses always are.
     *
     * @throws IllegalArgumentException if the input is malformed or its top
     *         level is not an object
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String s) {
        Object o = parse(s);
        if (o instanceof Map) {
            return (Map<String, Object>) o;
        }
        throw new IllegalArgumentException("Expected a JSON object at the top level");
    }

    /**
     * Serializes a value.
     *
     * <p>The output is also a valid JavaScript literal, which is what lets
     * {@code AllegroForm} embed a string straight into the JS it evaluates in
     * the page.
     *
     * @param pretty two-space indented and multi-line when true; compact when
     *               false (what the HTTP request bodies use)
     */
    public static String write(Object value, boolean pretty) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, pretty, 0);
        return sb.toString();
    }

    // ------------------------------------------------------------------ parse

    /** A recursive-descent parser over the input string, {@code i} being the read cursor. */
    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        /** Parses the single value the input must consist of, rejecting anything after it. */
        Object parseTopLevel() {
            Object v = parseValue();
            skipWs();
            if (i < s.length()) {
                throw err("trailing characters");
            }
            return v;
        }

        /** Parses whichever value starts at the cursor, dispatching on its first character. */
        Object parseValue() {
            skipWs();
            char c = peek();
            return switch (c) {
                case '{' -> parseObj();
                case '[' -> parseArr();
                case '"' -> parseStr();
                case 't', 'f' -> parseBool();
                case 'n' -> {
                    expect("null");
                    yield null;
                }
                default -> parseNum();
            };
        }

        /** Parses an object into an insertion-ordered map, so a round-trip keeps the key order. */
        Map<String, Object> parseObj() {
            LinkedHashMap<String, Object> m = new LinkedHashMap<>();
            i++; // consume '{'
            skipWs();
            if (peek() == '}') {
                i++;
                return m;
            }
            while (true) {
                skipWs();
                String k = parseStr();
                skipWs();
                if (peek() != ':') {
                    throw err("expected ':'");
                }
                i++;
                m.put(k, parseValue());
                skipWs();
                char c = peek();
                if (c == ',') {
                    i++;
                } else if (c == '}') {
                    i++;
                    return m;
                } else {
                    throw err("expected ',' or '}'");
                }
            }
        }

        /** Parses an array. */
        List<Object> parseArr() {
            ArrayList<Object> a = new ArrayList<>();
            i++; // consume '['
            skipWs();
            if (peek() == ']') {
                i++;
                return a;
            }
            while (true) {
                a.add(parseValue());
                skipWs();
                char c = peek();
                if (c == ',') {
                    i++;
                } else if (c == ']') {
                    i++;
                    return a;
                } else {
                    throw err("expected ',' or ']'");
                }
            }
        }

        /** Parses a quoted string, decoding the backslash escapes including {@code \\uXXXX}. */
        String parseStr() {
            if (peek() != '"') {
                throw err("expected string");
            }
            i++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) {
                    throw err("unterminated string");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> throw err("invalid escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        /** Parses a number. Every number becomes a {@link Double}; {@link #formatNumber} prints integral ones without a decimal point. */
        Double parseNum() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            if (i == start) {
                throw err("unexpected character '" + peek() + "'");
            }
            return Double.parseDouble(s.substring(start, i));
        }

        /** Parses {@code true} or {@code false}. */
        Boolean parseBool() {
            if (peek() == 't') {
                expect("true");
                return Boolean.TRUE;
            }
            expect("false");
            return Boolean.FALSE;
        }

        /** Consumes a literal word at the cursor, or fails. */
        void expect(String word) {
            if (!s.startsWith(word, i)) {
                throw err("expected '" + word + "'");
            }
            i += word.length();
        }

        /** Advances the cursor past any whitespace. */
        void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        /** The character at the cursor, failing at end of input rather than returning a sentinel. */
        char peek() {
            if (i >= s.length()) {
                throw err("unexpected end of input");
            }
            return s.charAt(i);
        }

        /** A parse error naming the cursor position, so a bad {@code data.json} can be found. */
        RuntimeException err(String message) {
            return new IllegalArgumentException("JSON parse error at index " + i + ": " + message);
        }
    }

    // ------------------------------------------------------------------ write

    /**
     * Appends any value. Types outside the supported set fall back to their
     * {@code toString} as a JSON string, which is what lets a {@code Path} or an
     * enum be handed to {@link #write} directly.
     */
    private static void writeValue(StringBuilder sb, Object value, boolean pretty, int depth) {
        switch (value) {
            case null -> sb.append("null");
            case Map<?, ?> map -> writeObject(sb, map, pretty, depth);
            case List<?> list -> writeArray(sb, list, pretty, depth);
            case String str -> writeString(sb, str);
            case Boolean b -> sb.append(b.booleanValue() ? "true" : "false");
            case Number n -> sb.append(formatNumber(n));
            default -> writeString(sb, value.toString());
        }
    }

    /** Appends an object, keys in the map's own iteration order. */
    private static void writeObject(StringBuilder sb, Map<?, ?> map, boolean pretty, int depth) {
        if (map.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append('{');
        int n = map.size();
        int idx = 0;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            newlineIndent(sb, pretty, depth + 1);
            writeString(sb, String.valueOf(e.getKey()));
            sb.append(pretty ? ": " : ":");
            writeValue(sb, e.getValue(), pretty, depth + 1);
            if (++idx < n) {
                sb.append(',');
            }
        }
        newlineIndent(sb, pretty, depth);
        sb.append('}');
    }

    /** Appends an array. */
    private static void writeArray(StringBuilder sb, List<?> list, boolean pretty, int depth) {
        if (list.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append('[');
        int n = list.size();
        for (int idx = 0; idx < n; idx++) {
            newlineIndent(sb, pretty, depth + 1);
            writeValue(sb, list.get(idx), pretty, depth + 1);
            if (idx + 1 < n) {
                sb.append(',');
            }
        }
        newlineIndent(sb, pretty, depth);
        sb.append(']');
    }

    /** Breaks the line and indents two spaces per level — a no-op in compact mode. */
    private static void newlineIndent(StringBuilder sb, boolean pretty, int depth) {
        if (pretty) {
            sb.append('\n');
            sb.append("  ".repeat(depth));
        }
    }

    /**
     * Formats a number, printing an integral value without a decimal point —
     * parsing turns every number into a {@link Double}, so without this a
     * round-tripped {@code data.json} would grow {@code 20.0} where it had 20.
     * Non-finite values become {@code null}, which JSON has no literal for.
     */
    private static String formatNumber(Number n) {
        if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte) {
            return n.toString();
        }
        double d = n.doubleValue();
        if (!Double.isFinite(d)) {
            return "null";
        }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return Long.toString((long) d);
        }
        return Double.toString(d);
    }

    /**
     * Appends a quoted string, escaping what JSON requires and leaving
     * everything else literal — Polish text stays readable in {@code data.json}
     * rather than turning into a wall of {@code \\uXXXX}.
     */
    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
