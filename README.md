# dvara-bifrost-importer: move a Bifrost config to DVARA

It reads a [Bifrost](https://github.com/maximhq/bifrost) `config.json` and writes a `gateway.yaml` for the DVARA
open-source gateway. It also writes a report that says, for every part of your file, whether it maps cleanly,
maps with a difference, or has no equivalent, and why.

```bash
jbang bifrost-to-dvara@dvarahq config.json -o dvara-import/
```

That needs only [JBang](https://www.jbang.dev/download/). It fetches the released jar and, if you don't have Java 25,
downloads it the first time. To keep it as a command, run `jbang app install bifrost-to-dvara@dvarahq`, then
`bifrost-to-dvara config.json`. Without JBang, download `dvara-bifrost-importer.jar` from the
[latest release](https://github.com/dvarahq/dvara-bifrost-importer/releases/latest) and run
`java -jar dvara-bifrost-importer.jar config.json -o dvara-import/` on Java 25.

```
Wrote gateway.yaml, import-report.md, dvara-import.properties to dvara-import
  3 providers, 9 routes, 1 workspaces, 1 policies
  Maps cleanly: 14
  Maps, with a difference: 8
  No equivalent: 5
  You need to: 4
  What DVARA adds: 6
Read dvara-import/import-report.md before you start the gateway.
```

- It reads only the file you name. It opens no network connection and writes only into the output folder.
- **It never copies a secret.** `env.NAME` becomes `${NAME}`, which the gateway reads from its environment at
  startup. A key written in your file as a literal, or held in a vault (`vault.…`), is not copied: the output
  names an environment variable instead, and the report tells you to set it (and to rotate a literal). A user,
  password or query string in a base URL is not copied either: the URL is written without them. Passwords for
  Bifrost's stores, its encryption key, extra headers and plugin settings are never read into the output.
- It does not replace files already in the output folder unless you pass `--force`.

Checked against Bifrost `transports/v2.5.1` (commit `7cb75fb`, released 10 Nov 2026): its `config.schema.json`, the
`config.json` guides under `docs/deployment-guides/config-json`, and the provider code under `core/providers`.

Apache License 2.0.

## What it writes

| File | What it is |
|---|---|
| `gateway.yaml` | Providers, workspaces, routes and policies, in the open gateway's format. Start the gateway with `DVARA_CONFIG_FILE` pointing at it. |
| `import-report.md` | The diff report: what you need to do, what has no equivalent and why, what changed, what maps cleanly, and what DVARA adds. |
| `dvara-import.properties` | Per-provider retries, backoff and timeouts, which `gateway.yaml` cannot carry. Only written when your file sets them. Pass it with `--spring.config.additional-location=file:dvara-import.properties`. |

`gateway.yaml` has no API keys. The gateway starts without one and refuses every call until you add one.
Mint a key with the gateway's own `--generate-key` and paste the entry it prints. To keep a virtual key your
callers already use, fingerprint it instead: `printf '%s' "$VK_PLATFORM_TEAM" | java -jar
dvara-gateway-server-<version>-app.jar --hash-key -`, and add it under `api_keys` with that virtual key's workspace.

## How a Bifrost file maps

| Bifrost | DVARA |
|---|---|
| A provider under `providers` | A provider of the matching type (see below) |
| A model on a key's `models` | Two routes: `provider/model`, as Bifrost callers send it, and the bare name. Each sends the model the gateway's way |
| A key's `aliases` (string or `model_id`) | The alias's routes send the alias's target |
| `models: ["*"]` | No route needed: the gateway sends a model to the provider that serves it by name |
| Keys that list their models | A policy, `bifrost-served-models`, that refuses any other model with 403 |
| `blacklisted_models` | Left off the allowlist, or, on a key that serves every model, a denylist policy `bifrost-blocked-models` |
| Several keys on one provider | The first enabled key is served; keys with the same credentials add their models (see below) |
| `network_config.base_url`, `azure_key_config.endpoint`, `ollama_key_config.url`, `vllm_key_config.url`, `sgl_key_config.url` | The provider's `base_url`, with the API path the gateway expects (`/v1`, `/v2`, `/openai`) |
| `network_config.default_request_timeout_in_seconds` | `dvara.llm-gateway.resilience.providers.<provider>.timeout.chat-timeout-ms` |
| `network_config.max_retries` | `….retry.max-attempts`, one more than the retries, as the gateway counts the first call |
| `network_config.retry_backoff_initial`, `retry_backoff_max` | `….retry.initial-backoff-ms`, `….retry.max-backoff-ms` |
| `governance.routing_rules` whose condition is `model == '<name>'` | A route for that name: its targets weighted, its `fallbacks` the route's chain, at most 5 |
| `governance.virtual_keys` | A workspace per active virtual key, with an allowlist policy for its `provider_configs` |
| A virtual key's rate limit, and its team's and customer's | The workspace's `rate-limit.requests-per-minute` and `tokens-per-minute`, the lowest of them, per minute |
| `guardrails_config`: Presidio, Azure PII, regex or secrets | `pii.action: REDACT` on the workspaces |
| `guardrails_config`: Lakera | `guardrail.action: BLOCK` on the workspaces |
| `env.NAME` | `${NAME}` |

### Providers

| Bifrost provider | DVARA provider | Model the gateway is sent |
|---|---|---|
| `openai` | `openai` | `gpt-4o` |
| `azure` | `azure-openai` (`base_url` is the key's endpoint with `/openai` added) | `azure/<deployment>` |
| `anthropic` | `anthropic` | `claude-sonnet-4-5` |
| `gemini` | `gemini` | `gemini-2.5-flash` |
| `bedrock` | `bedrock` (access key, secret key and region) | `bedrock/<model id>` |
| `mistral` | `mistral` | `mistral-large-latest` |
| `cohere` | `cohere` | `command-r-plus` |
| `groq` | `groq` | `groq/<model>` |
| `deepseek` | `deepseek` | `deepseek-chat` |
| `xai` | `grok` | `grok-4` |
| `ollama` | `ollama` | `ollama/<model>` |
| `vllm`, `sgl`, a custom provider on `openai` | `openai`, at its own URL | the model id (for vLLM, the key's `model_name`) |
| A custom provider on another base | that base's provider, at its own URL | as for the base |

Any other provider (`vertex`, `bedrock_mantle`, `openrouter`, `perplexity`, `cerebras`, `huggingface` and the
rest) is not served, and the report says so for each one.

A provider is never pointed at its public API when Bifrost sent it to a server of your own. If the base URL holds
a user and password or a query string, `base_url` is written without them, and the report tells you to rotate the
credentials. If the URL cannot be read, comes from a vault or a variable the gateway cannot read, or an
OpenAI-compatible server, Azure or Ollama has none, the provider is not served and the report says so under "You
need to". Bedrock at a base URL or VPC endpoints of your own is not served either, since the gateway's Bedrock
provider calls only AWS's regional endpoint.

### What does not carry over

The report names each one. The main ones:

- **One endpoint per provider.** The open gateway has one OpenAI, one Azure OpenAI and so on, with one key each.
  Bifrost spreads a provider's traffic across its keys by weight; here the first enabled key is served, and a key
  with other credentials is not. The models only it served are refused. (A workspace can bring its own provider
  key under `credentials`.) vLLM, SGLang and OpenAI-compatible custom providers share the `openai` slot with
  OpenAI itself.
- **Wildcards and patterns on the allowlist.** An allowlist matches exact names only. When some keys list their
  models and others serve `*` or `regex:` patterns, the named models keep their allowlist, but the rest are
  refused with 403 until you add them by hand. The report lists each such provider under "You need to". A file
  whose every key serves `*` gets no allowlist, as Bifrost had none. A virtual key whose models cannot be listed
  by name is left out rather than left open.
- **The `provider/model` form for models with no route.** Routes are written for every model a key, alias,
  virtual key or routing rule names. Any other model is reached by the gateway's own name for it.
- **Routing rules with other conditions**, rules scoped to a team, customer or virtual key, and adaptive load
  balancing. Fallbacks a caller sends in the request body are not read; the route's chain is.
- **Budgets, model configs, pricing overrides, and per-provider rate limits on a virtual key.** The open gateway
  does not enforce spend budgets.
- **Rate limits over windows other than a minute** become their per-minute share, which also caps bursts. A team's
  or customer's limit, shared across its virtual keys in Bifrost, applies to each workspace alone.
- **Semantic cache, Maxim, Datadog and other observability plugins, MCP, clustering, alerting, Bifrost's stores and
  dashboard login.**
- **Anything in Bifrost's config store**: providers, keys, virtual keys and rules added through its UI or API.
  Only `config.json` is read.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Written |
| 1 | The input could not be read, or is not a Bifrost config (it has no `providers`) |
| 2 | Usage error |
| 3 | A file in the output folder would be replaced; pass `--force` |

## Build and test

```bash
./mvnw verify
java -jar target/dvara-bifrost-importer.jar --help
```

The project stands alone: no parent and no runtime dependencies (it reads JSON with its own small parser). The
tests check the output with the open gateway's own code, `dvara-gateway-autoconfigure` and `dvara-gateway-policy`
as test dependencies: each fixture under `src/test/resources/fixtures` is imported and then loaded and validated by
the gateway's `gateway.yaml` loader, served by its configuration store, routed by its routing engine, its policies
compiled and checked against the models they should refuse, and its properties bound. The fixtures follow
Bifrost's documented examples.

The tests use the latest open release by default. To test against a newer snapshot:

```bash
./mvnw verify -Pdvara-snapshot -Ddvara.version=1.14.0-SNAPSHOT
```
