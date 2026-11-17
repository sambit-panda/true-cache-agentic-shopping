# AI shopping agent with Redis LangCache and Oracle True Cache

This demo shows three separate cache layers in one agent application:

1. **Redis LangCache** semantically reuses completed answers for stable, catalog-only questions. A hit skips both model inference and the database tool call.
2. **Oracle True Cache** serves catalog reads when the agent runs on a LangCache miss, and for eligible catalog requests that bypass semantic response caching.
3. **llama.cpp prompt-prefix caching** reuses the model's KV state for the fixed policy and tool schemas across repeated model calls.

The model is an open-weight Qwen3 4B GGUF running inside the cluster. The inference endpoint is private to the Kubernetes cluster and uses a Responses-compatible JSON protocol; the demo does not call a hosted OpenAI service or require a hosted-model API key. The Qwen3-4B model card lists Apache 2.0, and llama.cpp publishes its server image for `linux/amd64` and other platforms. See [Qwen3-4B-GGUF](https://huggingface.co/Qwen/Qwen3-4B-GGUF) and [llama.cpp Docker images](https://github.com/ggml-org/llama.cpp/blob/master/docs/docker.md).

## What the demo demonstrates

```text
Stable catalog question
   |
   v
Redis LangCache -- hit --> return cached answer
   |
   +-- miss --> catalog-only agent --> llama.cpp / Qwen3-4B
                     |
                     +-- search_products --> Oracle APP_TC True Cache --> primary

Live inventory question (always bypasses LangCache)
   |
   v
local agent --> search_products --> Oracle APP_TC True Cache
           \-> check_stock -----> Oracle APP primary (exact current quantity)
```

The application is the bridge between these cache layers: it decides which requests are safe to answer from a semantic response, which tool reads may use True Cache, and which checks must use the primary. LangCache and True Cache do not share entries or speak to each other directly. A semantic hit avoids model and tool work; True Cache lowers primary read demand for eligible database reads that still reach a tool. llama.cpp's prefix cache only reuses model prompt state and does not cache answers or database rows.

The Oracle demo exposes only read tools. It does not place, update, or cancel orders. LangCache is restricted to an agent that can call only `search_products`; its answer cache uses a 30-second TTL. Inventory and order prompts must bypass semantic response caching. Stock is read from the primary because checkout needs an exact current value.

## Evaluation on the supplied CPU-only Kubernetes node

I compared the CPU runtimes against the same shopping policy and read-only tool flow.

| Candidate | Result | Decision |
|---|---|---|
| vLLM CPU, Qwen3-1.7B BF16 | Reused 3,712 of 3,799 prompt tokens on a repeated prompt (97.7%), but in the full shopping-agent prompt it returned a plan as text instead of selecting tools. | Cache worked; tool selection was not reliable enough for this demo. |
| vLLM CPU, Qwen3-4B BF16 | Model loading crossed the 18 GiB container memory bound before the server became ready. | Too large in BF16 for the chosen safe limit on this node. |
| llama.cpp, Qwen3-4B-GGUF Q4_K_M | Completed the full read-only flow twice and reported prefix-cache reuse. Peak container memory was 2.93 GiB under an 8 GiB limit. | **Selected.** It fits and makes the required tool calls. |

The selected flow returned the same product and stock result on both runs: the Bluetooth Speaker at $79.00, with 40 units in stock. Each run made 3 model calls and 2 tool calls: one `search_products` read through `APP_TC` and one `check_stock` read through `APP`.

| Run | Model-loop time | Input tokens | Reused cached tokens | Reused share |
|---|---:|---:|---:|---:|
| 1, cold prefix after pod restart | 45.7 s | 5,307 | 3,469 | 65.4% |
| 2, warm repeat | 13.7 s | 5,307 | 5,160 | 97.2% |

The llama.cpp metrics endpoint agreed: 8,629 prompt tokens reused and 1,985 evaluated across the two runs. The application cache was bypassed, writes were disabled, and the model server remained on a ClusterIP service. This supports the prompt-cache and database-routing story. It is a two-run demonstration on one CPU node, not a load test or a measured True Cache hit-ratio/latency benchmark. Local inference removes hosted per-request model billing, while using CPU, memory, storage, and node capacity.

## Run the local model server in Kubernetes

The included manifest creates an isolated namespace, resource quota, deployment, and ClusterIP service. It pins the llama.cpp container digest used for the successful run and downloads the 4-bit Qwen model on the first start.

```sh
kubectl apply -f deploy/llama-cpp-qwen3.yaml
kubectl get pods -n ai-shopping-local-demo -w
```

The model download requires outbound access to Hugging Face on first start. Weights are kept under `/u01/truecache/ai-shopping-local-demo/model-cache` on the node; change that `hostPath` if the node uses a different persistent volume. The service is available inside the namespace at `http://llama-cpp:8080/v1/responses`, or from another namespace at `http://llama-cpp.ai-shopping-local-demo.svc.cluster.local:8080/v1/responses`.

The deployment is CPU-only, uses 3 inference threads and one model slot, and has an 8 GiB memory limit. Its service is ClusterIP only, with no ingress or load balancer. Add cluster authentication/network controls before making a model endpoint available to broader workloads.

## Run the Oracle-backed read-only demo

Build with JDK 21 and Maven from a host or pod that can reach both Oracle services and the model service. Oracle listener redirects can require running inside the cluster or on the cluster node; a simple port-forward may not be sufficient.

```sh
mvn -Poracle -DskipTests package

export LLM_RESPONSES_URL=http://llama-cpp.ai-shopping-local-demo.svc.cluster.local:8080/v1/responses
export LLM_MODEL=qwen3-4b
export ORA_USER=<read-only demo schema user>
export ORA_PASSWORD=<password>
export ORA_PRIMARY_URL='jdbc:oracle:thin:@//<primary-host>:1521/<primary-service>'
export ORA_CACHE_URL='jdbc:oracle:thin:@//<true-cache-host>:1521/<true-cache-service>'

./run-oracle-agent.sh
```

Set Oracle values from your approved environment configuration; credentials are not stored in this project. If the model endpoint is configured with a bearer token, set `LLM_BEARER_TOKEN` for the Java client. The included cluster manifest does not configure model authentication because it keeps the service private to the cluster.

The runner repeats the same prompt twice. It reports the model's input and cached-token counts, elapsed time, number of model/tool calls, database routes, and the application-cache bypass. The default prompt asks for the cheapest audio speaker and its exact current stock.

## Run the Redis LangCache + True Cache flow

Create a Redis LangCache service and configure the custom attribute name `scope` on that service. The Java client calls the LangCache REST API directly, so the model remains local and no OpenAI API key is involved. The demo uses the scope `shopping-catalog-v1-audio-speaker-cheapest`; each stored catalog answer expires after 30 seconds.

Provide the LangCache endpoint, cache ID, and service API key through environment variables or your approved secret manager. Do not commit the key to the project.

```sh
export LANGCACHE_BASE_URL='https://<your-langcache-host>'
export LANGCACHE_CACHE_ID='<cache-id>'
export LANGCACHE_API_KEY='<service-api-key>'
export LANGCACHE_SIMILARITY_THRESHOLD=0.90

./run-oracle-agent.sh --langcache-demo
```

The demo asks two paraphrased catalog questions. On an empty cache, the first misses LangCache, calls the local model, and lets `search_products` read through Oracle True Cache before saving the answer. The paraphrase should hit LangCache and skip both inference and the database tool call. It then asks for exact current stock; that request bypasses LangCache, uses True Cache for the catalog lookup, and checks stock on the primary. The output reports LangCache status, model and tool calls, elapsed time, token usage, and database tool routes.

The first catalog lookup is a miss only when the matching scoped entry is absent or expired. If it is already cached, the demo correctly reports a hit instead. Use a newly created LangCache service for a clean first-miss walkthrough; the demo does not flush or delete existing cache entries.

For an existing application, the integration point is the request router around the agent call: opt only stable, read-only catalog prompts into `LangCacheAgent`, and keep stock, order, checkout, and other freshness-sensitive paths on the direct agent flow. The wrapper requires a `ResponsesAgent` that exposes only `search_products`, plus an explicit cache scope. It checks LangCache before inference and stores only non-empty answers after a tool call. For production, derive the scope from tenant, catalog version, and request intent; tune the similarity threshold with representative paraphrases and near-miss prompts; and invalidate or expire responses when catalog data changes.

## Run the simulator

The standalone simulator uses no external service:

```sh
./build.sh test
./build.sh demo
./build.sh agent
```

To connect the simulated agent to a self-hosted model server, set `LLM_RESPONSES_URL` and `LLM_MODEL` before running `./build.sh agent`. The simulator includes write tools for the sample checkout flow; the Oracle demo remains read-only.

## Implementation notes

- `src/main/java/agentorders/ResponsesAgent.java` replays conversation history to the self-hosted server. Instructions and tool schemas stay first and in a stable order so the inference engine can reuse their prompt prefix.
- `src/main/java/agentorders/LangCacheClient.java` implements the LangCache REST search/store calls with environment-based credentials.
- `src/main/java/agentorders/LangCacheAgent.java` provides the cache-aside wrapper and enforces a short catalog-answer TTL.
- `src/oracle/java/agentorders/LocalModelOracleDemo.java` exposes only catalog and stock reads and has separate LangCache and live-inventory flows.
- `src/oracle/java/agentorders/OracleJdbcDb.java` uses separate JDBC pools for the primary and True Cache services.
- `deploy/llama-cpp-qwen3.yaml` contains the bounded CPU deployment and internal service.
- The model KV cache is held by the llama.cpp process. Restarting the model pod clears that in-memory prompt cache; the downloaded weights remain on the host path.
