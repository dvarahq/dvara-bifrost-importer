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

import com.dvarahq.tools.bifrost.DvaraConfig.Fallback;
import com.dvarahq.tools.bifrost.DvaraConfig.Policy;
import com.dvarahq.tools.bifrost.DvaraConfig.Provider;
import com.dvarahq.tools.bifrost.DvaraConfig.Route;
import com.dvarahq.tools.bifrost.DvaraConfig.WeightedProvider;
import com.dvarahq.tools.bifrost.DvaraConfig.Workspace;
import com.dvarahq.tools.bifrost.ImportReport.Kind;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Reads a Bifrost {@code config.json} and builds the equivalent open-gateway configuration, with a report of
 * what carried over, what changed and what has no equivalent.
 *
 * <p>Three rules hold throughout. A secret value is never copied: each key becomes the name of the environment
 * variable that holds it. A provider Bifrost sent to a server of its own is never pointed at the provider's
 * public API instead. And nothing in the input is passed over in silence: a key this class does not map is
 * named in the report.
 */
final class BifrostImporter {

    /** The workspace a key with no workspace of its own belongs to, in the open gateway. */
    static final String DEFAULT_WORKSPACE = "default";

    /** The most backups a route's chain may name. */
    static final int MAX_FALLBACKS = 5;

    /** The allowlist that keeps the gateway to the models Bifrost's keys served. */
    static final String SERVED_POLICY = "bifrost-served-models";

    /** The denylist for models a key's blacklisted_models refused. */
    static final String BLOCKED_POLICY = "bifrost-blocked-models";

    private static final Pattern WORKSPACE_ID = Pattern.compile("[A-Za-z0-9._-]{1,36}");

    /** The one CEL form a routing rule can carry over in: a match on the model's name. */
    private static final Pattern MODEL_RULE = Pattern.compile(
            "\\s*(?:request\\.)?model\\s*==\\s*(['\"])([^'\"\\s]+)\\1\\s*");

    private static final String REGEX_PREFIX = "regex:";

    /** The thing a caller needs to know about the result: the configuration and the report. */
    record Result(DvaraConfig config, ImportReport report) {}

    /** Input the importer cannot read at all, as opposed to parts of it that do not map. */
    static final class ImportException extends RuntimeException {
        ImportException(String message) {
            super(message);
        }
    }

    /** One key of a provider, as read. Its value is held only as a fingerprint, never as text to write. */
    private record KeyInfo(String where, String id, String name, Map<String, Object> raw) {}

    /** A Bifrost provider that is served, and the models it serves. */
    private static final class Served {
        final String name;
        final ProviderType type;
        final String where;
        final KeyInfo key;
        /** Caller's name (without the provider prefix) to the model id the provider is sent. */
        final Map<String, String> models = new LinkedHashMap<>();
        /** Serves every model, as {@code "*"} does. */
        boolean wildcard;
        /** {@code regex:} entries on the key's model list, which an exact allowlist cannot hold. */
        final List<String> patterns = new ArrayList<>();
        /** Exact names a blacklist refuses on a provider that serves every model. */
        final Set<String> blocked = new LinkedHashSet<>();
        /** vLLM: the one model the server runs, whatever name the caller used. */
        String fixedModel;

        Served(String name, ProviderType type, String where, KeyInfo key) {
            this.name = name;
            this.type = type;
            this.where = where;
            this.key = key;
        }

        String sent(String caller) {
            if (fixedModel != null) {
                return fixedModel;
            }
            return models.getOrDefault(caller, caller);
        }

        String dvara(String caller) {
            return type.dvaraModel(sent(caller));
        }

        String prefixed(String caller) {
            return name + "/" + caller;
        }

        /** Every model is served; an allowlist could only narrow it. */
        boolean unrestricted() {
            return wildcard || !patterns.isEmpty();
        }
    }

    /** A route while it is being built. */
    private static final class RouteDraft {
        final String id;
        final String model;
        final String pinnedModel;
        final List<WeightedProvider> providers;
        final List<Fallback> fallbacks = new ArrayList<>();

        RouteDraft(String id, String model, String pinnedModel, List<WeightedProvider> providers) {
            this.id = id;
            this.model = model;
            this.pinnedModel = pinnedModel;
            this.providers = providers;
        }

        String sentModel() {
            return pinnedModel != null ? pinnedModel : model;
        }

        Route toRoute() {
            return new Route(id, model, pinnedModel, List.copyOf(providers), List.copyOf(fallbacks));
        }
    }

    private final DvaraConfig out = new DvaraConfig();
    private final ImportReport report = new ImportReport();

    /** Bifrost 1.4 semantics: an empty allow-list means allow all. */
    private boolean v1;

    private final Map<String, Served> served = new LinkedHashMap<>();
    /** Bifrost providers in the file that are not served, so a reference to one is named as such. */
    private final Set<String> notServed = new LinkedHashSet<>();
    /** What each provider type was configured from, so a second, different endpoint of one type is caught. */
    private final Map<ProviderType, String> slotFingerprints = new LinkedHashMap<>();
    private final Map<ProviderType, String> slotOwners = new LinkedHashMap<>();
    private final Map<String, RouteDraft> routesByModel = new LinkedHashMap<>();
    private final Set<String> routeIds = new LinkedHashSet<>();
    private final Set<String> envNames = new TreeSet<>();
    private final Map<String, Object> guardrailMetadata = new LinkedHashMap<>();
    /** Exact model names other sections name for a provider, so a provider serving "*" still gets routes for them. */
    private final Map<String, Set<String>> mentioned = new LinkedHashMap<>();

    private final Map<String, Map<String, Object>> rateLimits = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> teams = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> customers = new LinkedHashMap<>();
    private final Set<String> usedRateLimits = new LinkedHashSet<>();
    private final Set<String> usedTeams = new LinkedHashSet<>();
    private final Set<String> usedCustomers = new LinkedHashSet<>();

    private BifrostImporter() {}

    static Result importJson(String text) {
        Json.Parsed parsed;
        try {
            parsed = Json.parse(text);
        } catch (Json.ParseException e) {
            throw new ImportException("not valid JSON: " + e.getMessage());
        }
        if (!(parsed.value() instanceof Map<?, ?> map)) {
            throw new ImportException("the file is not a Bifrost config: its top level is not an object");
        }
        BifrostImporter importer = new BifrostImporter();
        for (String dup : parsed.duplicateKeys()) {
            importer.report.add(Kind.CHANGED, dup, "Appears twice in the file; the last one is read, as Bifrost does.");
        }
        importer.run(stringKeys(map));
        return new Result(importer.out, importer.report);
    }

    // ------------------------------------------------------------------------------------------------
    // The run, in the order the parts depend on each other
    // ------------------------------------------------------------------------------------------------

    private void run(Map<String, Object> root) {
        Object providers = root.get("providers");
        if (providers == null) {
            throw new ImportException("the file has no providers, so there is nothing to import");
        }
        if (!(providers instanceof Map<?, ?> providerMap)) {
            throw new ImportException("providers is not an object");
        }
        Long version = root.get("version") instanceof Long l ? l : null;
        v1 = version != null && version == 1;

        for (String key : root.keySet()) {
            readTopLevel(key, root.get(key));
        }

        Map<String, Object> governance = section(root, "governance");
        readProviders(stringKeys(providerMap));
        reportProviders();
        indexGovernance(governance);
        collectMentions(governance);
        buildRoutes();
        reportSharedNames(readRoutingRules(governance.get("routing_rules")));

        // The gateway takes the first route that matches; every route here is an exact name.
        routesByModel.values().forEach(d -> out.routes.add(d.toRoute()));
        out.workspaces.add(new Workspace(DEFAULT_WORKSPACE, null, new LinkedHashMap<>()));
        buildServedPolicies();
        readVirtualKeys(governance.get("virtual_keys"));
        readGovernanceRest(governance);
        readPlugins(root.get("plugins"));
        readClient(section(root, "client"), governance.get("virtual_keys") instanceof List<?> l && !l.isEmpty());
        readGuardrails(section(root, "guardrails_config"));
        applyGuardrailMetadata();
        reportDefaults();
        finalActions();
    }

    private void readTopLevel(String key, Object value) {
        switch (key) {
            case "providers", "governance", "plugins", "client", "guardrails_config" -> { }
            case "$schema" -> { }
            case "version" -> {
                if (v1) {
                    report.add(Kind.MAPPED, key, "Version 1: an empty model list allows every model, as in Bifrost"
                            + " 1.4. Read that way here.");
                }
            }
            case "config_store", "logs_store", "vector_store" -> report.add(Kind.NOT_MAPPED, key, "The open gateway"
                    + " has no database: gateway.yaml is its whole configuration, read at startup. Its settings,"
                    + " passwords included, are not copied.");
            case "encryption_key", "setup_token" -> report.add(Kind.NOT_MAPPED, key, "Not copied. It protects"
                    + " Bifrost's own store and dashboard, which have no equivalent in gateway.yaml.");
            case "auth_config" -> report.add(Kind.NOT_MAPPED, key, "Bifrost's dashboard login; not carried over, and"
                    + " its values are not copied.");
            case "source_of_truth" -> report.add(Kind.CHANGED, key, "No setting. gateway.yaml is the gateway's whole"
                    + " configuration; nothing is merged from a database.");
            case "mcp" -> report.add(Kind.NOT_MAPPED, key, "MCP clients and tools are not in gateway.yaml, so this"
                    + " importer does not carry them over.");
            case "cluster_config" -> report.add(Kind.NOT_MAPPED, key, "The open gateway runs as one process and shares"
                    + " no state between nodes.");
            case "load_balancer_config" -> report.add(Kind.CHANGED, key, "Adaptive routing has no equivalent. A route"
                    + " with several providers picks one by weight.");
            case "circuit_breaker_config" -> report.add(Kind.CHANGED, key, "Not carried over. The gateway opens a"
                    + " provider's circuit on its failure rate (50% of the last 10 calls by default) and fails over on"
                    + " a route's chain; tune dvara.llm-gateway.resilience.circuit-breaker.* if you need to.");
            case "alerting" -> report.add(Kind.NOT_MAPPED, key, "No alerting in the gateway. Alert on its Prometheus"
                    + " metrics instead.");
            case "proxy_config" -> report.add(Kind.NOT_MAPPED, key, "The outbound proxy is not in gateway.yaml; set the"
                    + " JVM's https.proxyHost and https.proxyPort. Its values are not copied.");
            case "framework" -> report.add(Kind.NOT_MAPPED, key, "Bifrost's pricing catalog; the gateway does not read"
                    + " prices from gateway.yaml.");
            case "server", "websocket", "large_payload_optimization" -> report.add(Kind.NOT_MAPPED, key, "Bifrost's"
                    + " HTTP server tuning; no equivalent in gateway.yaml. The gateway is a Spring Boot app: set"
                    + " server.* properties instead.");
            default -> report.add(Kind.NOT_MAPPED, key, "Not a section the open gateway has; not carried over.");
        }
    }

    // ------------------------------------------------------------------------------------------------
    // providers
    // ------------------------------------------------------------------------------------------------

    private void readProviders(Map<String, Object> providers) {
        if (providers.isEmpty()) {
            report.add(Kind.NOT_MAPPED, "providers", "Empty, so no provider is configured.");
        }
        providers.forEach((name, value) -> {
            String where = "providers." + name;
            if (!(value instanceof Map<?, ?> m)) {
                report.add(Kind.NOT_MAPPED, where, "Not an object; not read.");
                notServed.add(name);
                return;
            }
            if (!readProvider(name, where, stringKeys(m))) {
                notServed.add(name);
            }
        });
    }

    /** Reads one provider; false, with a report line, when it is not served. */
    private boolean readProvider(String name, String where, Map<String, Object> p) {
        Map<String, Object> custom = section(p, "custom_provider_config", where);
        boolean isCustom = p.containsKey("custom_provider_config");
        ProviderType type;
        boolean compatible;
        if (isCustom) {
            String base = Objects.toString(custom.get("base_provider_type"), "");
            type = ProviderType.forBifrostName(base).filter(t -> !ProviderType.OPENAI_COMPATIBLE.contains(base))
                    .orElse(null);
            if (type == null) {
                report.add(Kind.NOT_MAPPED, where, "Not served. A custom provider built on `" + base + "`, and the open"
                        + " gateway has no provider for `" + base + "`.");
                return false;
            }
            compatible = true;
        } else {
            type = ProviderType.forBifrostName(name).orElse(null);
            if (type == null) {
                String reason = ProviderType.UNSUPPORTED_REASONS.getOrDefault(name.toLowerCase(Locale.ROOT),
                        "The open gateway has no provider for `" + name + "`.");
                report.add(Kind.NOT_MAPPED, where, "Not served. " + reason);
                return false;
            }
            compatible = ProviderType.OPENAI_COMPATIBLE.contains(name.toLowerCase(Locale.ROOT));
        }

        List<KeyInfo> keys = readKeys(where, p.get("keys"));
        if (keys.isEmpty()) {
            return false;
        }
        KeyInfo primary = keys.get(0);
        Map<String, Object> network = section(p, "network_config", where);

        // Checked before anything is built, so a provider that is not served adds no instructions.
        String fingerprint = fingerprint(type, primary, network);
        String existing = slotFingerprints.get(type);
        if (existing != null && !existing.equals(fingerprint)) {
            report.add(Kind.NOT_MAPPED, where, "Not served. It would use the gateway's " + type.yamlType
                    + " provider, which `" + slotOwners.get(type) + "` already uses with a different key or URL, and"
                    + " the open gateway has one " + type.yamlType + " endpoint. (A workspace can bring its own key"
                    + " under `credentials`.)");
            return false;
        }

        // The gateway has one key per provider: the first key, and any that carry the same credentials.
        Served s = new Served(name, type, where, primary);
        List<KeyInfo> same = new ArrayList<>();
        List<KeyInfo> different = new ArrayList<>();
        for (KeyInfo k : keys) {
            (fingerprint(type, k, network).equals(fingerprint) ? same : different).add(k);
        }
        same.forEach(k -> readModels(s, k));
        if (s.models.isEmpty() && !s.wildcard && s.patterns.isEmpty()) {
            report.add(Kind.NOT_MAPPED, where, "Not served: " + (different.isEmpty() ? "" : "the key the gateway would"
                    + " use, `" + primary.name() + "`, ") + "serves no model.");
            return false;
        }

        UrlValue baseUrl = baseUrl(where, name, type, compatible, network, primary);
        if (baseUrl == UNUSABLE_BASE_URL) {
            return false;
        }
        if (existing == null) {
            Provider provider = providerFor(primary, type, compatible, isCustom, custom, baseUrl);
            out.providers.put(type, provider);
            slotFingerprints.put(type, fingerprint);
            slotOwners.put(type, where);
        } else {
            report.add(Kind.CHANGED, where, "Shares the gateway's " + type.yamlType + " provider with `"
                    + slotOwners.get(type) + "`: the same key and URL.");
        }
        served.put(name, s);

        if (keys.size() > 1) {
            same.stream().skip(1).forEach(k -> report.add(Kind.MAPPED, k.where(), "Same key and URL as "
                    + primary.where() + "; its models are served too."));
            different.forEach(k -> report.add(Kind.NOT_MAPPED, k.where(), "Not served. It uses a different key or URL"
                    + " than " + primary.where() + ", and the open gateway has one " + type.yamlType + " endpoint, so"
                    + " the models only this key served are refused. (A workspace can bring its own key under"
                    + " `credentials`.)"));
            report.add(Kind.CHANGED, where, "Bifrost spread requests across " + keys.size() + " keys by weight. The"
                    + " gateway sends every " + name + " request with one key, `" + primary.name() + "`"
                    + (different.isEmpty() ? ", since they are the same." : "."));
        }
        readNetworkConfig(s, network);
        reportProviderSettings(where, p);
        return true;
    }

    private static final Set<String> KEY_FIELDS_HANDLED = Set.of("id", "name", "value", "models", "weight",
            "enabled", "aliases", "blacklisted_models", "description", "azure_key_config", "bedrock_key_config",
            "ollama_key_config", "vllm_key_config", "sgl_key_config", "oauth_key_config");

    /** The keys a provider has that are switched on; reports the rest. */
    private List<KeyInfo> readKeys(String where, Object value) {
        List<KeyInfo> keys = new ArrayList<>();
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            report.add(Kind.NOT_MAPPED, where, "Not served: it has no keys.");
            return keys;
        }
        for (int i = 0; i < list.size(); i++) {
            String index = where + ".keys[" + i + "]";
            if (!(list.get(i) instanceof Map<?, ?> m)) {
                report.add(Kind.NOT_MAPPED, index, "Not an object; not read.");
                continue;
            }
            Map<String, Object> k = stringKeys(m);
            String name = Objects.toString(k.get("name"), "");
            String keyWhere = name.isBlank() ? index : index + " (" + name + ")";
            if (Boolean.FALSE.equals(k.get("enabled"))) {
                report.add(Kind.NOT_MAPPED, keyWhere, "enabled: false, so Bifrost did not use it; not carried over.");
                continue;
            }
            keys.add(new KeyInfo(keyWhere, Objects.toString(k.get("id"), null), name.isBlank() ? "keys[" + i + "]"
                    : name, k));
        }
        if (keys.isEmpty()) {
            report.add(Kind.NOT_MAPPED, where, "Not served: none of its keys is enabled.");
        }
        return keys;
    }

    /** What identifies one endpoint of a type. Held in memory only, to compare keys; never written. */
    private static String fingerprint(ProviderType type, KeyInfo key, Map<String, Object> network) {
        Map<String, Object> k = key.raw();
        return type + "|" + Objects.toString(k.get("value"), "")
                + "|" + Objects.toString(network.get("base_url"), "")
                + "|" + Objects.toString(k.get("azure_key_config"), "")
                + "|" + Objects.toString(k.get("bedrock_key_config"), "")
                + "|" + Objects.toString(k.get("ollama_key_config"), "")
                + "|" + Objects.toString(k.get("vllm_key_config"), "")
                + "|" + Objects.toString(k.get("sgl_key_config"), "");
    }

    /** The provider entry for the key the gateway will use. */
    private Provider providerFor(KeyInfo key, ProviderType type, boolean compatible, boolean isCustom,
                                 Map<String, Object> custom, UrlValue baseUrl) {
        String where = key.where();
        Map<String, Object> k = key.raw();
        SecretValue apiKey = null;
        if (type.takesApiKey) {
            SecretValue.Ref ref = SecretValue.classify(k.get("value"));
            if (ref.kind() == SecretValue.Kind.NONE) {
                apiKey = new SecretValue(type.defaultKeyEnv);
                envNames.add(type.defaultKeyEnv);
                if (k.get("oauth_key_config") != null) {
                    report.add(Kind.ACTION, where, "Bifrost minted this key from a token endpoint (oauth_key_config)."
                            + " The gateway sends a fixed key: put one the server accepts in " + type.defaultKeyEnv
                            + ". The OAuth settings were not copied.");
                } else if (type == ProviderType.AZURE_OPENAI) {
                    report.add(Kind.ACTION, where, "No key, so Bifrost signed in with DefaultAzureCredential (managed"
                            + " identity). The gateway calls Azure OpenAI with an API key only: set "
                            + type.defaultKeyEnv + ".");
                } else if (compatible) {
                    report.add(Kind.ACTION, where, "The gateway's " + type.yamlType + " provider starts only with a"
                            + " key. This server had none, so set " + type.defaultKeyEnv + " to any value it accepts.");
                } else {
                    report.add(Kind.ACTION, where, "No key in the file. Set " + type.defaultKeyEnv + ".");
                }
            } else {
                apiKey = secret(where, "value", ref, type.defaultKeyEnv);
            }
        } else if (SecretValue.classify(k.get("value")).kind() != SecretValue.Kind.NONE) {
            report.add(Kind.NOT_MAPPED, where, "value: the open gateway calls " + type.yamlType + " without an API key,"
                    + " so it is not carried over.");
        }
        if (isCustom) {
            for (String field : custom.keySet()) {
                if (!field.equals("base_provider_type") && !field.equals("is_key_less")) {
                    report.add(Kind.NOT_MAPPED, where.substring(0, where.indexOf(".keys[")) + ".custom_provider_config."
                            + field, "Not carried over: the gateway calls the server as its " + type.yamlType
                            + " provider does.");
                }
            }
        }

        SecretValue accessKey = null;
        SecretValue secretKey = null;
        UrlValue region = null;
        if (type == ProviderType.BEDROCK) {
            Map<String, Object> b = section(k, "bedrock_key_config", where);
            accessKey = secretOrNull(where, "bedrock_key_config.access_key", b.get("access_key"), "AWS_ACCESS_KEY_ID");
            secretKey = secretOrNull(where, "bedrock_key_config.secret_key", b.get("secret_key"),
                    "AWS_SECRET_ACCESS_KEY");
            Object r = b.get("region");
            SecretValue.Ref regionRef = SecretValue.classify(r);
            if (regionRef.kind() == SecretValue.Kind.ENV && SecretValue.isValidName(regionRef.envName())) {
                envNames.add(regionRef.envName());
                region = UrlValue.fromEnv(regionRef.envName(), "");
            } else if (regionRef.kind() == SecretValue.Kind.LITERAL
                    && String.valueOf(r).strip().matches("[a-z]{2}(-[a-z]+)+-\\d+")) {
                region = UrlValue.literal(String.valueOf(r).strip());
            } else {
                report.add(Kind.ACTION, where, "bedrock_key_config.region is missing or not a region the importer can"
                        + " read. Add `region` to the bedrock provider in gateway.yaml.");
            }
            if (accessKey == null) {
                report.add(Kind.CHANGED, where, "No AWS keys in the file. The gateway reads AWS_ACCESS_KEY_ID and"
                        + " AWS_SECRET_ACCESS_KEY; it does not assume a role or use IRSA as Bifrost can.");
            }
            List<String> other = b.keySet().stream()
                    .filter(f -> !Set.of("access_key", "secret_key", "region", "endpoints").contains(f)).toList();
            if (!other.isEmpty()) {
                report.add(Kind.NOT_MAPPED, where, "bedrock_key_config " + String.join(", ", other) + ": the gateway"
                        + " signs Bedrock calls with an access key, a secret key and a region only. Their values are"
                        + " not copied.");
            }
        }
        if (type == ProviderType.AZURE_OPENAI) {
            Map<String, Object> a = section(k, "azure_key_config", where);
            if (a.get("api_version") != null) {
                report.add(Kind.CHANGED, where, "azure_key_config.api_version is not carried over: the gateway calls"
                        + " Azure OpenAI chat with api-version 2024-10-21.");
            }
            List<String> entra = a.keySet().stream()
                    .filter(f -> Set.of("client_id", "client_secret", "tenant_id", "scopes").contains(f)).toList();
            if (!entra.isEmpty()) {
                report.add(Kind.NOT_MAPPED, where, "azure_key_config " + String.join(", ", entra) + ": the gateway"
                        + " calls Azure OpenAI with an API key, not Entra ID. Their values are not copied.");
            }
            List<String> other = a.keySet().stream()
                    .filter(f -> !Set.of("endpoint", "api_version", "client_id", "client_secret", "tenant_id",
                            "scopes").contains(f)).toList();
            if (!other.isEmpty()) {
                report.add(Kind.NOT_MAPPED, where, "azure_key_config " + String.join(", ", other) + ": not carried"
                        + " over.");
            }
        }

        List<String> notCarried = new ArrayList<>();
        for (String field : k.keySet()) {
            if (!KEY_FIELDS_HANDLED.contains(field)) {
                notCarried.add(field);
            }
        }
        if (!notCarried.isEmpty()) {
            report.add(Kind.NOT_MAPPED, where, String.join(", ", notCarried) + ": not carried over.");
        }
        if (k.get("oauth_key_config") != null && SecretValue.classify(k.get("value")).kind() != SecretValue.Kind.NONE) {
            report.add(Kind.NOT_MAPPED, where, "oauth_key_config: not used, since the key has a value, as in Bifrost.");
        }
        return new Provider(type, apiKey, baseUrl, accessKey, secretKey, region);
    }

    /** The models one served key serves, added to its provider. */
    private void readModels(Served s, KeyInfo key) {
        Map<String, Object> k = key.raw();
        String where = key.where();
        Map<String, String> aliases = aliases(where, k.get("aliases"));

        if (s.name.equalsIgnoreCase("vllm")) {
            Object runs = section(k, "vllm_key_config", where).get("model_name");
            if (runs instanceof String m && !m.isBlank() && m.strip().chars().noneMatch(Character::isWhitespace)
                    && s.fixedModel == null) {
                s.fixedModel = m.strip();
            }
        }

        Object modelsValue = k.get("models");
        List<String> models = modelsValue instanceof List<?> l ? l.stream().map(String::valueOf).map(String::strip)
                .toList() : List.of();
        if (models.isEmpty()) {
            if (v1) {
                models = List.of("*");
            } else {
                report.add(Kind.NOT_MAPPED, where, "models is empty or missing, which Bifrost reads as no model at"
                        + " all, so this key serves nothing.");
                return;
            }
        }
        if (s.fixedModel != null && models.equals(List.of("*"))) {
            // A vLLM server runs one model: "*" sends every name to it, and the caller names it.
            models = List.of(s.fixedModel);
        }

        boolean wildcard = models.contains("*");
        List<String> patterns = models.stream().filter(m -> m.startsWith(REGEX_PREFIX)).toList();
        List<String> exact = models.stream().filter(m -> !m.equals("*") && !m.startsWith(REGEX_PREFIX)).toList();

        // The blacklist wins over the model list, in Bifrost as here.
        Object blValue = k.get("blacklisted_models");
        List<String> blacklist = blValue instanceof List<?> bl ? bl.stream().map(String::valueOf).map(String::strip)
                .toList() : List.of();
        if (blacklist.contains("*")) {
            report.add(Kind.NOT_MAPPED, where, "blacklisted_models is `*`, which refuses every model, so this key"
                    + " serves nothing.");
            return;
        }
        List<Pattern> blockPatterns = new ArrayList<>();
        for (String b : blacklist) {
            if (b.startsWith(REGEX_PREFIX)) {
                Pattern pt = compile(b);
                if (pt == null || wildcard || !patterns.isEmpty()) {
                    report.add(Kind.ACTION, where, "Its models are not served. blacklisted_models has the pattern `" + b
                            + "`, the gateway's denylist matches exact names only, and this key serves models by"
                            + " wildcard or pattern, so the models it refuses cannot be listed. Write the key's models"
                            + " out by name and run the importer again.");
                    return;
                }
                blockPatterns.add(pt);
            }
        }
        Set<String> blockedExact = blacklist.stream().filter(b -> !b.startsWith(REGEX_PREFIX))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (wildcard) {
            s.wildcard = true;
            s.blocked.addAll(blockedExact);
            // An alias names a model the caller sends, so it is routed even when every model is served.
            aliases.forEach((alias, target) -> {
                if (!isBlocked(alias, blockedExact, blockPatterns)) {
                    s.models.putIfAbsent(alias, target);
                }
            });
        }
        for (String m : exact) {
            if (isBlocked(m, blockedExact, blockPatterns)) {
                report.add(Kind.MAPPED, where, "`" + m + "` is on blacklisted_models as well, so it is not served.");
                continue;
            }
            if (m.chars().anyMatch(Character::isWhitespace)) {
                report.add(Kind.NOT_MAPPED, where, "`" + m + "` has a space in its name, which the gateway cannot"
                        + " route.");
                continue;
            }
            s.models.putIfAbsent(m, lookup(aliases, m));
        }
        aliases.keySet().stream().filter(a -> !wildcard && exact.stream().noneMatch(m -> m.equalsIgnoreCase(a)))
                .forEach(a -> report.add(Kind.NOT_MAPPED, where, "Alias `" + a + "` is not on the key's models, so"
                        + " Bifrost did not serve it either; not routed."));
        for (String p : patterns) {
            if (compile(p) == null) {
                report.add(Kind.NOT_MAPPED, where, "models entry `" + p + "` is not a pattern the importer can read;"
                        + " not served.");
            } else {
                s.patterns.add(p);
            }
        }
    }

    /** Bifrost looks aliases up without regard to case. */
    private static String lookup(Map<String, String> aliases, String model) {
        return aliases.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(model)).map(Map.Entry::getValue)
                .findFirst().orElse(model);
    }

    /** Whether a provider's keys serve this model, as Bifrost decides it. */
    private static boolean serves(Served s, String model) {
        if (s.blocked.stream().anyMatch(b -> b.equalsIgnoreCase(model))) {
            return false;
        }
        return s.wildcard || s.models.containsKey(model)
                || s.patterns.stream().map(BifrostImporter::compile).anyMatch(p -> p != null && p.matcher(model).matches());
    }

    private static boolean isBlocked(String model, Set<String> exact, List<Pattern> patterns) {
        return exact.stream().anyMatch(b -> b.equalsIgnoreCase(model))
                || patterns.stream().anyMatch(p -> p.matcher(model).matches());
    }

    /** A Bifrost {@code regex:} entry as a Java pattern; null when it does not compile. */
    static Pattern compile(String entry) {
        try {
            return Pattern.compile(entry.substring(REGEX_PREFIX.length()), Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    /** A key's alias map: the name a caller sends to the model id the provider is sent. */
    private Map<String, String> aliases(String where, Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value == null) {
            return result;
        }
        if (!(value instanceof Map<?, ?> m)) {
            report.add(Kind.NOT_MAPPED, where, "aliases is not an object; not read.");
            return result;
        }
        stringKeys(m).forEach((alias, target) -> {
            String id;
            if (target instanceof String t) {
                id = t.strip();
            } else if (target instanceof Map<?, ?> tm) {
                Map<String, Object> t = stringKeys(tm);
                List<String> overrides = t.keySet().stream()
                        .filter(f -> !Set.of("model_id", "model_name", "model_family", "description").contains(f))
                        .toList();
                if (!overrides.isEmpty()) {
                    report.add(Kind.NOT_MAPPED, where, "Alias `" + alias + "` overrides " + String.join(", ", overrides)
                            + " for its model, which a route cannot; not routed.");
                    return;
                }
                id = Objects.toString(t.get("model_id"), "").strip();
            } else {
                id = "";
            }
            if (id.isEmpty() || id.chars().anyMatch(Character::isWhitespace) || alias.isBlank()) {
                report.add(Kind.NOT_MAPPED, where, "Alias `" + alias + "` has no model id the gateway can send; not"
                        + " routed.");
                return;
            }
            result.put(alias.strip(), id);
        });
        return result;
    }

    // ------------------------------------------------------------------------------------------------
    // Base URLs
    // ------------------------------------------------------------------------------------------------

    /**
     * Returned by {@link #baseUrl} when the provider names a base URL that cannot be written. Leaving the URL
     * out would send its traffic and key to the provider's public API instead, so it is not served.
     */
    private static final UrlValue UNUSABLE_BASE_URL = UrlValue.literal("");

    /** The provider's base URL; null for the provider's own API; {@link #UNUSABLE_BASE_URL}, reported, if neither. */
    private UrlValue baseUrl(String where, String name, ProviderType type, boolean compatible,
                             Map<String, Object> network, KeyInfo key) {
        Map<String, Object> k = key.raw();
        Object raw;
        String field;
        boolean vllm = name.equalsIgnoreCase("vllm");
        if (type == ProviderType.AZURE_OPENAI) {
            raw = section(k, "azure_key_config", key.where()).get("endpoint");
            field = "azure_key_config.endpoint";
            if (network.get("base_url") != null) {
                report.add(Kind.NOT_MAPPED, where + ".network_config.base_url", "Bifrost's Azure provider calls each"
                        + " key's endpoint, not this URL; not used.");
            }
        } else if (type == ProviderType.BEDROCK) {
            Map<String, Object> b = section(k, "bedrock_key_config", key.where());
            if (network.get("base_url") != null || b.get("endpoints") != null) {
                report.add(Kind.ACTION, where, "Not served. Bifrost called Bedrock at "
                        + (network.get("base_url") != null ? "a base URL" : "VPC endpoints") + " of your own, and the"
                        + " gateway's Bedrock provider calls only AWS's public regional endpoint.");
                return UNUSABLE_BASE_URL;
            }
            return null;
        } else if (vllm) {
            raw = section(k, "vllm_key_config", key.where()).get("url");
            field = "vllm_key_config.url";
        } else if (type == ProviderType.OLLAMA || name.equalsIgnoreCase("sgl")) {
            // Bifrost lets the provider's base_url win over the key's URL for these two.
            String cfg = type == ProviderType.OLLAMA ? "ollama_key_config" : "sgl_key_config";
            raw = network.get("base_url") != null ? network.get("base_url")
                    : section(k, cfg, key.where()).get("url");
            field = network.get("base_url") != null ? "network_config.base_url" : cfg + ".url";
        } else {
            raw = network.get("base_url");
            field = "network_config.base_url";
        }
        String serverWord = type == ProviderType.OLLAMA ? "Ollama server" : "server";

        if (raw == null || String.valueOf(raw).isBlank()) {
            if (type == ProviderType.AZURE_OPENAI) {
                report.add(Kind.ACTION, where, "Not served. The key has no azure_key_config.endpoint, and the gateway"
                        + " needs the resource's URL. Add it and run the importer again.");
                return UNUSABLE_BASE_URL;
            }
            if (compatible || type == ProviderType.OLLAMA) {
                report.add(Kind.ACTION, where, "Not served. A " + serverWord + " of your own with no URL in the file,"
                        + " and without one the gateway would call "
                        + (type == ProviderType.OLLAMA ? "localhost" : type.yamlType + "'s own API")
                        + ". Add its URL and run the importer again.");
                return UNUSABLE_BASE_URL;
            }
            return null;
        }
        String suffix = compatible ? (type == ProviderType.OPENAI ? "/v1" : type.baseUrlSuffix) : type.baseUrlSuffix;
        SecretValue.Ref ref = SecretValue.classify(raw);
        if (ref.kind() == SecretValue.Kind.VAULT || (ref.kind() == SecretValue.Kind.ENV
                && !SecretValue.isValidName(ref.envName()))) {
            report.add(Kind.ACTION, where, "Not served. " + field + " is read from "
                    + (ref.kind() == SecretValue.Kind.VAULT ? "a vault, which the gateway does not read"
                    : "an environment variable the gateway cannot read (letters, digits and underscores only)")
                    + ", and without a base URL the gateway would call " + type.yamlType + "'s own API. Put the URL"
                    + " in a variable it can read and run the importer again.");
            return UNUSABLE_BASE_URL;
        }
        if (ref.kind() == SecretValue.Kind.ENV) {
            String env = ref.envName();
            envNames.add(env);
            if (type == ProviderType.GEMINI) {
                report.add(Kind.ACTION, where, "Base URL from " + env + ". Bifrost's Gemini URL ends in /v1beta and the"
                        + " gateway adds that itself: set " + env + " without it.");
            } else if (!suffix.isEmpty()) {
                report.add(Kind.ACTION, where, "Base URL from " + env + ". gateway.yaml adds " + suffix + " after it,"
                        + " where Bifrost added the API path itself: make sure " + env + " ends without a trailing"
                        + " slash or " + suffix + ".");
            }
            if (compatible) {
                report.add(Kind.CHANGED, where, "A server of your own. It is served through the gateway's "
                        + type.yamlType + " provider, at its base URL, which then serves every " + type.yamlType
                        + " route.");
            }
            return UrlValue.fromEnv(env, suffix);
        }

        String original = String.valueOf(raw).strip();
        String url = original;
        boolean hadCredentials = !isCopyableUrl(url);
        if (hadCredentials) {
            // The credentials are left out (the report says how to supply them); the host is kept.
            url = withoutCredentials(url);
            if (url == null) {
                report.add(Kind.ACTION, where, "Not served. " + field + " is not a URL the importer can read, and"
                        + " without a base URL the gateway would call " + type.yamlType + "'s own API. Fix it and run"
                        + " the importer again. (It was not copied, in case it holds a credential.)");
                return UNUSABLE_BASE_URL;
            }
        }
        url = url.replaceAll("/+$", "");
        if (!compatible && type.bifrostDefaultBaseUrl != null && url.equalsIgnoreCase(type.bifrostDefaultBaseUrl)
                && !hadCredentials) {
            report.add(Kind.MAPPED, where + "." + field, "The provider's own API, which the gateway calls by default.");
            return null;
        }
        String written;
        if (type == ProviderType.GEMINI) {
            written = url.replaceAll("/v1beta$", "");
        } else {
            written = url.endsWith(suffix) ? url : url + suffix;
        }
        if (hadCredentials) {
            report.add(Kind.ACTION, where, field + " carries a user and password or a query string. They were not"
                    + " copied: base_url is " + written + ". "
                    + (type.takesApiKey ? "The gateway sends only the provider key, so the server must accept that"
                    : "The gateway calls " + type.yamlType + " without a key, so the server must accept calls without"
                    + " them") + ". Rotate the credentials, since they were stored in plain text.");
        }
        if (compatible) {
            report.add(Kind.CHANGED, where, "A server of your own. It is served through the gateway's " + type.yamlType
                    + " provider, at " + written + ", which then serves every " + type.yamlType + " route.");
        } else if (!written.equals(url)) {
            report.add(Kind.CHANGED, where + "." + field, "Written as " + written + ": the gateway's " + type.yamlType
                    + " base URL " + (type == ProviderType.GEMINI ? "leaves out" : "includes") + " the API version"
                    + " Bifrost adds itself.");
        } else if (type != ProviderType.OLLAMA) {
            report.add(Kind.MAPPED, where + "." + field, "Copied as the provider's base_url.");
        }
        return UrlValue.literal(written);
    }

    /** A URL that may be written to the output: no user, no password and no query, which can hold a key. */
    static boolean isCopyableUrl(String url) {
        try {
            URI uri = new URI(url);
            return uri.getScheme() != null && uri.getHost() != null && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && (uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** The URL with its user, password, query and fragment removed; null if it is not an http(s) URL with a host. */
    static String withoutCredentials(String url) {
        try {
            URI uri = new URI(url);
            if (uri.getScheme() == null || uri.getHost() == null || !(uri.getScheme().equalsIgnoreCase("http")
                    || uri.getScheme().equalsIgnoreCase("https"))) {
                return null;
            }
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort())
                    + Objects.toString(uri.getRawPath(), "");
        } catch (URISyntaxException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Secrets
    // ------------------------------------------------------------------------------------------------

    /**
     * The variable a secret is read from. {@code env.NAME} becomes {@code NAME}; a literal value is not
     * copied, and the default variable is named instead, with a line telling the operator to set it.
     */
    private SecretValue secret(String where, String field, SecretValue.Ref ref, String defaultEnv) {
        if (ref.kind() == SecretValue.Kind.ENV && SecretValue.isValidName(ref.envName())) {
            envNames.add(ref.envName());
            return new SecretValue(ref.envName());
        }
        envNames.add(defaultEnv);
        switch (ref.kind()) {
            case ENV -> report.add(Kind.ACTION, where, field + " names a variable the gateway cannot read (letters,"
                    + " digits and underscores only). The gateway reads " + defaultEnv + " instead: set it.");
            case VAULT -> report.add(Kind.ACTION, where, field + " is read from a vault, which the gateway does not"
                    + " read. Put the secret in " + defaultEnv + ".");
            default -> report.add(Kind.ACTION, where, field + " is written in the file as a literal. It was not"
                    + " copied. The gateway reads " + defaultEnv + ": put the value there, and rotate it, since it was"
                    + " stored in plain text.");
        }
        return new SecretValue(defaultEnv);
    }

    private SecretValue secretOrNull(String where, String field, Object raw, String defaultEnv) {
        SecretValue.Ref ref = SecretValue.classify(raw);
        if (ref.kind() == SecretValue.Kind.NONE) {
            return null;
        }
        return secret(where, field, ref, defaultEnv);
    }

    // ------------------------------------------------------------------------------------------------
    // network_config and the provider's other settings
    // ------------------------------------------------------------------------------------------------

    private void readNetworkConfig(Served s, Map<String, Object> network) {
        String where = s.where + ".network_config";
        String prefix = "dvara.llm-gateway.resilience.providers." + s.type.yamlType + ".";
        for (Map.Entry<String, Object> e : network.entrySet()) {
            String key = e.getKey();
            String w = where + "." + key;
            Object v = e.getValue();
            switch (key) {
                case "base_url" -> { }
                case "default_request_timeout_in_seconds" -> {
                    Long n = positiveWhole(v);
                    if (n == null) {
                        report.add(Kind.NOT_MAPPED, w, "Not a positive whole number of seconds; not carried over.");
                    } else {
                        property(w, prefix + "timeout.chat-timeout-ms", String.valueOf(n * 1000));
                        if (n * 1000 > 120_000) {
                            report.add(Kind.CHANGED, w, "For a stream, Bifrost's timeout bounds the wait for the"
                                    + " response to start. The gateway bounds the whole stream with"
                                    + " streaming-timeout-ms (120 seconds by default); raise it if streams run longer.");
                        }
                    }
                }
                case "max_retries" -> {
                    Long n = whole(v);
                    if (n == null || n < 0) {
                        report.add(Kind.NOT_MAPPED, w, "Not a whole number; not carried over.");
                    } else {
                        // The gateway counts attempts, the first call included; Bifrost counts retries after it.
                        property(w, prefix + "retry.max-attempts", String.valueOf(n + 1));
                    }
                }
                case "retry_backoff_initial" -> backoff(w, v, prefix + "retry.initial-backoff-ms");
                case "retry_backoff_max" -> backoff(w, v, prefix + "retry.max-backoff-ms");
                case "stream_idle_timeout_in_seconds" -> report.add(Kind.CHANGED, w, "The gateway has no idle bound"
                        + " between chunks: streaming-timeout-ms bounds the whole stream.");
                case "extra_headers" -> report.add(Kind.NOT_MAPPED, w, "The gateway sends no extra headers to a"
                        + " provider; their values are not copied.");
                case "insecure_skip_verify" -> report.add(Kind.NOT_MAPPED, w, "The gateway always verifies the"
                        + " provider's certificate. Trust a private CA in the JVM's truststore instead.");
                case "ca_cert_pem" -> report.add(Kind.NOT_MAPPED, w, "Not carried over. Add the CA to the JVM's"
                        + " truststore so the gateway trusts the provider's certificate.");
                default -> report.add(Kind.NOT_MAPPED, w, "Connection tuning with no equivalent in gateway.yaml; not"
                        + " carried over.");
            }
        }
        if (!network.containsKey("default_request_timeout_in_seconds")) {
            noTimeout.add(s.where);
        }
        if (!network.containsKey("max_retries")) {
            noRetries.add(s.where);
        }
    }

    private final List<String> noTimeout = new ArrayList<>();
    private final List<String> noRetries = new ArrayList<>();

    /** Where the file left Bifrost's defaults, which differ from the gateway's. */
    private void reportDefaults() {
        String p = "dvara.llm-gateway.resilience.providers.<provider>.";
        if (!noTimeout.isEmpty()) {
            report.add(Kind.CHANGED, String.join(", ", noTimeout), "No timeout set, so Bifrost waited up to 300"
                    + " seconds. The gateway waits 30; set " + p + "timeout.chat-timeout-ms to change it.");
        }
        if (!noRetries.isEmpty()) {
            report.add(Kind.CHANGED, String.join(", ", noRetries), "No retries set, so Bifrost made one attempt. The"
                    + " gateway makes up to 3 on a transient error; set " + p + "retry.max-attempts=1 to keep one.");
        }
    }

    private void backoff(String where, Object value, String key) {
        Long n = positiveWhole(value);
        if (n == null) {
            report.add(Kind.NOT_MAPPED, where, "Not a positive whole number of milliseconds; not carried over.");
        } else {
            property(where, key, String.valueOf(n));
        }
    }

    private void reportProviderSettings(String where, Map<String, Object> p) {
        for (String key : p.keySet()) {
            String w = where + "." + key;
            switch (key) {
                case "keys", "network_config", "custom_provider_config" -> { }
                case "concurrency_and_buffer_size" -> report.add(Kind.CHANGED, w, "The gateway does not cap concurrent"
                        + " requests per provider; it limits requests and tokens per minute, per key and workspace.");
                case "proxy_config" -> report.add(Kind.NOT_MAPPED, w, "The outbound proxy is not in gateway.yaml; set"
                        + " the JVM's https.proxyHost and https.proxyPort. Its values are not copied.");
                case "send_back_raw_request", "send_back_raw_response", "store_raw_request_response" ->
                        report.add(Kind.NOT_MAPPED, w, "The gateway does not return or store the provider's raw"
                                + " request and response.");
                default -> report.add(Kind.NOT_MAPPED, w, "No equivalent in gateway.yaml; not carried over.");
            }
        }
    }

    private void reportProviders() {
        served.values().forEach(s -> {
            Provider p = out.providers.get(s.type);
            StringBuilder text = new StringBuilder("Provider `").append(s.type.yamlType).append("`");
            if (p.apiKey() != null) {
                text.append(", key from ").append(p.apiKey().yaml());
            }
            if (p.accessKey() != null) {
                text.append(", access key from ").append(p.accessKey().yaml());
            }
            if (p.baseUrl() != null) {
                text.append(p.baseUrl().isLiteral() ? ", at its own base URL" : ", base URL from ${"
                        + p.baseUrl().envName() + "}");
            }
            text.append('.');
            if (s.wildcard) {
                text.append(" It serves every model, as `*` did.");
            }
            report.add(Kind.MAPPED, s.where, text.toString());
        });
    }

    // ------------------------------------------------------------------------------------------------
    // Routes
    // ------------------------------------------------------------------------------------------------

    /** Model names that other sections name for a provider that serves every model, so they get routes too. */
    private void collectMentions(Map<String, Object> governance) {
        if (governance.get("virtual_keys") instanceof List<?> vks) {
            for (Object vk : vks) {
                if (vk instanceof Map<?, ?> m && stringKeys(m).get("provider_configs") instanceof List<?> pcs) {
                    for (Object pc : pcs) {
                        if (pc instanceof Map<?, ?> pm) {
                            Map<String, Object> c = stringKeys(pm);
                            String provider = Objects.toString(c.get("provider"), "");
                            if (c.get("allowed_models") instanceof List<?> names) {
                                names.forEach(n -> mention(provider, String.valueOf(n)));
                            }
                        }
                    }
                }
            }
        }
        if (governance.get("routing_rules") instanceof List<?> rules) {
            for (Object rule : rules) {
                if (rule instanceof Map<?, ?> m && stringKeys(m).get("targets") instanceof List<?> targets) {
                    for (Object t : targets) {
                        if (t instanceof Map<?, ?> tm) {
                            Map<String, Object> target = stringKeys(tm);
                            mention(Objects.toString(target.get("provider"), ""),
                                    Objects.toString(target.get("model"), ""));
                        }
                    }
                }
            }
        }
    }

    private void mention(String provider, String model) {
        String m = stripPrefix(provider, model.strip());
        if (!m.isEmpty() && !m.equals("*") && !m.startsWith(REGEX_PREFIX)
                && m.chars().noneMatch(Character::isWhitespace)) {
            mentioned.computeIfAbsent(provider, p -> new LinkedHashSet<>()).add(m);
        }
    }

    /** A model named as {@code provider/model} under that provider, without the prefix. */
    private static String stripPrefix(String provider, String model) {
        return !provider.isEmpty() && model.startsWith(provider + "/") ? model.substring(provider.length() + 1) : model;
    }

    /** Bare model names more than one provider serves, and those providers in file order. */
    private final Map<String, List<String>> bareOwners = new LinkedHashMap<>();

    private void buildRoutes() {
        for (Served s : served.values()) {
            Set<String> names = new LinkedHashSet<>(s.models.keySet());
            if (s.wildcard) {
                for (String m : mentioned.getOrDefault(s.name, Set.of())) {
                    if (s.blocked.stream().noneMatch(b -> b.equalsIgnoreCase(m))) {
                        names.add(m);
                    }
                }
            }
            for (String caller : names) {
                if (caller.chars().anyMatch(Character::isWhitespace) || s.sent(caller).chars()
                        .anyMatch(Character::isWhitespace)) {
                    report.add(Kind.NOT_MAPPED, s.where, "`" + caller + "` has a space in its name, which the gateway"
                            + " cannot route.");
                    continue;
                }
                String dvara = s.dvara(caller);
                addRoute(s.prefixed(caller), s.type, dvara);
                if (!routesByModel.containsKey(caller)) {
                    addRoute(caller, s.type, dvara);
                }
                bareOwners.computeIfAbsent(caller, c -> new ArrayList<>()).add(s.name);
            }
            if (!names.isEmpty()) {
                report.add(Kind.MAPPED, s.where, "Routes for " + names.stream().map(n -> "`" + n + "`")
                        .collect(Collectors.joining(", ")) + ", each by its own name and as `" + s.name + "/<model>`,"
                        + " as Bifrost callers may send.");
            }
            List<String> renamed = names.stream().filter(n -> !s.sent(n).equals(n))
                    .map(n -> "`" + n + "` as `" + s.dvara(n) + "`").toList();
            if (!renamed.isEmpty()) {
                report.add(Kind.MAPPED, s.where, "Each of these routes sends the model the key named for it: "
                        + String.join(", ", renamed) + ".");
            }
            if (s.wildcard) {
                report.add(Kind.CHANGED, s.where, "A model with no route here is reached by the gateway's own name for"
                        + " it" + (s.type.modelPrefix.isEmpty() ? ", the provider's model id" : ", `"
                        + s.type.modelPrefix + "<model>`") + ". The `" + s.name + "/<model>` form works only for"
                        + " the models routed above.");
            }
        }
    }

    /** Run after the routing rules, which may give a shared name a route of its own. */
    private void reportSharedNames(Set<String> ruleModels) {
        bareOwners.forEach((model, owners) -> {
            if (owners.size() > 1 && !ruleModels.contains(model)) {
                report.add(Kind.CHANGED, "model " + model, "Served by " + String.join(", ", owners) + ". A request for"
                        + " `" + model + "` with no provider goes to " + owners.get(0) + ", the first in the file;"
                        + " callers who name the provider, such as `" + owners.get(1) + "/" + model + "`, keep it.");
            }
        });
    }

    private RouteDraft addRoute(String model, ProviderType type, String sent) {
        RouteDraft existing = routesByModel.get(model);
        if (existing != null) {
            return existing;
        }
        RouteDraft draft = new RouteDraft(routeId(model), model, sent.equals(model) ? null : sent,
                new ArrayList<>(List.of(new WeightedProvider(type, 1))));
        routesByModel.put(model, draft);
        return draft;
    }

    private String routeId(String model) {
        String base = model.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (base.isEmpty()) {
            base = "route";
        }
        String id = base;
        for (int n = 2; !routeIds.add(id); n++) {
            id = base + "-" + n;
        }
        return id;
    }

    // ------------------------------------------------------------------------------------------------
    // governance.routing_rules
    // ------------------------------------------------------------------------------------------------

    /** Reads the rules, lowest priority number first as Bifrost runs them; returns the models they route. */
    private Set<String> readRoutingRules(Object value) {
        Set<String> routed = new LinkedHashSet<>();
        if (value == null) {
            return routed;
        }
        if (!(value instanceof List<?> list)) {
            report.add(Kind.NOT_MAPPED, "governance.routing_rules", "Not a list; not read.");
            return routed;
        }
        List<Map.Entry<String, Map<String, Object>>> rules = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            String index = "governance.routing_rules[" + i + "]";
            if (!(list.get(i) instanceof Map<?, ?> m)) {
                report.add(Kind.NOT_MAPPED, index, "Not an object; not read.");
                continue;
            }
            Map<String, Object> rule = stringKeys(m);
            String name = Objects.toString(rule.get("name"), Objects.toString(rule.get("id"), ""));
            rules.add(Map.entry(name.isBlank() ? index : index + " (" + name + ")", rule));
        }
        rules.sort(java.util.Comparator.comparingDouble(e -> {
            Double p = number(e.getValue().get("priority"));
            return p == null ? 0 : p;
        }));
        for (Map.Entry<String, Map<String, Object>> e : rules) {
            String model = readRoutingRule(e.getKey(), e.getValue(), routed);
            if (model != null) {
                routed.add(model);
            }
        }
        return routed;
    }

    /** One rule; the model it routes, or null when it is not carried over. */
    private String readRoutingRule(String where, Map<String, Object> rule, Set<String> routed) {
        if (Boolean.FALSE.equals(rule.get("enabled"))) {
            report.add(Kind.NOT_MAPPED, where, "enabled: false; not carried over.");
            return null;
        }
        String scope = Objects.toString(rule.get("scope"), "global");
        if (!scope.equals("global")) {
            report.add(Kind.NOT_MAPPED, where, "Scoped to a " + scope + ". A route applies to every workspace, so it is"
                    + " not carried over.");
            return null;
        }
        String cel = Objects.toString(rule.get("cel_expression"), "");
        Matcher matcher = MODEL_RULE.matcher(cel);
        if (!matcher.matches()) {
            report.add(Kind.NOT_MAPPED, where, "A route matches the model's name only, and this rule's condition is"
                    + " more than `model == '<name>'`; not carried over.");
            return null;
        }
        String model = matcher.group(2);
        if (routed.contains(model)) {
            report.add(Kind.NOT_MAPPED, where, "A rule that runs first already routes `" + model + "`; not carried"
                    + " over.");
            return null;
        }
        if (!(rule.get("targets") instanceof List<?> targetList) || targetList.isEmpty()) {
            report.add(Kind.NOT_MAPPED, where, "No targets; not read.");
            return null;
        }
        // Targets that send the same model share the traffic; a target that sends another model becomes a backup.
        List<WeightedProvider> providers = new ArrayList<>();
        Map<ProviderType, Double> weights = new LinkedHashMap<>();
        String sent = null;
        List<Fallback> backups = new ArrayList<>();
        for (Object t : targetList) {
            if (!(t instanceof Map<?, ?> tm)) {
                report.add(Kind.NOT_MAPPED, where, "A target is not an object; not read.");
                continue;
            }
            Map<String, Object> target = stringKeys(tm);
            Fallback f = target(where, model, Objects.toString(target.get("provider"), null),
                    Objects.toString(target.get("model"), null));
            if (f == null) {
                continue;
            }
            if (target.get("provider_key_name") != null) {
                report.add(Kind.CHANGED, where, "provider_key_name: the gateway has one key per provider, so the"
                        + " target uses it.");
            }
            if (sent == null || sent.equals(f.model()) && !weights.containsKey(f.type())) {
                sent = sent == null ? f.model() : sent;
                Double w = number(target.get("weight"));
                weights.merge(f.type(), w == null || w <= 0 ? 1 : w, Double::sum);
            } else {
                backups.add(f);
            }
        }
        if (sent == null) {
            report.add(Kind.NOT_MAPPED, where, "None of its targets is served here; not carried over.");
            return null;
        }
        boolean fractional = weights.values().stream().anyMatch(w -> w != Math.rint(w));
        weights.forEach((type, w) -> providers.add(new WeightedProvider(type,
                weights.size() == 1 ? 1 : (int) Math.max(1, Math.round(fractional ? w * 100 : w)))));

        RouteDraft previous = routesByModel.remove(model);
        String id = previous != null ? previous.id : routeId(model);
        RouteDraft draft = new RouteDraft(id, model, sent.equals(model) ? null : sent, providers);
        routesByModel.put(model, draft);
        for (Fallback b : backups) {
            addFallback(draft, b, where);
        }
        if (!backups.isEmpty()) {
            report.add(Kind.CHANGED, where, "Its targets send different models. A route sends one model, so the first"
                    + " is served and the others became its backups: the traffic split became failover.");
        }
        if (rule.get("fallbacks") instanceof List<?> fallbacks) {
            for (Object fb : fallbacks) {
                String provider;
                String fbModel;
                if (fb instanceof String fs) {
                    int slash = fs.indexOf('/');
                    provider = slash < 0 ? fs : fs.substring(0, slash);
                    fbModel = slash < 0 ? "" : fs.substring(slash + 1);
                } else if (fb instanceof Map<?, ?> fm) {
                    Map<String, Object> f = stringKeys(fm);
                    provider = Objects.toString(f.get("provider"), null);
                    fbModel = Objects.toString(f.get("model"), "");
                } else {
                    continue;
                }
                Fallback f = target(where, model, provider, fbModel.isEmpty() ? null : fbModel);
                if (f != null) {
                    addFallback(draft, f, where);
                }
            }
        }
        report.add(Kind.MAPPED, where, "Route `" + draft.id + "` for `" + model + "`, to "
                + draft.providers.stream().map(p -> p.type().yamlType + (draft.providers.size() > 1 ? " (" + p.weight()
                + ")" : "")).collect(Collectors.joining(", ")) + (draft.pinnedModel != null ? ", sent as `"
                + draft.pinnedModel + "`" : "") + (draft.fallbacks.isEmpty() ? "" : ", failing over to "
                + draft.fallbacks.stream().map(f -> f.type().yamlType + "/" + f.model())
                .collect(Collectors.joining(", then ")))
                + ".");
        for (String key : rule.keySet()) {
            switch (key) {
                case "id", "name", "description", "cel_expression", "targets", "fallbacks", "enabled", "scope",
                     "scope_id" -> { }
                case "priority" -> { }
                default -> report.add(Kind.NOT_MAPPED, where, "`" + key + "` is not carried over.");
            }
        }
        return model;
    }

    /** A routing target or fallback as the gateway names it; null, with a report line, when it is not served. */
    private Fallback target(String where, String incoming, String provider, String model) {
        if (provider == null || provider.isBlank()) {
            report.add(Kind.NOT_MAPPED, where, "A target with no provider; not carried over.");
            return null;
        }
        Served s = served.get(provider);
        if (s == null) {
            report.add(Kind.NOT_MAPPED, where, "Target `" + provider + "` dropped: "
                    + (notServed.contains(provider) ? "that provider is not served here." : "no provider is called"
                    + " that."));
            return null;
        }
        String caller = model == null ? incoming : stripPrefix(provider, model);
        if (!serves(s, caller)) {
            report.add(Kind.NOT_MAPPED, where, "Target `" + provider + "/" + caller + "` dropped: that provider's keys"
                    + " do not serve it.");
            return null;
        }
        return new Fallback(s.type, s.dvara(caller));
    }

    private void addFallback(RouteDraft draft, Fallback fallback, String where) {
        boolean isPrimary = draft.sentModel().equals(fallback.model())
                && draft.providers.stream().anyMatch(p -> p.type() == fallback.type());
        if (isPrimary || draft.fallbacks.contains(fallback)) {
            return;
        }
        if (draft.fallbacks.size() >= MAX_FALLBACKS) {
            report.add(Kind.NOT_MAPPED, where, "Fallback " + fallback.type().yamlType + "/" + fallback.model()
                    + " dropped: a route's chain names at most " + MAX_FALLBACKS + " backups.");
            return;
        }
        draft.fallbacks.add(fallback);
    }

    // ------------------------------------------------------------------------------------------------
    // The allowlist and denylist for what the keys served
    // ------------------------------------------------------------------------------------------------

    private void buildServedPolicies() {
        if (served.isEmpty()) {
            return;
        }
        List<Served> open = served.values().stream().filter(Served::unrestricted).toList();
        if (open.size() == served.size()) {
            report.add(Kind.MAPPED, "providers", "Every provider serves every model, as in Bifrost, so no model"
                    + " allowlist was written.");
        } else {
            Set<String> models = servedModels();
            out.policies.add(new Policy(SERVED_POLICY, null, allowlistDsl(models,
                    "This model is not served by this gateway")));
            report.add(Kind.MAPPED, "providers", "Only the models on the providers' keys are served, as in Bifrost:"
                    + " policy `" + SERVED_POLICY + "` refuses any other model with 403.");
            for (Served s : open) {
                String what = s.wildcard ? "serves every model (`*`)" : "serves models by pattern ("
                        + backticked(s.patterns) + ")";
                report.add(Kind.ACTION, s.where, "It " + what + ", and policy `" + SERVED_POLICY + "` matches exact"
                        + " names only, so the models it served are refused with 403 unless routed above. Add each one"
                        + " you still serve to the policy's allowlist by hand, or delete the policy to serve every"
                        + " model.");
            }
        }
        Set<String> blocked = new TreeSet<>();
        for (Served s : served.values()) {
            for (String b : s.blocked) {
                blocked.add(s.prefixed(b));
                blocked.add(s.type.dvaraModel(b));
            }
        }
        if (!blocked.isEmpty()) {
            out.policies.add(new Policy(BLOCKED_POLICY, null, denylistDsl(blocked,
                    "This model is blocked on this gateway")));
            report.add(Kind.MAPPED, "blacklisted_models", "Policy `" + BLOCKED_POLICY + "` refuses "
                    + backticked(blocked) + " with 403.");
        }
    }

    /**
     * Every model a request may name or be sent as. A policy checks both the model the caller named and the
     * model the route sends, so the pins and the backups' models are listed too.
     */
    private Set<String> servedModels() {
        Set<String> models = new TreeSet<>();
        for (RouteDraft r : routesByModel.values()) {
            models.add(r.model);
            models.add(r.sentModel());
            r.fallbacks.forEach(f -> models.add(f.model()));
        }
        return models;
    }

    static String allowlistDsl(Set<String> models, String message) {
        return listDsl("only-listed-models", "allowlist", models, message);
    }

    static String denylistDsl(Set<String> models, String message) {
        return listDsl("blocked-models", "denylist", models, message);
    }

    private static String listDsl(String ruleId, String kind, Set<String> models, String message) {
        StringBuilder dsl = new StringBuilder();
        dsl.append("version: \"1\"\n");
        dsl.append("rules:\n");
        dsl.append("  - id: ").append(ruleId).append('\n');
        dsl.append("    conditions:\n");
        dsl.append("      model:\n");
        dsl.append("        ").append(kind).append(":\n");
        for (String m : models) {
            dsl.append("          - ").append(GatewayYamlWriter.quote(m)).append('\n');
        }
        dsl.append("    action: DENY\n");
        dsl.append("    deny_message: ").append(GatewayYamlWriter.quote(message)).append('\n');
        return dsl.toString();
    }

    // ------------------------------------------------------------------------------------------------
    // governance: virtual keys become workspaces
    // ------------------------------------------------------------------------------------------------

    private void indexGovernance(Map<String, Object> governance) {
        index(governance.get("rate_limits"), "governance.rate_limits", rateLimits);
        index(governance.get("teams"), "governance.teams", teams);
        index(governance.get("customers"), "governance.customers", customers);
    }

    private void index(Object value, String where, Map<String, Map<String, Object>> into) {
        if (value == null) {
            return;
        }
        if (!(value instanceof List<?> list)) {
            report.add(Kind.NOT_MAPPED, where, "Not a list; not read.");
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) instanceof Map<?, ?> m) {
                Map<String, Object> entry = stringKeys(m);
                String id = Objects.toString(entry.get("id"), "");
                if (!id.isBlank()) {
                    into.put(id, entry);
                    continue;
                }
            }
            report.add(Kind.NOT_MAPPED, where + "[" + i + "]", "No id; not read.");
        }
    }

    private void readVirtualKeys(Object value) {
        if (value == null) {
            return;
        }
        if (!(value instanceof List<?> list)) {
            report.add(Kind.NOT_MAPPED, "governance.virtual_keys", "Not a list; not read.");
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            String index = "governance.virtual_keys[" + i + "]";
            if (!(list.get(i) instanceof Map<?, ?> m)) {
                report.add(Kind.NOT_MAPPED, index, "Not an object; not read.");
                continue;
            }
            readVirtualKey(index, stringKeys(m));
        }
    }

    private static final Set<String> VK_FIELDS_HANDLED = Set.of("id", "name", "value", "is_active", "description",
            "provider_configs", "allow_all_providers", "rate_limit_id", "team_id", "customer_id");

    private void readVirtualKey(String index, Map<String, Object> vk) {
        String id = Objects.toString(vk.get("id"), "").strip();
        String where = id.isEmpty() ? index : index + " (" + id + ")";
        if (Boolean.FALSE.equals(vk.get("is_active"))) {
            report.add(Kind.NOT_MAPPED, where, "is_active: false; not carried over.");
            return;
        }
        if (!WORKSPACE_ID.matcher(id).matches() || id.equals(DEFAULT_WORKSPACE) || id.equalsIgnoreCase("__platform__")) {
            report.add(Kind.NOT_MAPPED, where, "id `" + id + "` cannot be a workspace id (1 to 36 letters, digits, '.',"
                    + " '_' or '-', and not `default`).");
            return;
        }

        // What the key may call, provider by provider. "everything" holds while it may call all the gateway serves,
        // and then the workspace needs no allowlist of its own.
        Set<String> allowed = new TreeSet<>();
        boolean everything = true;
        boolean anything = false;
        List<String> refusedOpen = new ArrayList<>();
        boolean allowAll = Boolean.TRUE.equals(vk.get("allow_all_providers"));
        Map<String, Map<String, Object>> configs = new LinkedHashMap<>();
        if (vk.get("provider_configs") instanceof List<?> pcs) {
            for (Object pc : pcs) {
                if (pc instanceof Map<?, ?> pm) {
                    Map<String, Object> c = stringKeys(pm);
                    configs.put(Objects.toString(c.get("provider"), "").toLowerCase(Locale.ROOT), c);
                }
            }
        }
        if (v1 && configs.isEmpty()) {
            allowAll = true;
        }
        for (String provider : configs.keySet()) {
            if (!served.containsKey(provider) && served.keySet().stream().noneMatch(p -> p.equalsIgnoreCase(provider))) {
                report.add(Kind.NOT_MAPPED, where, "provider_configs `" + provider + "`: "
                        + (notServed.stream().anyMatch(p -> p.equalsIgnoreCase(provider))
                        ? "that provider is not served here." : "no provider is called that."));
            }
        }
        boolean weighted = configs.values().stream().filter(c -> c.get("weight") != null).count() > 1;
        for (Served s : served.values()) {
            Map<String, Object> c = configs.get(s.name.toLowerCase(Locale.ROOT));
            Grant g;
            if (c == null) {
                g = allowAll ? Grant.everyModel() : Grant.noModel();
            } else {
                g = grant(where, s, c);
            }
            if (g.isNone()) {
                everything = false;
                continue;
            }
            anything = true;
            allowed.addAll(routeNames(s, g));
            if (g.all) {
                if (!g.blocked.isEmpty()) {
                    everything = false;
                }
                if (s.unrestricted()) {
                    // Only the routed models can be listed; the rest are refused if the workspace gets an allowlist.
                    refusedOpen.add(s.name);
                }
                continue;
            }
            everything = false;
            if (!g.patterns.isEmpty()) {
                report.add(Kind.ACTION, where, "provider_configs `" + s.name + "`: allowed_models "
                        + backticked(g.patterns) + " are patterns, and an allowlist matches exact names only. The"
                        + " workspace is refused the models they allowed; add the ones it may call to its policy by"
                        + " hand.");
            }
        }
        String policyId = (id.toLowerCase(Locale.ROOT).startsWith("vk") ? id : "vk-" + id) + "-models";
        if (!anything) {
            report.add(Kind.NOT_MAPPED, where, "It may call no model the gateway serves, so it is not carried over.");
            return;
        }
        if (!everything && allowed.isEmpty()) {
            // Without names to list, the workspace would be refused nothing: leave it out instead.
            report.add(Kind.ACTION, where, "Not carried over. It may call only some models, and none of them can be"
                    + " listed by name (they come from a wildcard or a pattern). Add a workspace `" + id + "` and an"
                    + " allowlist policy `" + policyId + "` by hand if you keep it.");
            return;
        }
        String name = vk.get("name") instanceof String n && !n.isBlank() ? n : null;
        Map<String, Object> metadata = new LinkedHashMap<>();
        limits(where, vk, metadata);
        out.workspaces.add(new Workspace(id, name, metadata));
        report.add(Kind.MAPPED, where, "Workspace `" + id + "`" + (name != null ? " (" + name + ")" : "") + ".");

        if (!everything) {
            out.policies.add(new Policy(policyId, id, allowlistDsl(allowed,
                    "This model is not available to this workspace")));
            report.add(Kind.MAPPED, where, "provider_configs: policy `" + policyId + "` allows only the models the"
                    + " key could call (and the models their routes send).");
            if (!refusedOpen.isEmpty()) {
                report.add(Kind.ACTION, where, "It could call every model of " + String.join(", ", refusedOpen)
                        + ", and policy `" + policyId + "` matches exact names only. The workspace is refused the"
                        + " ones with no route here; add those it may call to the policy by hand.");
            }
        }
        if (weighted) {
            report.add(Kind.CHANGED, where, "provider_configs weights: the gateway sends a model to the provider that"
                    + " serves it, not by the key's weights.");
        }
        virtualKeyValue(where, id, vk.get("value"));

        List<String> notCarried = vk.keySet().stream().filter(k -> !VK_FIELDS_HANDLED.contains(k)).toList();
        if (!notCarried.isEmpty()) {
            report.add(Kind.NOT_MAPPED, where, String.join(", ", notCarried) + ": not carried over (their values are not"
                    + " copied).");
        }
    }

    /** What a virtual key may call on one provider. */
    private record Grant(boolean all, Set<String> models, List<String> patterns, Set<String> blocked) {
        static Grant everyModel() {
            return new Grant(true, Set.of(), List.of(), Set.of());
        }

        static Grant noModel() {
            return new Grant(false, Set.of(), List.of(), Set.of());
        }

        boolean isNone() {
            return !all && models.isEmpty() && patterns.isEmpty();
        }
    }

    private Grant grant(String where, Served s, Map<String, Object> c) {
        String w = where + " provider_configs " + s.name;
        List<String> models = c.get("allowed_models") instanceof List<?> l
                ? l.stream().map(String::valueOf).map(String::strip).map(m -> stripPrefix(s.name, m)).toList()
                : List.of();
        if (models.isEmpty()) {
            if (!v1) {
                return Grant.noModel();
            }
            models = List.of("*");
        }
        Object keyIds = c.get("key_ids");
        List<String> ids = keyIds instanceof List<?> kl ? kl.stream().map(String::valueOf).toList() : List.of();
        if (ids.isEmpty() && v1) {
            ids = List.of("*");
        }
        if (!ids.contains("*") && !ids.contains(s.key.name()) && (s.key.id() == null || !ids.contains(s.key.id()))) {
            report.add(Kind.CHANGED, w, "key_ids does not name `" + s.key.name() + "`, the one key the gateway uses for"
                    + " " + s.name + ", so the workspace may not call " + s.name + ".");
            return Grant.noModel();
        }
        List<String> blacklist = c.get("blacklisted_models") instanceof List<?> bl
                ? bl.stream().map(String::valueOf).map(String::strip).map(m -> stripPrefix(s.name, m)).toList()
                : List.of();
        if (blacklist.contains("*")) {
            return Grant.noModel();
        }
        List<Pattern> blockPatterns = new ArrayList<>();
        for (String b : blacklist) {
            if (b.startsWith(REGEX_PREFIX)) {
                Pattern p = compile(b);
                if (p == null || (models.contains("*") && s.unrestricted())) {
                    report.add(Kind.ACTION, w, "blacklisted_models has the pattern `" + b + "`, which the gateway's"
                            + " denylist cannot hold, so the workspace may not call " + s.name + ". Write its"
                            + " allowlist by hand if it should.");
                    return Grant.noModel();
                }
                blockPatterns.add(p);
            }
        }
        Set<String> blocked = blacklist.stream().filter(b -> !b.startsWith(REGEX_PREFIX))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (c.get("rate_limit_id") != null) {
            report.add(Kind.NOT_MAPPED, w, "rate_limit_id: the gateway limits a workspace as a whole, not per"
                    + " provider.");
        }
        if (models.contains("*")) {
            if (blockPatterns.isEmpty()) {
                return new Grant(true, Set.of(), List.of(), blocked);
            }
            // Patterns on a provider whose models are all named: list the ones left.
            Set<String> left = s.models.keySet().stream().filter(m -> !isBlocked(m, blocked, blockPatterns))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            return new Grant(false, left, List.of(), Set.of());
        }
        Set<String> exact = new LinkedHashSet<>();
        List<String> patterns = new ArrayList<>();
        for (String m : models) {
            if (m.startsWith(REGEX_PREFIX)) {
                Pattern p = compile(m);
                if (p != null && !s.unrestricted()) {
                    s.models.keySet().stream().filter(n -> p.matcher(n).matches()).forEach(exact::add);
                } else {
                    patterns.add(m);
                }
            } else {
                exact.add(m);
            }
        }
        exact.removeIf(m -> isBlocked(m, blocked, blockPatterns));
        // The key can only narrow what its provider serves.
        exact.removeIf(m -> !s.unrestricted() && !s.models.containsKey(m));
        return new Grant(false, exact, patterns, Set.of());
    }

    /** The names a request for these models may carry, and the models their routes send. */
    private Set<String> routeNames(Served s, Grant g) {
        Set<String> names = new TreeSet<>();
        Collection<String> callers;
        if (g == null || g.all) {
            Set<String> routed = new LinkedHashSet<>(s.models.keySet());
            if (s.wildcard) {
                routed.addAll(mentioned.getOrDefault(s.name, Set.of()));
            }
            if (g != null) {
                routed.removeIf(m -> g.blocked.stream().anyMatch(b -> b.equalsIgnoreCase(m)));
            }
            callers = routed;
        } else {
            callers = g.models;
        }
        for (String m : callers) {
            names.add(m);
            names.add(s.prefixed(m));
            names.add(s.dvara(m));
            // A bare name another provider routes is listed, but not what that route sends: the policy checks the
            // sent model too, so the request is refused rather than sent to a provider the key could not call.
            // Backups on other providers are left off for the same reason.
            for (RouteDraft r : new RouteDraft[] {routesByModel.get(m), routesByModel.get(s.prefixed(m))}) {
                if (r != null && r.providers.stream().allMatch(p -> p.type() == s.type)) {
                    names.add(r.sentModel());
                }
            }
        }
        return names;
    }

    private void virtualKeyValue(String where, String workspace, Object value) {
        SecretValue.Ref ref = SecretValue.classify(value);
        String hash = "java -jar dvara-gateway-server-<version>-app.jar --hash-key -";
        switch (ref.kind()) {
            case ENV -> report.add(Kind.ACTION, where, "Its key is not carried over: gateway.yaml holds a SHA-256"
                    + " fingerprint of each key, never the key. To let callers keep it, fingerprint it with the"
                    + " gateway: `printf '%s' \"$" + (SecretValue.isValidName(ref.envName()) ? ref.envName()
                    : "<the key>") + "\" | " + hash + "`, and add the result under api_keys with workspace: "
                    + workspace + ". Bifrost callers send it as x-bf-vk or a bearer token; the gateway reads"
                    + " Authorization: Bearer.");
            case LITERAL -> report.add(Kind.ACTION, where, "Its key is written in the file as a literal. It was not"
                    + " copied. Mint a new key for workspace " + workspace + " with --generate-key, and rotate the old"
                    + " one, since it was stored in plain text.");
            case VAULT -> report.add(Kind.ACTION, where, "Its key is in a vault and was not read. Fingerprint it with"
                    + " `" + hash + "` and add it under api_keys with workspace: " + workspace + ", or mint a new one.");
            case NONE -> report.add(Kind.ACTION, where, "Bifrost generated its key, which is not in the file. Mint one"
                    + " for workspace " + workspace + " with --generate-key.");
        }
    }

    /** The key's request and token limits, with its team's and customer's, as per-minute workspace limits. */
    private void limits(String where, Map<String, Object> vk, Map<String, Object> metadata) {
        List<String[]> sources = new ArrayList<>();
        String rl = Objects.toString(vk.get("rate_limit_id"), null);
        if (rl != null) {
            sources.add(new String[] {"its own", rl});
        }
        String teamId = Objects.toString(vk.get("team_id"), null);
        String customerId = Objects.toString(vk.get("customer_id"), null);
        if (teamId != null) {
            Map<String, Object> team = teams.get(teamId);
            if (team == null) {
                report.add(Kind.NOT_MAPPED, where, "team_id `" + teamId + "`: no team is called that.");
            } else {
                usedTeams.add(teamId);
                if (team.get("rate_limit_id") != null) {
                    sources.add(new String[] {"team " + teamId + "'s", String.valueOf(team.get("rate_limit_id"))});
                }
                if (customerId == null && team.get("customer_id") != null) {
                    customerId = String.valueOf(team.get("customer_id"));
                }
            }
        }
        if (customerId != null) {
            Map<String, Object> customer = customers.get(customerId);
            if (customer == null) {
                report.add(Kind.NOT_MAPPED, where, "customer_id `" + customerId + "`: no customer is called that.");
            } else {
                usedCustomers.add(customerId);
                if (customer.get("rate_limit_id") != null) {
                    sources.add(new String[] {"customer " + customerId + "'s",
                            String.valueOf(customer.get("rate_limit_id"))});
                }
            }
        }
        for (String[] source : sources) {
            Map<String, Object> limit = rateLimits.get(source[1]);
            if (limit == null) {
                report.add(Kind.NOT_MAPPED, where, source[0] + " rate_limit_id `" + source[1] + "`: no rate limit is"
                        + " called that.");
                continue;
            }
            usedRateLimits.add(source[1]);
            boolean shared = !source[0].equals("its own");
            perMinute(where, source[0], shared, limit.get("request_max_limit"), limit.get("request_reset_duration"),
                    "rate-limit.requests-per-minute", "requests", metadata);
            perMinute(where, source[0], shared, limit.get("token_max_limit"), limit.get("token_reset_duration"),
                    "rate-limit.tokens-per-minute", "tokens", metadata);
        }
    }

    private void perMinute(String where, String owner, boolean shared, Object max, Object duration, String key,
                           String what, Map<String, Object> metadata) {
        if (max == null) {
            return;
        }
        Long n = positiveWhole(max);
        Long seconds = durationSeconds(Objects.toString(duration, ""));
        if (n == null || seconds == null) {
            report.add(Kind.NOT_MAPPED, where, owner + " " + what + " limit: not a limit and window the importer can"
                    + " read; not carried over.");
            return;
        }
        long perMinute = seconds == 60 ? n : Math.max(1, (long) Math.floor(n * 60.0 / seconds));
        if (perMinute > Integer.MAX_VALUE) {
            perMinute = Integer.MAX_VALUE;
        }
        Object current = metadata.get(key);
        if (current instanceof Integer c && c <= perMinute) {
            report.add(Kind.CHANGED, where, owner + " limit of " + n + " " + what + " per " + duration + " is looser"
                    + " than the " + c + " a minute already set, so the lower one is kept.");
            return;
        }
        metadata.put(key, (int) perMinute);
        String text = key + ": " + perMinute + " for the workspace, from " + owner + " " + n + " " + what + " per "
                + duration + ".";
        if (seconds != 60) {
            report.add(Kind.CHANGED, where, text + " The gateway counts per minute, so a limit over another window"
                    + " became its per-minute share" + (seconds > 60 ? ", which also caps bursts Bifrost allowed"
                    : "") + ".");
        } else if (shared) {
            report.add(Kind.CHANGED, where, text + " Bifrost shared it across every key of the " + owner.split(" ")[0]
                    + "; the gateway applies it to this workspace alone.");
        } else {
            report.add(Kind.MAPPED, where, text);
        }
    }

    /** Bifrost's window lengths: 30s, 5m, 1h, 1d, 1w, 1M, 1Q, 1Y. */
    static Long durationSeconds(String d) {
        Matcher m = Pattern.compile("(\\d+)([smhdwMQY])").matcher(d.strip());
        if (!m.matches()) {
            return null;
        }
        long n = Long.parseLong(m.group(1));
        long unit = switch (m.group(2)) {
            case "s" -> 1;
            case "m" -> 60;
            case "h" -> 3600;
            case "d" -> 86_400;
            case "w" -> 604_800;
            case "M" -> 2_592_000;
            case "Q" -> 7_776_000;
            default -> 31_536_000;
        };
        return n <= 0 ? null : n * unit;
    }

    private void readGovernanceRest(Map<String, Object> governance) {
        for (Map.Entry<String, Object> e : governance.entrySet()) {
            String key = e.getKey();
            String where = "governance." + key;
            switch (key) {
                case "virtual_keys", "routing_rules" -> { }
                case "rate_limits" -> rateLimits.keySet().stream().filter(id -> !usedRateLimits.contains(id))
                        .forEach(id -> report.add(Kind.NOT_MAPPED, where + " " + id, "No virtual key carried over uses"
                                + " it, so it is not carried over."));
                case "teams" -> teams.forEach((id, team) -> owner(where + " " + id, "team", team,
                        usedTeams.contains(id)));
                case "customers" -> customers.forEach((id, c) -> owner(where + " " + id, "customer", c,
                        usedCustomers.contains(id)));
                case "budgets" -> report.add(Kind.NOT_MAPPED, where, "The open gateway does not enforce spend budgets.");
                case "model_configs" -> report.add(Kind.NOT_MAPPED, where, "Per-model budgets and rate limits have no"
                        + " equivalent; the gateway limits each key and workspace.");
                case "pricing_overrides" -> report.add(Kind.NOT_MAPPED, where, "Custom prices are not read from"
                        + " gateway.yaml.");
                case "auth_config" -> report.add(Kind.NOT_MAPPED, where, "Bifrost's dashboard login; not carried over,"
                        + " and its values are not copied.");
                default -> report.add(Kind.NOT_MAPPED, where, "No equivalent in the open gateway; not carried over.");
            }
        }
    }

    /** A team or customer: no workspace of its own, but its rate limit reaches its virtual keys' workspaces. */
    private void owner(String where, String what, Map<String, Object> entry, boolean used) {
        if (!used) {
            report.add(Kind.NOT_MAPPED, where, "A " + what + " is reached through its virtual keys, and none carried"
                    + " over names it; not carried over.");
        } else if (entry.get("rate_limit_id") != null) {
            report.add(Kind.CHANGED, where, "No workspace of its own: its rate limit is applied to each of its virtual"
                    + " keys' workspaces.");
        } else {
            report.add(Kind.NOT_MAPPED, where, "No workspace of its own, and nothing on it carries over.");
        }
        List<String> other = entry.keySet().stream()
                .filter(k -> !Set.of("id", "name", "customer_id", "rate_limit_id").contains(k)).toList();
        if (!other.isEmpty()) {
            report.add(Kind.NOT_MAPPED, where, String.join(", ", other) + ": not carried over.");
        }
    }

    // ------------------------------------------------------------------------------------------------
    // plugins, client and guardrails_config
    // ------------------------------------------------------------------------------------------------

    private static final Set<String> OBSERVABILITY_PLUGINS = Set.of("maxim", "datadog", "splunk", "bigquery", "kafka",
            "pubsub");

    private void readPlugins(Object value) {
        if (value == null) {
            return;
        }
        if (!(value instanceof List<?> list)) {
            report.add(Kind.NOT_MAPPED, "plugins", "Not a list; not read.");
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> m)) {
                report.add(Kind.NOT_MAPPED, "plugins[" + i + "]", "Not an object; not read.");
                continue;
            }
            Map<String, Object> plugin = stringKeys(m);
            String name = Objects.toString(plugin.get("name"), "");
            String where = "plugins[" + i + "] (" + name + ")";
            if (!Boolean.TRUE.equals(plugin.get("enabled"))) {
                report.add(Kind.NOT_MAPPED, where, "Not enabled; not carried over.");
                continue;
            }
            if (plugin.get("path") != null) {
                report.add(Kind.NOT_MAPPED, where, "A custom plugin; the gateway does not load Bifrost plugins.");
                continue;
            }
            switch (name) {
                case "telemetry" -> report.add(Kind.MAPPED, where, "The gateway serves Prometheus metrics at"
                        + " /actuator/prometheus. Its push-gateway and label settings are not carried over.");
                case "otel" -> report.add(Kind.CHANGED, where, "The gateway traces with OpenTelemetry over OTLP; set"
                        + " management.otlp.tracing.endpoint to your collector. The plugin's settings, headers"
                        + " included, are not copied.");
                case "semantic_cache" -> report.add(Kind.NOT_MAPPED, where, "The open gateway has no response cache.");
                case "logging" -> report.add(Kind.CHANGED, where, "The gateway writes an access log without prompt text,"
                        + " and a tamper-evident audit log when DVARA_AUDIT_FILE_PATH is set; it keeps no request log"
                        + " store.");
                case "governance" -> report.add(Kind.MAPPED, where, "Always on in the gateway: every request needs an"
                        + " API key, which resolves to a workspace.");
                default -> report.add(Kind.NOT_MAPPED, where, OBSERVABILITY_PLUGINS.contains(name)
                        ? "No " + name + " integration. The gateway exports Prometheus metrics, OpenTelemetry traces"
                        + " and its own audit log."
                        : "No equivalent plugin; not carried over.");
            }
        }
    }

    private void readClient(Map<String, Object> client, boolean hasVirtualKeys) {
        for (Map.Entry<String, Object> e : client.entrySet()) {
            String key = e.getKey();
            String where = "client." + key;
            Object v = e.getValue();
            switch (key) {
                case "enforce_auth_on_inference" -> {
                    if (Boolean.TRUE.equals(v)) {
                        report.add(Kind.MAPPED, where, "The gateway refuses every request without an API key.");
                    }
                }
                case "allow_direct_keys" -> {
                    if (Boolean.TRUE.equals(v)) {
                        report.add(Kind.NOT_MAPPED, where, "Callers cannot send their own provider key with a request."
                                + " A workspace can bring its own key under `credentials`.");
                    }
                }
                case "drop_excess_requests", "initial_pool_size" -> report.add(Kind.NOT_MAPPED, where, "Bifrost's"
                        + " worker pool; the gateway has none to size.");
                case "enable_logging", "disable_content_logging", "log_retention_days", "logging_headers",
                     "hidden_request_types" -> report.add(Kind.NOT_MAPPED, where, "The gateway keeps no request log"
                        + " store. Its access log leaves out prompt text, and its audit log is a file you rotate.");
                case "prometheus_labels" -> report.add(Kind.NOT_MAPPED, where, "Custom metric labels are not carried"
                        + " over.");
                case "compat" -> report.add(Kind.CHANGED, where, "No setting. The gateway translates each request for"
                        + " the provider that serves it.");
                case "allowed_origins", "allowed_headers" -> report.add(Kind.NOT_MAPPED, where, "CORS is not set in"
                        + " gateway.yaml.");
                case "max_request_body_size_mb" -> report.add(Kind.NOT_MAPPED, where, "Not carried over; the gateway's"
                        + " request size limit is a Spring property.");
                default -> report.add(Kind.NOT_MAPPED, where, "No equivalent in the open gateway; not carried over.");
            }
        }
        if (!Boolean.TRUE.equals(client.get("enforce_auth_on_inference"))) {
            report.add(Kind.CHANGED, "client.enforce_auth_on_inference", "Bifrost served requests without a virtual"
                    + " key" + (hasVirtualKeys ? " as well as with one" : "") + ". The gateway refuses every request"
                    + " without an API key, so mint one for each caller.");
        }
    }

    private static final Set<String> PII_GUARDRAILS = Set.of("presidio", "azure-pii", "regex", "secrets");
    private static final Set<String> INJECTION_GUARDRAILS = Set.of("lakera");

    private void readGuardrails(Map<String, Object> config) {
        if (config.get("guardrail_providers") instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                if (!(list.get(i) instanceof Map<?, ?> m)) {
                    continue;
                }
                Map<String, Object> g = stringKeys(m);
                String kind = Objects.toString(g.get("provider_name"), "").toLowerCase(Locale.ROOT);
                String where = "guardrails_config.guardrail_providers[" + i + "] (" + kind + ")";
                if (Boolean.FALSE.equals(g.get("enabled"))) {
                    report.add(Kind.NOT_MAPPED, where, "Not enabled; not carried over.");
                    continue;
                }
                boolean detectOnly = String.valueOf(g.get("config")).contains("detect_only")
                        && !String.valueOf(g.get("config")).matches("(?s).*action=(redact|block).*");
                String settingKey;
                String settingValue;
                if (PII_GUARDRAILS.contains(kind)) {
                    settingKey = "pii.action";
                    settingValue = detectOnly ? "LOG" : "REDACT";
                } else if (INJECTION_GUARDRAILS.contains(kind)) {
                    settingKey = "guardrail.action";
                    settingValue = detectOnly ? "LOG" : "BLOCK";
                } else {
                    report.add(Kind.NOT_MAPPED, where, "`" + kind + "` is not called. The gateway runs its own PII and"
                            + " prompt-injection detection on every request.");
                    continue;
                }
                guardrailMetadata.merge(settingKey, settingValue, (a, b) -> "LOG".equals(a) ? b : a);
                report.add(Kind.MAPPED, where, "The gateway's own " + (settingKey.startsWith("pii") ? "PII detection"
                        : "prompt-injection guardrail") + ", with " + settingKey + ": " + settingValue + ". It does not"
                        + " call " + kind + " or use its settings" + (kind.equals("regex") ? " or patterns" : "")
                        + ".");
            }
        }
        if (config.get("guardrail_rules") != null) {
            report.add(Kind.CHANGED, "guardrails_config.guardrail_rules", "The gateway applies the workspace's PII and"
                    + " prompt-injection settings to every request, before it is sent; the rules' conditions,"
                    + " sampling and output checks are not carried over.");
        }
        config.keySet().stream().filter(k -> !k.equals("guardrail_providers") && !k.equals("guardrail_rules"))
                .forEach(k -> report.add(Kind.NOT_MAPPED, "guardrails_config." + k, "Not carried over."));
    }

    private void applyGuardrailMetadata() {
        if (guardrailMetadata.isEmpty()) {
            report.add(Kind.ADDED, "workspaces", "Workspace `default`. Its PII and prompt-injection detections are"
                    + " logged and the request goes through; pii.action: REDACT and guardrail.action: BLOCK make"
                    + " them strict (commented in gateway.yaml).");
            return;
        }
        for (int i = 0; i < out.workspaces.size(); i++) {
            Workspace w = out.workspaces.get(i);
            Map<String, Object> metadata = new LinkedHashMap<>(guardrailMetadata);
            metadata.putAll(w.metadata());
            out.workspaces.set(i, new Workspace(w.id(), w.name(), metadata));
        }
    }

    // ------------------------------------------------------------------------------------------------
    // The closing lines
    // ------------------------------------------------------------------------------------------------

    private void finalActions() {
        report.add(Kind.ACTION, "api_keys", "Mint the gateway's API keys. gateway.yaml has none, so the gateway"
                + " starts and refuses every call until you add one: `java -jar dvara-gateway-server-<version>-app.jar"
                + " --generate-key --name <name> --workspace " + DEFAULT_WORKSPACE + "` prints the key once and the"
                + " api_keys entry to paste.");
        if (out.workspaces.size() > 1) {
            report.add(Kind.ACTION, "workspaces", "A workspace is reached through its keys: mint one with"
                    + " --workspace <id> for each virtual key you keep.");
        }
        report.add(Kind.NOT_MAPPED, "(Bifrost config store)", "Providers, keys, virtual keys and rules added through"
                + " Bifrost's UI or API are in its config store, not in config.json, so they are not imported. Export"
                + " them into the file first.");
        report.add(Kind.CHANGED, "(requests)", "Bifrost callers can name fallbacks in the request body. The gateway"
                + " fails over on the route's chain in gateway.yaml; add a route with `fallbacks` to keep one.");
        if (!envNames.isEmpty()) {
            report.add(Kind.ACTION, "environment", "Set these variables for the gateway, which gateway.yaml reads"
                    + " at startup: " + String.join(", ", envNames) + ".");
        }
        if (!out.properties.isEmpty()) {
            report.add(Kind.ACTION, "dvara-import.properties", "Start the gateway with"
                    + " `--spring.config.additional-location=file:dvara-import.properties`, or set the same"
                    + " properties another way.");
        }
        report.add(Kind.ADDED, "", "Every request carries an API key that resolves to a workspace; gateway.yaml holds"
                + " only each key's SHA-256 fingerprint.");
        report.add(Kind.ADDED, "", "PII detection and a prompt-injection guardrail on every request, set per"
                + " workspace.");
        report.add(Kind.ADDED, "", "A policy engine: model allow and deny lists, max_tokens and tool rules, checked"
                + " on the model the route actually sends.");
        report.add(Kind.ADDED, "", "A tamper-evident audit log, each record chained to the last with an HMAC, when"
                + " DVARA_AUDIT_FILE_PATH and DVARA_AUDIT_HMAC_SECRET are set.");
        report.add(Kind.ADDED, "", "A circuit breaker per provider, a context-window check before each request is"
                + " sent, and Prometheus metrics and OpenTelemetry traces.");
    }

    // ------------------------------------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------------------------------------

    private void property(String where, String key, String value) {
        out.properties.put(key, value);
        report.add(Kind.MAPPED, where, "`" + key + "=" + value + "` in dvara-import.properties.");
    }

    private Map<String, Object> section(Map<String, Object> parent, String key) {
        return section(parent, key, key);
    }

    private Map<String, Object> section(Map<String, Object> parent, String key, String where) {
        Object value = parent.get(key);
        if (value == null) {
            return new LinkedHashMap<>();
        }
        if (value instanceof Map<?, ?> m) {
            return stringKeys(m);
        }
        report.add(Kind.NOT_MAPPED, where.equals(key) ? key : where + "." + key, "Not an object; not read.");
        return new LinkedHashMap<>();
    }

    private static String backticked(Collection<String> names) {
        return names.stream().map(n -> "`" + n + "`").collect(Collectors.joining(", "));
    }

    static Map<String, Object> stringKeys(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }

    static Double number(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return null;
    }

    static Long whole(Object value) {
        Double d = number(value);
        if (d == null || d != Math.rint(d) || Math.abs(d) > Integer.MAX_VALUE) {
            return null;
        }
        return d.longValue();
    }

    static Long positiveWhole(Object value) {
        Long n = whole(value);
        return n == null || n <= 0 ? null : n;
    }
}
