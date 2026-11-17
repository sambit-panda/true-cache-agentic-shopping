package agentorders;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One tool parameter: drives both argument validation and the JSON schema shown to the model. */
public record Param(String name, String type, String description, boolean required,
                    Long min, Long max, List<String> enumValues, Long defaultValue) {

    public static Param requiredInt(String name, String desc, long min, long max) {
        return new Param(name, "integer", desc, true, min, max, null, null);
    }

    public static Param optionalInt(String name, String desc, long min, long max, long def) {
        return new Param(name, "integer", desc, false, min, max, null, def);
    }

    public static Param requiredString(String name, String desc) {
        return new Param(name, "string", desc, true, null, null, null, null);
    }

    public static Param requiredEnum(String name, String desc, List<String> values) {
        return new Param(name, "string", desc, true, null, null, values, null);
    }

    /** Validated arguments with defaults applied. */
    public record Args(Map<String, Object> values) {
        public long lng(String n) { return (Long) values.get(n); }
        public int integer(String n) { return ((Long) values.get(n)).intValue(); }
        public String str(String n) { return (String) values.get(n); }
        @Override public String toString() { return values.toString(); }
    }

    /** @throws IllegalArgumentException with a message that is safe to return to the model. */
    public static Args validate(List<Param> params, Map<String, Object> raw) {
        Map<String, Param> byName = new HashMap<>();
        for (Param p : params) byName.put(p.name(), p);
        for (String k : raw.keySet()) {
            if (!byName.containsKey(k)) throw new IllegalArgumentException("unknown argument '" + k + "'");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Param p : params) {
            Object v = raw.get(p.name());
            if (v == null) {
                if (p.required()) throw new IllegalArgumentException("missing required argument '" + p.name() + "'");
                if (p.defaultValue() != null) out.put(p.name(), p.defaultValue());
                continue;
            }
            if (p.type().equals("integer")) {
                if (!(v instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) {
                    throw new IllegalArgumentException("'" + p.name() + "' must be an integer");
                }
                long l = n.longValue();
                if ((p.min() != null && l < p.min()) || (p.max() != null && l > p.max())) {
                    throw new IllegalArgumentException("'" + p.name() + "' must be between " + p.min() + " and " + p.max());
                }
                out.put(p.name(), l);
            } else {
                if (!(v instanceof String s)) throw new IllegalArgumentException("'" + p.name() + "' must be a string");
                if (p.enumValues() != null && !p.enumValues().contains(s)) {
                    throw new IllegalArgumentException("'" + p.name() + "' must be one of " + p.enumValues());
                }
                out.put(p.name(), s);
            }
        }
        return new Args(out);
    }

    /** JSON-schema object for the tool's input. */
    public static Map<String, Object> schema(List<Param> params) {
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> required = new java.util.ArrayList<>();
        for (Param p : params) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("type", p.type());
            d.put("description", p.description());
            if (p.min() != null) d.put("minimum", p.min());
            if (p.max() != null) d.put("maximum", p.max());
            if (p.enumValues() != null) d.put("enum", p.enumValues());
            props.put(p.name(), d);
            if (p.required()) required.add(p.name());
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }
}
