package agentorders;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Stateless agent loop over a self-hosted Responses-compatible inference endpoint. */
@SuppressWarnings("unchecked")
public final class ResponsesAgent {
    public interface Transport {
        String post(String requestJson) throws IOException;
    }

    /** HTTP transport for a local or privately hosted model server; no provider API key is needed. */
    public static final class HttpTransport implements Transport {
        private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        private final URI url;
        private final String bearerToken;

        public HttpTransport(String url) {
            this(url, System.getenv("LLM_BEARER_TOKEN"));
        }

        public HttpTransport(String url, String bearerToken) {
            this.url = URI.create(url);
            this.bearerToken = bearerToken;
        }

        @Override
        public String post(String body) throws IOException {
            HttpRequest.Builder builder = HttpRequest.newBuilder(url)
                    .timeout(Duration.ofSeconds(180))
                    .header("Content-Type", "application/json");
            if (bearerToken != null && !bearerToken.isBlank()) {
                builder.header("Authorization", "Bearer " + bearerToken);
            }
            HttpRequest req = builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
            try {
                HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() / 100 != 2) throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
                return r.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }
    }

    public record Usage(long inputTokens, long cachedTokens, long outputTokens) {
        public static final Usage ZERO = new Usage(0, 0, 0);

        public Usage plus(Usage o) {
            return new Usage(inputTokens + o.inputTokens, cachedTokens + o.cachedTokens, outputTokens + o.outputTokens);
        }

        public double cachedRatio() {
            return inputTokens == 0 ? 0 : (double) cachedTokens / inputTokens;
        }
    }

    public record Result(String text, Usage usage, int modelCalls, int toolCalls, boolean hadToolError) {}

    private final Transport transport;
    private final ToolRuntime runtime;
    private final String model;
    private final String instructions;
    private final int maxOutputTokens;
    private final List<Tool> modelTools;
    private final Set<String> allowedToolNames;

    public ResponsesAgent(Transport transport, ToolRuntime runtime, String model, String instructions,
                          int maxOutputTokens, Collection<Tool> modelTools) {
        this.transport = transport;
        this.runtime = runtime;
        this.model = model;
        this.instructions = instructions;
        this.maxOutputTokens = maxOutputTokens;
        this.modelTools = List.copyOf(modelTools);
        this.allowedToolNames = this.modelTools.stream().map(Tool::name).collect(Collectors.toUnmodifiableSet());
    }

    public ResponsesAgent(Transport transport, ToolRuntime runtime, String model, String instructions,
                          int maxOutputTokens) {
        this(transport, runtime, model, instructions, maxOutputTokens, runtime.tools());
    }

    /** Names of the tools this agent is allowed to call. */
    public Set<String> allowedToolNames() { return allowedToolNames; }

    /**
     * Replays the conversation history on each request so this works with stateless self-hosted
     * servers. Static instructions and tools remain at the start of every request, allowing the
     * inference engine to reuse their KV/prefix cache. Tool results stay local to this process.
     */
    public Result run(Session session, String userMessage, int maxModelCalls) throws IOException {
        List<Object> input = new ArrayList<>();
        input.add(Map.of("role", "user", "content", userMessage));
        Usage total = Usage.ZERO;
        int toolCalls = 0;
        boolean hadToolError = false;
        for (int call = 1; call <= maxModelCalls; call++) {
            Map<String, Object> resp = MiniJson.readObject(transport.post(MiniJson.write(request(input))));
            if (resp.get("error") != null) throw new IOException("Model server error: " + MiniJson.write(resp.get("error")));
            if ("incomplete".equals(resp.get("status"))) {
                throw new IOException("response incomplete: " + MiniJson.write(resp.get("incomplete_details")));
            }
            total = total.plus(usage(resp));

            List<Object> results = new ArrayList<>();
            List<Object> assistantItems = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            for (Object o : (List<Object>) resp.get("output")) {
                Map<String, Object> item = (Map<String, Object>) o;
                String type = (String) item.get("type");
                if ("function_call".equals(type)) {
                    assistantItems.add(Map.of(
                            "type", "function_call",
                            "call_id", item.get("call_id"),
                            "name", item.get("name"),
                            "arguments", item.get("arguments")));
                    toolCalls++;
                    String callId = (String) item.get("call_id");
                    Map<String, Object> out = execute(session, (String) item.get("name"), (String) item.get("arguments"), callId);
                    hadToolError |= out.containsKey("error");
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("type", "function_call_output");
                    r.put("call_id", callId);
                    r.put("output", MiniJson.write(out));
                    results.add(r);
                } else if ("message".equals(type)) {
                    // llama.cpp requires replayed Responses messages to carry type="message".
                    assistantItems.add(Map.of("type", "message", "role", "assistant", "content", item.get("content")));
                    for (Object c : (List<Object>) item.get("content")) {
                        Map<String, Object> part = (Map<String, Object>) c;
                        if ("output_text".equals(part.get("type"))) text.append(part.get("text"));
                    }
                }
            }
            if (results.isEmpty()) {
                String answer = text.toString().replaceAll("<think>\\s*</think>\\s*", "").strip();
                return new Result(answer, total, call, toolCalls, hadToolError);
            }
            List<Object> fullHistory = new ArrayList<>(input);
            fullHistory.addAll(assistantItems);
            fullHistory.addAll(results);
            input = fullHistory;
        }
        throw new IllegalStateException("agent did not finish within " + maxModelCalls + " model calls");
    }

    private Map<String, Object> execute(Session s, String name, String argumentsJson, String callId) {
        if (!allowedToolNames.contains(name)) return Tools.error("tool_not_available");
        Map<String, Object> args;
        try {
            args = argumentsJson == null || argumentsJson.isBlank() ? Map.of() : MiniJson.readObject(argumentsJson);
        } catch (RuntimeException e) {
            return Tools.error("invalid_arguments: not valid JSON");
        }
        return runtime.call(s, name, args, callId);
    }

    private Map<String, Object> request(List<Object> input) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("model", model);
        r.put("instructions", instructions);
        r.put("tools", ToolSchemas.responsesTools(modelTools));
        r.put("input", input);
        r.put("parallel_tool_calls", false);
        r.put("max_output_tokens", maxOutputTokens);
        r.put("temperature", 0);
        return r;
    }

    private static Usage usage(Map<String, Object> resp) {
        Map<String, Object> u = (Map<String, Object>) resp.get("usage");
        if (u == null) return Usage.ZERO;
        Map<String, Object> details = (Map<String, Object>) u.get("input_tokens_details");
        return new Usage(num(u.get("input_tokens")), details == null ? 0 : num(details.get("cached_tokens")),
                num(u.get("output_tokens")));
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }
}
