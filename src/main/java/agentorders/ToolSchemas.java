package agentorders;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool definitions in the Responses-compatible function-tool format. Keep tool order and descriptions
 * deterministic so inference servers can reuse the static policy + tool prefix. strict is false because
 * some parameters are optional (strict mode requires every property to be listed as required).
 */
public final class ToolSchemas {
    private ToolSchemas() {}

    public static List<Map<String, Object>> responsesTools(Collection<Tool> tools) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Tool t : tools) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "function");
            m.put("name", t.name());
            m.put("description", t.description());
            m.put("parameters", Param.schema(t.params()));
            m.put("strict", false);
            out.add(m);
        }
        return out;
    }

    public static String responsesToolsJson(Collection<Tool> tools) {
        return MiniJson.write(responsesTools(tools));
    }
}
