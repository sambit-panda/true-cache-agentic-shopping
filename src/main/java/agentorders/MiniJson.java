package agentorders;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tiny dependency-free JSON: writes any Map/List/String/Number/Boolean tree, reads full JSON. */
public final class MiniJson {
    private MiniJson() {}

    // ------------------------------------------------------------------ write
    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        w(sb, o);
        return sb.toString();
    }

    private static void w(StringBuilder sb, Object o) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof String s) {
            quote(sb, s);
        } else if (o instanceof Boolean || o instanceof Number) {
            sb.append(o);
        } else if (o instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                w(sb, e.getValue());
            }
            sb.append('}');
        } else if (o instanceof Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object x : c) {
                if (!first) sb.append(',');
                first = false;
                w(sb, x);
            }
            sb.append(']');
        } else {
            quote(sb, String.valueOf(o));
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------ read
    /** Parses any JSON value into immutable Map / List / String / Long / Double / Boolean / null. */
    public static Object read(String s) {
        int[] i = {0};
        ws(s, i);
        Object v = val(s, i);
        ws(s, i);
        if (i[0] != s.length()) throw new IllegalArgumentException("trailing data at " + i[0]);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readObject(String s) {
        Object v = read(s);
        if (!(v instanceof Map)) throw new IllegalArgumentException("expected a JSON object");
        return (Map<String, Object>) v;
    }

    private static void ws(String s, int[] i) {
        while (i[0] < s.length() && Character.isWhitespace(s.charAt(i[0]))) i[0]++;
    }

    private static void expect(String s, int[] i, char c) {
        if (i[0] >= s.length() || s.charAt(i[0]++) != c) throw new IllegalArgumentException("expected " + c + " at " + (i[0] - 1));
    }

    private static Object val(String s, int[] i) {
        char c = s.charAt(i[0]);
        if (c == '"') return str(s, i);
        if (c == '{') return obj(s, i);
        if (c == '[') return arr(s, i);
        if (s.startsWith("true", i[0])) { i[0] += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i[0])) { i[0] += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i[0])) { i[0] += 4; return null; }
        int st = i[0];
        while (i[0] < s.length() && ",}] \t\r\n".indexOf(s.charAt(i[0])) < 0) i[0]++;
        String num = s.substring(st, i[0]);
        return (num.contains(".") || num.contains("e") || num.contains("E")) ? (Object) Double.valueOf(num) : (Object) Long.valueOf(num);
    }

    private static Map<String, Object> obj(String s, int[] i) {
        expect(s, i, '{');
        Map<String, Object> out = new LinkedHashMap<>();
        ws(s, i);
        if (s.charAt(i[0]) == '}') { i[0]++; return Collections.unmodifiableMap(out); }
        while (true) {
            ws(s, i);
            String k = str(s, i);
            ws(s, i);
            expect(s, i, ':');
            ws(s, i);
            out.put(k, val(s, i));
            ws(s, i);
            char c = s.charAt(i[0]++);
            if (c == '}') return Collections.unmodifiableMap(out);
            if (c != ',') throw new IllegalArgumentException("expected , or } at " + (i[0] - 1));
        }
    }

    private static List<Object> arr(String s, int[] i) {
        expect(s, i, '[');
        List<Object> out = new ArrayList<>();
        ws(s, i);
        if (s.charAt(i[0]) == ']') { i[0]++; return Collections.unmodifiableList(out); }
        while (true) {
            ws(s, i);
            out.add(val(s, i));
            ws(s, i);
            char c = s.charAt(i[0]++);
            if (c == ']') return Collections.unmodifiableList(out);
            if (c != ',') throw new IllegalArgumentException("expected , or ] at " + (i[0] - 1));
        }
    }

    private static String str(String s, int[] i) {
        expect(s, i, '"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = s.charAt(i[0]++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            char e = s.charAt(i[0]++);
            switch (e) {
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> { sb.append((char) Integer.parseInt(s.substring(i[0], i[0] + 4), 16)); i[0] += 4; }
                default -> sb.append(e);
            }
        }
    }
}
