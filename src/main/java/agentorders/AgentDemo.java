package agentorders;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs agent conversations against the shopping tools.
 *
 *   LLM_RESPONSES_URL set -> calls a self-hosted Responses-compatible model server
 *   otherwise             -> a scripted stand-in for the model, to show the loop and tool routing
 *
 * Env: LLM_RESPONSES_URL, LLM_MODEL (default qwen3-4b), optional LLM_BEARER_TOKEN,
 *      REDIS_ADDR (host:port) to use the Redis cache instead of the in-process one.
 * Args: --conversations N (default 3) --parallel K (default 1) --max-calls N (default 8)
 */
public final class AgentDemo {
    /** Stand-in for the model: replays canned Responses-API replies, one per call, in order. */
    public static final class ScriptedModel implements ResponsesAgent.Transport {
        public final List<String> requests = new ArrayList<>();
        private final List<Function<String, String>> steps;
        private int next;

        public ScriptedModel(List<Function<String, String>> steps) { this.steps = steps; }

        @Override
        public synchronized String post(String body) {
            requests.add(body);
            return steps.get(next++).apply(body);
        }
    }

    public static String toolCall(String respId, String callId, String name, String argsJson, long in, long cached) {
        return MiniJson.write(Map.of("id", respId, "status", "completed",
                "output", List.of(Map.of("type", "function_call", "call_id", callId, "name", name, "arguments", argsJson)),
                "usage", Map.of("input_tokens", in, "input_tokens_details", Map.of("cached_tokens", cached), "output_tokens", 20)));
    }

    public static String finalText(String respId, String text, long in, long cached) {
        return MiniJson.write(Map.of("id", respId, "status", "completed",
                "output", List.of(Map.of("type", "message", "role", "assistant",
                        "content", List.of(Map.of("type", "output_text", "text", text)))),
                "usage", Map.of("input_tokens", in, "input_tokens_details", Map.of("cached_tokens", cached), "output_tokens", 20)));
    }

    static ScriptedModel defaultScript() {
        Pattern orderId = Pattern.compile("ord_[0-9a-f]{12}");
        return new ScriptedModel(List.of(
                b -> toolCall("resp_1", "call_1", "search_products", "{\"category\":\"audio\"}", 0, 0),
                b -> toolCall("resp_2", "call_2", "check_stock", "{\"product_id\":102}", 0, 0),
                b -> toolCall("resp_3", "call_3", "place_order", "{\"product_id\":102,\"qty\":1}", 0, 0),
                b -> {
                    // the request also contains an example id in the tool description: take the LAST match
                    Matcher m = orderId.matcher(b);
                    String id = "ord_missing";
                    while (m.find()) id = m.group();
                    return toolCall("resp_4", "call_4", "get_order_status", "{\"order_id\":\"" + id + "\"}", 0, 0);
                },
                b -> finalText("resp_5", "Your order is placed and its status is PLACED.", 0, 0)));
    }

    static final List<String> PROMPTS = List.of(
            "Find the cheapest speaker in audio, check it is in stock, order one, and confirm the order status.",
            "I'd like two USB-C docks. Check stock first, then order them and confirm.",
            "Order one webcam, then tell me the status of that order.",
            "What audio products do you have? Order one pair of the studio headphones if they are in stock.");

    static int intArg(String[] a, String name, int def) {
        for (int i = 0; i < a.length - 1; i++) if (a[i].equals(name)) return Integer.parseInt(a[i + 1]);
        return def;
    }

    static ToolCache makeCache() {
        String addr = System.getenv("REDIS_ADDR");
        if (addr != null && !addr.isBlank()) {
            String[] hp = addr.split(":");
            RespClient c = RespClient.tryConnect(hp[0], Integer.parseInt(hp[1]));
            if (c != null) {
                System.out.println("cache: Redis at " + addr);
                return new RedisWriteThroughCache(c, "agentorders:" + System.currentTimeMillis() + ":", Duration.ofSeconds(30));
            }
            System.out.println("cache: Redis at " + addr + " not reachable, using the in-process cache");
        } else {
            System.out.println("cache: in-process");
        }
        return new WriteThroughCache(10_000, Duration.ofSeconds(30));
    }

    public static void main(String[] argv) throws Exception {
        int conversations = intArg(argv, "--conversations", 3);
        int parallel = intArg(argv, "--parallel", 1);
        int maxCalls = intArg(argv, "--max-calls", 8);
        String endpoint = System.getenv("LLM_RESPONSES_URL");
        boolean useLocalModel = endpoint != null && !endpoint.isBlank();
        String model = System.getenv().getOrDefault("LLM_MODEL", "qwen3-4b");

        SimDb db = new SimDb(300);
        Demo.seed(db);
        ToolRuntime rt = new ToolRuntime(db, makeCache());

        int prefixChars = AgentPolicy.INSTRUCTIONS.length() + ToolSchemas.responsesToolsJson(rt.tools()).length();
        System.out.printf("model: %s%n", useLocalModel ? model : "SCRIPTED (no local model endpoint; token counts are not meaningful)");
        System.out.printf("static prefix (instructions + tools): %d chars, roughly %d tokens%n",
                prefixChars, prefixChars / 4);
        if (useLocalModel) System.out.printf("self-hosted model calls: up to %d conversations x %d model calls each%n", conversations, maxCalls);

        ResponsesAgent.Transport shared = useLocalModel ? new ResponsesAgent.HttpTransport(endpoint) : null;
        Semaphore gate = new Semaphore(parallel);
        List<Future<String>> futures = new ArrayList<>();
        ResponsesAgent.Usage[] total = {ResponsesAgent.Usage.ZERO};
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < conversations; i++) {
                final int n = i;
                futures.add(ex.submit(() -> {
                    gate.acquire();
                    try {
                        Session s = Session.forCustomer(1 + n);
                        ResponsesAgent agent = new ResponsesAgent(useLocalModel ? shared : defaultScript(), rt, model,
                                AgentPolicy.INSTRUCTIONS, 2000, rt.tools());
                        ResponsesAgent.Result r = agent.run(s, PROMPTS.get(n % PROMPTS.size()), maxCalls);
                        synchronized (total) { total[0] = total[0].plus(r.usage()); }
                        return "conversation %d: %d model calls, %d tool calls, input=%d cached=%d output=%d%n    -> %s"
                                .formatted(n + 1, r.modelCalls(), r.toolCalls(), r.usage().inputTokens(),
                                        r.usage().cachedTokens(), r.usage().outputTokens(), r.text().strip());
                    } finally {
                        gate.release();
                    }
                }));
            }
            for (Future<String> f : futures) {
                try {
                    System.out.println(f.get());
                } catch (Exception e) {
                    System.out.println("conversation failed: " + e.getCause());
                }
            }
        }
        ResponsesAgent.Usage u = total[0];
        System.out.printf("%ntotals: input=%d cached=%d (%.0f%%) output=%d%n", u.inputTokens(), u.cachedTokens(), 100 * u.cachedRatio(), u.outputTokens());
        System.out.println("tool routes:");
        rt.routeCounts().forEach((k, v) -> System.out.printf("  %-60s %d%n", k, v));
        System.out.println("tool cache: " + rt.cache().stats());
    }
}
