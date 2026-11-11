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

import com.dvarahq.autoconfigure.GatewayProperties;
import com.dvarahq.autoconfigure.config.yaml.GatewayYamlConfig;
import com.dvarahq.autoconfigure.config.yaml.GatewayYamlLoader;
import com.dvarahq.autoconfigure.config.yaml.YamlProviderType;
import com.dvarahq.autoconfigure.config.yaml.repo.YamlConfigStore;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.policy.Policy;
import com.dvarahq.core.policy.PolicyContext;
import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.routing.ModelPrefixRoutingStrategy;
import com.dvarahq.core.routing.RequestContext;
import com.dvarahq.core.routing.Route;
import com.dvarahq.core.routing.RouteConfig;
import com.dvarahq.core.routing.RoutingEngine;
import com.dvarahq.core.routing.WeightedRoutingStrategy;
import com.dvarahq.policy.CompiledPolicy;
import com.dvarahq.policy.PolicyCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The output is only worth something if the gateway accepts it. Each fixture is imported through the command
 * line, and what it writes is read back with the open gateway's own code: its file loader and validator, its
 * configuration store, its policy compiler, its routing engine and Spring's property binder.
 */
class GeneratedConfigLoadsTest {

    static Stream<String> fixtures() {
        return Fixtures.ALL.stream();
    }

    /** Runs the command line on a fixture and returns the folder it wrote. */
    private static Path importToDisk(String fixture, Path dir) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(new String[] {Fixtures.path(fixture).toString(), "-o", dir.toString()},
                new PrintStream(out), new PrintStream(err));
        assertThat(code).as("exit code; stderr: %s", err).isEqualTo(Main.OK);
        return dir;
    }

    /** Runs the command line on a config given as text and returns the folder it wrote. */
    private static Path importText(Path dir, String json) throws IOException {
        Path source = dir.resolve("config.json");
        Files.writeString(source, json);
        Path out = dir.resolve("out");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertThat(Main.run(new String[] {source.toString(), "-o", out.toString()},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(err)))
                .as("exit code; stderr: %s", err).isEqualTo(Main.OK);
        return out;
    }

    /** An environment where every variable gateway.yaml names is set, as an operator would set them. */
    private static Function<String, String> environment(Path dir) {
        return name -> name.equals("DVARA_CONFIG_FILE") ? dir.resolve(Main.GATEWAY_YAML).toString()
                : name.contains("ENDPOINT") || name.contains("URL") ? "https://example.test" : "value-of-" + name;
    }

    private static GatewayYamlConfig load(Path dir) {
        return GatewayYamlLoader.load(environment(dir)).orElseThrow();
    }

    private static GatewayProperties bindProperties(Path dir) throws IOException {
        Properties file = new Properties();
        file.load(new StringReader(Files.readString(dir.resolve(Main.PROPERTIES))));
        Map<String, String> map = new HashMap<>();
        file.forEach((k, v) -> map.put((String) k, (String) v));
        return new Binder(new MapConfigurationPropertySource(map))
                .bind("dvara.llm-gateway", GatewayProperties.class).get();
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    void theGatewaysLoaderReadsTheFileAndFindsNothingWrong(String fixture, @TempDir Path dir) {
        importToDisk(fixture, dir);
        GatewayYamlConfig config = load(dir);

        assertThat(GatewayYamlLoader.validate(config)).as("gateway.yaml validation errors").isEmpty();
        // The store refuses at construction what the loader lets through (a bad key entry, say).
        YamlConfigStore store = new YamlConfigStore(config);
        assertThat(store.routes()).hasSize(Fixtures.importFixture(fixture).config().routes.size());
        assertThat(store.workspaces()).hasSize(Fixtures.importFixture(fixture).config().workspaces.size());
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    void everyProviderBecomesThePropertiesItsBeanRegistersFrom(String fixture, @TempDir Path dir) {
        importToDisk(fixture, dir);
        GatewayYamlConfig config = load(dir);
        if (config.getProviders() == null) {
            return;
        }
        for (GatewayYamlConfig.ProviderEntry entry : config.getProviders()) {
            YamlProviderType type = YamlProviderType.of(entry.getType()).orElseThrow();
            Map<String, Object> props = new HashMap<>();
            type.mapTo(entry, props);
            String switchKey = type.propertyPrefix() + (type.switchedOn() == YamlProviderType.Switch.API_KEY
                    ? ".api-key" : ".enabled");
            assertThat(props).as("properties for %s", entry.getType()).containsKey(switchKey);
            assertThat(String.valueOf(props.get(switchKey))).isNotBlank();
            if (type.baseUrlRequiredToRegister()) {
                assertThat(props).as("Azure needs its base URL to register").containsKey(type.propertyPrefix() + ".base-url");
            }
        }
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    void everyRouteNamesConfiguredProvidersAndAChainTheGatewayAccepts(String fixture, @TempDir Path dir) {
        importToDisk(fixture, dir);
        GatewayYamlConfig config = load(dir);
        Set<String> providers = config.getProviders() == null ? Set.of()
                : config.getProviders().stream().map(GatewayYamlConfig.ProviderEntry::getType).collect(Collectors.toSet());
        for (Route route : new YamlConfigStore(config).routes()) {
            RouteConfig rc = routeConfig(route);
            assertThat(rc.invalidPin()).as("pin of %s", route.getId()).isNull();
            assertThat(rc.invalidFallbacks()).as("fallbacks of %s", route.getId()).isNull();
            route.getProviders().forEach(p -> assertThat(providers).as("route %s", route.getId()).contains(p.getProvider()));
            rc.getFallbacks().forEach(f -> assertThat(providers).as("fallback of %s", route.getId()).contains(f.provider()));
        }
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    void everyPolicyCompilesWithTheGatewaysPolicyCompiler(String fixture, @TempDir Path dir) {
        importToDisk(fixture, dir);
        YamlConfigStore store = new YamlConfigStore(load(dir));
        PolicyCompiler compiler = new PolicyCompiler();
        for (Policy policy : store.policies()) {
            assertThat(compiler.compile(policy).rules()).as("rules of %s", policy.getId()).isNotEmpty();
        }
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    void everyPropertyBindsToTheGatewaysProperties(String fixture, @TempDir Path dir) throws IOException {
        importToDisk(fixture, dir);
        if (!Files.exists(dir.resolve(Main.PROPERTIES))) {
            return;
        }
        GatewayProperties props = bindProperties(dir);
        Map<String, String> expected = Fixtures.importFixture(fixture).config().properties;
        // Each property names one provider's override; it must land on that provider's bound settings.
        for (Map.Entry<String, String> e : expected.entrySet()) {
            String[] parts = e.getKey().substring("dvara.llm-gateway.resilience.providers.".length()).split("\\.");
            GatewayProperties.ProviderResilienceOverride o = props.getResilience().getProviders().get(parts[0]);
            assertThat(o).as("override for %s", parts[0]).isNotNull();
            Object bound = switch (parts[1] + "." + parts[2]) {
                case "timeout.chat-timeout-ms" -> o.getTimeout().getChatTimeoutMs();
                case "retry.max-attempts" -> o.getRetry().getMaxAttempts();
                case "retry.initial-backoff-ms" -> o.getRetry().getInitialBackoffMs();
                case "retry.max-backoff-ms" -> o.getRetry().getMaxBackoffMs();
                default -> throw new AssertionError("unexpected property " + e.getKey());
            };
            assertThat(String.valueOf(bound)).as(e.getKey()).isEqualTo(e.getValue());
        }
    }

    @Test
    void theRetryAndTimeoutPropertiesBindToTheProvidersOverride(@TempDir Path dir) throws IOException {
        importToDisk("load-balancing.json", dir);
        GatewayProperties.ProviderResilienceOverride openai = bindProperties(dir).getResilience().getProviders()
                .get("openai");
        assertThat(openai.getRetry().getMaxAttempts()).isEqualTo(4);           // max_retries: 3
        assertThat(openai.getRetry().getInitialBackoffMs()).isEqualTo(500L);  // retry_backoff_initial: 500
        assertThat(openai.getRetry().getMaxBackoffMs()).isEqualTo(5000L);     // retry_backoff_max: 5000
        assertThat(openai.getTimeout().getChatTimeoutMs()).isEqualTo(120_000L); // default_request_timeout_in_seconds: 120
    }

    @Test
    void aProviderNameWithADashBindsAsItsOwnOverride(@TempDir Path dir) throws IOException {
        Path out = importText(dir, """
                {"providers": {"azure": {"keys": [{"name": "a", "value": "env.AZ", "models": ["gpt-4o"], "weight": 1,
                    "azure_key_config": {"endpoint": "https://r.openai.azure.com"}}],
                  "network_config": {"default_request_timeout_in_seconds": 90}}}}""");
        assertThat(bindProperties(out).getResilience().getProviders().get("azure-openai").getTimeout()
                .getChatTimeoutMs()).isEqualTo(90_000L);
    }

    @Test
    void aBifrostModelNameIsServedAsTheProvidersModelOnTheRightProvider(@TempDir Path dir) {
        importToDisk("load-balancing.json", dir);
        RoutingEngine engine = engine(new YamlConfigStore(load(dir)).routes());
        List<LlmProvider> providers = List.of(provider("openai"), provider("azure-openai"), provider("anthropic"));

        RequestContext prefixed = RequestContext.builder().build();
        assertThat(engine.route(ChatRequest.builder().model("openai/gpt-4o").build(), providers, prefixed).name())
                .isEqualTo("openai");
        assertThat(prefixed.getResolvedModel()).isEqualTo("gpt-4o");

        // The routing rule sends gpt-4o to Azure, as the key's alias names it, with its fallbacks after.
        RequestContext bare = RequestContext.builder().build();
        assertThat(engine.route(ChatRequest.builder().model("gpt-4o").build(), providers, bare).name())
                .isEqualTo("azure-openai");
        assertThat(bare.getResolvedModel()).isEqualTo("azure/gpt-4o-prod-deployment");
        assertThat(bare.getMatchedRoute().getFallbacks()).first()
                .isEqualTo(new RouteConfig.FallbackTarget("openai", "gpt-4o"));

        RequestContext claude = RequestContext.builder().build();
        assertThat(engine.route(ChatRequest.builder().model("anthropic/claude-haiku-4-5").build(), providers, claude)
                .name()).isEqualTo("anthropic");
        assertThat(claude.getResolvedModel()).isEqualTo("claude-haiku-4-5");
    }

    @Test
    void anOpenAiCompatibleServerGetsItsModelsEvenThoughNoProviderClaimsTheirNames(@TempDir Path dir) {
        importToDisk("self-hosted.json", dir);
        GatewayYamlConfig config = load(dir);
        assertThat(config.getProviders()).anySatisfy(p -> {
            assertThat(p.getType()).isEqualTo("openai");
            assertThat(p.getBaseUrl()).isEqualTo("http://vllm-server:8000/v1");
        });
        RoutingEngine engine = engine(new YamlConfigStore(config).routes());
        RequestContext ctx = RequestContext.builder().build();
        LlmProvider chosen = engine.route(ChatRequest.builder().model("llama-3-70b").build(),
                List.of(provider("anthropic"), provider("openai"), provider("ollama")), ctx);
        assertThat(chosen.name()).isEqualTo("openai");
        assertThat(ctx.getResolvedModel()).isEqualTo("meta-llama/Meta-Llama-3-70B-Instruct");
    }

    @Test
    void theAllowlistAndDenylistRefuseWhatBifrostRefused(@TempDir Path dir) {
        importToDisk("restricted.json", dir);
        YamlConfigStore store = new YamlConfigStore(load(dir));
        PolicyCompiler compiler = new PolicyCompiler();
        Map<String, CompiledPolicy> compiled = store.policies().stream()
                .collect(Collectors.toMap(Policy::getId, compiler::compile));

        CompiledPolicy served = compiled.get(BifrostImporter.SERVED_POLICY);
        assertThat(denies(served, "gpt-4o")).isFalse();
        assertThat(denies(served, "openai/best-model")).isFalse();
        assertThat(denies(served, "gpt-4o-2024-11-20")).as("the model the alias sends").isFalse();
        assertThat(denies(served, "gpt-4o-mini")).as("blacklisted on the key").isTrue();
        assertThat(denies(served, "o3")).as("never on a key").isTrue();

        CompiledPolicy blocked = compiled.get(BifrostImporter.BLOCKED_POLICY);
        assertThat(denies(blocked, "gemini-1.5-pro")).isTrue();
        assertThat(denies(blocked, "gemini-2.5-flash")).isFalse();
    }

    @Test
    void aVirtualKeysWorkspacePolicyAppliesToThatWorkspaceAlone(@TempDir Path dir) {
        importToDisk("enterprise.json", dir);
        YamlConfigStore store = new YamlConfigStore(load(dir));
        Policy support = store.policies().stream().filter(p -> p.getId().equals("vk-support-bot-models"))
                .findFirst().orElseThrow();
        assertThat(support.getWorkspaceId()).isEqualTo("vk-support-bot");
        CompiledPolicy compiled = new PolicyCompiler().compile(support);
        assertThat(denies(compiled, "gpt-4o-mini")).isFalse();
        assertThat(denies(compiled, "gpt-4o")).isTrue();
    }

    @Test
    void aServerWithCredentialsInItsUrlIsLoadedAtItsOwnHostAndTheCredentialsAreNowhere(@TempDir Path dir)
            throws IOException {
        Path out = importText(dir, """
                {"providers": {"openai": {"keys": [{"name": "o", "value": "env.OPENAI_API_KEY", "models": ["*"],
                    "weight": 1}],
                  "network_config": {"base_url": "https://svc:s3cret@llm.internal:8443/proxy?key=s3cret"}}}}""");
        GatewayYamlConfig config = load(out);
        assertThat(GatewayYamlLoader.validate(config)).isEmpty();
        assertThat(config.getProviders()).singleElement().satisfies(p -> {
            assertThat(p.getType()).isEqualTo("openai");
            assertThat(p.getBaseUrl()).isEqualTo("https://llm.internal:8443/proxy/v1");
        });
        for (String file : List.of(Main.GATEWAY_YAML, Main.REPORT)) {
            assertThat(Files.readString(out.resolve(file))).as(file).doesNotContain("s3cret");
        }
    }

    @Test
    void anEnvironmentBaseUrlIsResolvedByTheLoaderWithItsPath(@TempDir Path dir) throws IOException {
        Path out = importText(dir, """
                {"providers": {"mistral": {"keys": [{"name": "m", "value": "env.MISTRAL_API_KEY",
                    "models": ["mistral-large-latest"], "weight": 1}],
                  "network_config": {"base_url": "env.MISTRAL_URL"}}}}""");
        assertThat(load(out).getProviders()).singleElement()
                .satisfies(p -> assertThat(p.getBaseUrl()).isEqualTo("https://example.test/v1"));
    }

    @Test
    void aFileWithNoPropertiesToCarryWritesNoPropertiesFile(@TempDir Path dir) {
        importToDisk("quickstart.json", dir);
        assertThat(dir.resolve(Main.PROPERTIES)).doesNotExist();
    }

    // ------------------------------------------------------------------------------------------------

    private static boolean denies(CompiledPolicy policy, String model) {
        ChatRequest request = ChatRequest.builder().model(model).build();
        return policy.rules().stream().anyMatch(r -> "DENY".equals(r.action())
                && r.matcher().matches(PolicyContext.empty(), request));
    }

    /** The same conversion the gateway's route publisher makes, minus the strategies the file cannot name. */
    private static RouteConfig routeConfig(Route route) {
        return RouteConfig.builder()
                .id(route.getId())
                .modelPattern(route.getModelPattern())
                .strategy(com.dvarahq.autoconfigure.config.yaml.repo.YamlRouteRepository.toStrategy(route.getStrategy()))
                .providers(route.getProviders().stream()
                        .map(p -> RouteConfig.ProviderWeight.builder().provider(p.getProvider()).weight(p.getWeight()).build())
                        .toList())
                .pinnedModelVersion(route.getPinnedModelVersion())
                .fallbacks(route.getFallbacks() == null ? List.of() : route.getFallbacks())
                .build();
    }

    private static RoutingEngine engine(List<Route> routes) {
        List<RoutingEngine.ResolvedRoute> resolved = routes.stream().map(r -> {
            RouteConfig rc = routeConfig(r);
            assertThat(rc.getStrategy()).isEqualTo(RouteConfig.Strategy.WEIGHTED);
            return new RoutingEngine.ResolvedRoute(rc, new WeightedRoutingStrategy(rc.getProviders()));
        }).toList();
        return new RoutingEngine(new ModelPrefixRoutingStrategy(), resolved);
    }

    /** A provider that only has a name; routing chooses by name, and nothing here calls it. */
    private static LlmProvider provider(String name) {
        return (LlmProvider) Proxy.newProxyInstance(LlmProvider.class.getClassLoader(),
                new Class<?>[] {LlmProvider.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "name", "toString" -> name;
                    case "supports" -> false;
                    case "hashCode" -> name.hashCode();
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
