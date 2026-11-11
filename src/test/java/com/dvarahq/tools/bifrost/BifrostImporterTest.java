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
import com.dvarahq.tools.bifrost.DvaraConfig.Provider;
import com.dvarahq.tools.bifrost.DvaraConfig.Route;
import com.dvarahq.tools.bifrost.DvaraConfig.WeightedProvider;
import com.dvarahq.tools.bifrost.DvaraConfig.Workspace;
import com.dvarahq.tools.bifrost.ImportReport.Finding;
import com.dvarahq.tools.bifrost.ImportReport.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BifrostImporterTest {

    private static BifrostImporter.Result importJson(String json) {
        return BifrostImporter.importJson(json);
    }

    /** A file with one provider, whose body is given. */
    private static String provider(String name, String body) {
        return "{\"providers\": {\"" + name + "\": " + body + "}}";
    }

    /** A provider body with one key: its fields, then the provider's other fields. */
    private static String oneKey(String keyFields, String providerFields) {
        return "{\"keys\": [{\"name\": \"k\", \"weight\": 1.0" + (keyFields.isBlank() ? "" : ", " + keyFields) + "}]"
                + (providerFields.isBlank() ? "" : ", " + providerFields) + "}";
    }

    private static Optional<Route> findRoute(BifrostImporter.Result r, String model) {
        return r.config().routes.stream().filter(x -> x.model().equals(model)).findFirst();
    }

    private static Route route(BifrostImporter.Result r, String model) {
        return findRoute(r, model).orElseThrow(() -> new AssertionError("no route for " + model + " in "
                + r.config().routes));
    }

    private static List<String> texts(BifrostImporter.Result r, Kind kind) {
        return r.report().of(kind).stream().map(f -> f.where() + ": " + f.text()).toList();
    }

    private static String everything(BifrostImporter.Result r) {
        return GatewayYamlWriter.write(r.config(), "x") + GatewayYamlWriter.writeProperties(r.config(), "x")
                + r.report().toMarkdown("x", true);
    }

    private static DvaraConfig.Policy policy(BifrostImporter.Result r, String id) {
        return r.config().policies.stream().filter(p -> p.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no policy " + id + " in " + r.config().policies));
    }

    // ------------------------------------------------------------------------------------------------
    // Providers and model names
    // ------------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} {1} -> {2} {3}")
    @CsvSource({
            "openai, gpt-4o, openai, gpt-4o",
            "anthropic, claude-sonnet-4-5, anthropic, claude-sonnet-4-5",
            "gemini, gemini-2.5-flash, gemini, gemini-2.5-flash",
            "mistral, mistral-large-latest, mistral, mistral-large-latest",
            "cohere, command-r-plus, cohere, command-r-plus",
            "groq, llama-3.3-70b-versatile, groq, groq/llama-3.3-70b-versatile",
            "deepseek, deepseek-chat, deepseek, deepseek-chat",
            "xai, grok-4, grok, grok-4",
    })
    void eachSupportedProviderMapsToItsTypeAndTheModelNameTheGatewayUses(String bifrost, String model, String type,
                                                                         String sent) {
        BifrostImporter.Result r = importJson(provider(bifrost, oneKey(
                "\"value\": \"env.KEY\", \"models\": [\"" + model + "\"]", "")));
        assertThat(r.config().providers.values()).singleElement()
                .satisfies(p -> assertThat(p.type().yamlType).isEqualTo(type));
        // A Bifrost caller may send provider/model or the bare name; both reach the provider, sent as the gateway names it.
        Route prefixed = route(r, bifrost + "/" + model);
        assertThat(prefixed.pinnedModel()).isEqualTo(sent.equals(bifrost + "/" + model) ? null : sent);
        assertThat(prefixed.providers()).containsExactly(new WeightedProvider(prefixed.providers().get(0).type(), 1));
        assertThat(prefixed.providers().get(0).type().yamlType).isEqualTo(type);
        Route bare = route(r, model);
        assertThat(bare.pinnedModel()).isEqualTo(sent.equals(model) ? null : sent);
    }

    @Test
    void ollamaBedrockAndAzureMapWithTheirOwnSettings() {
        BifrostImporter.Result ollama = importJson(provider("ollama", oneKey(
                "\"value\": \"\", \"models\": [\"llama3.1\"], \"ollama_key_config\": {\"url\": \"http://ollama:11434\"}",
                "")));
        assertThat(ollama.config().providers.get(ProviderType.OLLAMA).baseUrl())
                .isEqualTo(UrlValue.literal("http://ollama:11434"));
        assertThat(route(ollama, "ollama/llama3.1").pinnedModel()).isNull();
        assertThat(route(ollama, "llama3.1").pinnedModel()).isEqualTo("ollama/llama3.1");

        BifrostImporter.Result bedrock = importJson(provider("bedrock", oneKey(
                "\"value\": \"\", \"models\": [\"anthropic.claude-3-5-sonnet-20240620-v1:0\"], \"bedrock_key_config\":"
                        + " {\"region\": \"eu-west-1\", \"access_key\": \"env.AK\", \"secret_key\": \"env.SK\","
                        + " \"session_token\": \"env.TOKEN\"}", "")));
        Provider b = bedrock.config().providers.get(ProviderType.BEDROCK);
        assertThat(b.accessKey()).isEqualTo(new SecretValue("AK"));
        assertThat(b.secretKey()).isEqualTo(new SecretValue("SK"));
        assertThat(b.region()).isEqualTo(UrlValue.literal("eu-west-1"));
        assertThat(b.apiKey()).isNull();
        assertThat(b.baseUrl()).isNull();
        assertThat(route(bedrock, "anthropic.claude-3-5-sonnet-20240620-v1:0").pinnedModel())
                .isEqualTo("bedrock/anthropic.claude-3-5-sonnet-20240620-v1:0");
        assertThat(texts(bedrock, Kind.NOT_MAPPED)).anySatisfy(t -> assertThat(t).contains("session_token"));

        BifrostImporter.Result azure = importJson(provider("azure", oneKey(
                "\"value\": \"env.AZ\", \"models\": [\"gpt-4o\"], \"aliases\": {\"gpt-4o\": \"gpt-4o-prod\"},"
                        + " \"azure_key_config\": {\"endpoint\": \"https://res.openai.azure.com/\", \"api_version\":"
                        + " \"2024-10-21\"}", "")));
        Provider a = azure.config().providers.get(ProviderType.AZURE_OPENAI);
        assertThat(a.baseUrl()).isEqualTo(UrlValue.literal("https://res.openai.azure.com/openai"));
        assertThat(route(azure, "azure/gpt-4o").pinnedModel()).isEqualTo("azure/gpt-4o-prod");
        assertThat(texts(azure, Kind.CHANGED)).anySatisfy(t -> assertThat(t).contains("api_version"));
    }

    @ParameterizedTest
    @CsvSource({
            "vertex, Vertex AI",
            "bedrock_mantle, Bedrock Mantle",
            "openrouter, no provider for `openrouter`",
            "perplexity, no provider for `perplexity`",
    })
    void aProviderTheGatewayLacksIsReportedWithTheReasonAndWritesNothing(String bifrost, String reason) {
        BifrostImporter.Result r = importJson(provider(bifrost, oneKey("\"value\": \"env.K\", \"models\": [\"*\"]", "")));
        assertThat(r.config().providers).isEmpty();
        assertThat(r.config().routes).isEmpty();
        assertThat(texts(r, Kind.NOT_MAPPED)).anySatisfy(t -> assertThat(t).startsWith("providers." + bifrost)
                .contains("Not served").contains(reason));
    }

    @Test
    void aCustomProviderOnABaseTheGatewayLacksIsNotServed() {
        BifrostImporter.Result r = importJson(provider("acme", oneKey("\"value\": \"env.K\", \"models\": [\"*\"]",
                "\"custom_provider_config\": {\"base_provider_type\": \"cerebras\"}, \"network_config\":"
                        + " {\"base_url\": \"https://acme.example\"}")));
        assertThat(r.config().providers).isEmpty();
        assertThat(texts(r, Kind.NOT_MAPPED)).anySatisfy(t -> assertThat(t).contains("custom provider built on"));
    }

    @Test
    void aProviderThatServesEveryModelNeedsNoRouteAndNoAllowlist() {
        BifrostImporter.Result r = Fixtures.importFixture("quickstart.json");
        assertThat(r.config().providers).containsOnlyKeys(ProviderType.OPENAI, ProviderType.ANTHROPIC,
                ProviderType.GROQ);
        assertThat(r.config().routes).isEmpty();
        assertThat(r.config().policies).isEmpty();
        assertThat(texts(r, Kind.MAPPED)).anySatisfy(t -> assertThat(t).contains("no model allowlist"));
        assertThat(texts(r, Kind.CHANGED)).anySatisfy(t -> assertThat(t).startsWith("providers.groq")
                .contains("`groq/<model>`"));
    }

    @Test
    void anEmptyModelListServesNothingButVersionOneReadsItAsEveryModel() {
        String body = oneKey("\"value\": \"env.K\", \"models\": []", "");
        BifrostImporter.Result v2 = importJson(provider("openai", body));
        assertThat(v2.config().providers).isEmpty();
        assertThat(texts(v2, Kind.NOT_MAPPED)).anySatisfy(t -> assertThat(t).contains("no model at all"));

        BifrostImporter.Result v1 = importJson("{\"version\": 1, \"providers\": {\"openai\": " + body + "}}");
        assertThat(v1.config().providers).containsOnlyKeys(ProviderType.OPENAI);
        assertThat(v1.config().policies).isEmpty();
    }

    @Test
    void aDisabledKeyIsSkippedAndTheNextOneIsServed() {
        BifrostImporter.Result r = importJson(provider("openai", """
                {"keys": [
                  {"name": "off", "value": "env.OFF", "models": ["*"], "weight": 1, "enabled": false},
                  {"name": "on", "value": "env.ON", "models": ["gpt-4o"], "weight": 1}
                ]}"""));
        assertThat(r.config().providers.get(ProviderType.OPENAI).apiKey()).isEqualTo(new SecretValue("ON"));
        assertThat(texts(r, Kind.NOT_MAPPED)).anySatisfy(t -> assertThat(t).contains("(off)").contains("enabled: false"));
    }

    // ------------------------------------------------------------------------------------------------
    // Secrets
    // ------------------------------------------------------------------------------------------------

    @Test
    void anEnvironmentReferenceBecomesTheSameVariable() {
        BifrostImporter.Result r = importJson(provider("openai", oneKey(
                "\"value\": \"env.MY_OPENAI_KEY\", \"models\": [\"*\"]", "")));
        assertThat(r.config().providers.get(ProviderType.OPENAI).apiKey()).isEqualTo(new SecretValue("MY_OPENAI_KEY"));
        assertThat(GatewayYamlWriter.write(r.config(), "x")).contains("api_key: ${MY_OPENAI_KEY}");
    }

    @Test
    void theObjectFormOfASecretIsReadByItsVariable() {
        BifrostImporter.Result r = importJson(provider("openai", oneKey(
                "\"value\": {\"value\": \"\", \"env_var\": \"env.FROM_OBJECT\", \"from_env\": true}, \"models\": [\"*\"]",
                "")));
        assertThat(r.config().providers.get(ProviderType.OPENAI).apiKey()).isEqualTo(new SecretValue("FROM_OBJECT"));
    }

    @Test
    void aLiteralKeyIsReplacedByAVariableAndTheOperatorIsToldToRotateIt() {
        BifrostImporter.Result r = importJson(provider("anthropic", oneKey(
                "\"value\": \"sk-ant-THIS-IS-A-LITERAL\", \"models\": [\"*\"]", "")));
        assertThat(r.config().providers.get(ProviderType.ANTHROPIC).apiKey())
                .isEqualTo(new SecretValue("ANTHROPIC_API_KEY"));
        assertThat(texts(r, Kind.ACTION)).anySatisfy(t -> assertThat(t).contains("literal").contains("rotate"));
        assertThat(everything(r)).doesNotContain("THIS-IS-A-LITERAL");
    }

    @Test
    void aVaultReferenceOrABadVariableNameIsNotCopiedAndNamesTheDefaultVariable() {
        BifrostImporter.Result vault = importJson(provider("openai", oneKey(
                "\"value\": \"vault.secret/data/openai\", \"models\": [\"*\"]", "")));
        assertThat(vault.config().providers.get(ProviderType.OPENAI).apiKey()).isEqualTo(new SecretValue("OPENAI_API_KEY"));
        assertThat(texts(vault, Kind.ACTION)).anySatisfy(t -> assertThat(t).contains("vault"));
        assertThat(everything(vault)).doesNotContain("secret/data/openai");

        BifrostImporter.Result bad = importJson(provider("openai", oneKey(
                "\"value\": \"env.NOT-A-NAME\", \"models\": [\"*\"]", "")));
        assertThat(bad.config().providers.get(ProviderType.OPENAI).apiKey()).isEqualTo(new SecretValue("OPENAI_API_KEY"));
        assertThat(texts(bad, Kind.ACTION)).anySatisfy(t -> assertThat(t).contains("cannot read"));
    }

    @Test
    void aKeylessAzureKeyIsToldToSetAnApiKey() {
        BifrostImporter.Result r = importJson(provider("azure", oneKey(
                "\"value\": \"\", \"models\": [\"gpt-4o\"], \"azure_key_config\": {\"endpoint\": \"env.AZURE_ENDPOINT\","
                        + " \"client_id\": \"env.CID\", \"client_secret\": \"LITERAL-ENTRA\"}", "")));
        Provider a = r.config().providers.get(ProviderType.AZURE_OPENAI);
        assertThat(a.apiKey()).isEqualTo(new SecretValue("AZURE_API_KEY"));
        assertThat(a.baseUrl()).isEqualTo(UrlValue.fromEnv("AZURE_ENDPOINT", "/openai"));
        assertThat(texts(r, Kind.ACTION)).anySatisfy(t -> assertThat(t).contains("DefaultAzureCredential"));
        assertThat(texts(r, Kind.NOT_MAPPED)).anySatisfy(t -> assertThat(t).contains("client_secret"));
        assertThat(everything(r)).doesNotContain("LITERAL-ENTRA");
    }

    @Test
    void aLiteralAwsSecretIsNotCopied() {
        BifrostImporter.Result r = importJson(provider("bedrock", oneKey(
                "\"value\": \"\", \"models\": [\"*\"], \"bedrock_key_config\": {\"region\": \"us-east-1\","
                        + " \"access_key\": \"AKIA-LITERAL\", \"secret_key\": \"SECRET-LITERAL\"}", "")));
        Provider b = r.config().providers.get(ProviderType.BEDROCK);
        assertThat(b.accessKey()).isEqualTo(new SecretValue("AWS_ACCESS_KEY_ID"));
        assertThat(b.secretKey()).isEqualTo(new SecretValue("AWS_SECRET_ACCESS_KEY"));
        assertThat(everything(r)).doesNotContain("AKIA-LITERAL").doesNotContain("SECRET-LITERAL");
    }

    // ------------------------------------------------------------------------------------------------
    // Base URLs: a provider at a server of its own never falls back to the public API
    // ------------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({
            "openai, https://llm.corp.example, https://llm.corp.example/v1",
            "openai, https://llm.corp.example/, https://llm.corp.example/v1",
            "openai, https://llm.corp.example/v1, https://llm.corp.example/v1",
            "mistral, https://mistral.corp.example, https://mistral.corp.example/v1",
            "groq, https://groq.corp.example/openai, https://groq.corp.example/openai/v1",
            "xai, https://xai.corp.example, https://xai.corp.example/v1",
            "cohere, https://cohere.corp.example, https://cohere.corp.example/v2",
            "anthropic, https://claude.corp.example, https://claude.corp.example",
            "deepseek, https://ds.corp.example, https://ds.corp.example",
            "gemini, https://gemini.corp.example/v1beta, https://gemini.corp.example",
    })
    void aCustomBaseUrlBecomesTheFormTheGatewaysProviderExpects(String bifrost, String url, String written) {
        BifrostImporter.Result r = importJson(provider(bifrost, oneKey("\"value\": \"env.K\", \"models\": [\"*\"]",
                "\"network_config\": {\"base_url\": \"" + url + "\"}")));
        assertThat(r.config().providers.values()).singleElement()
                .satisfies(p -> assertThat(p.baseUrl()).isEqualTo(UrlValue.literal(written)));
    }

    @Test
    void theProvidersOwnDefaultUrlIsNotWritten() {
        BifrostImporter.Result r = importJson(provider("openai", oneKey("\"value\": \"env.K\", \"models\": [\"*\"]",
                "\"network_config\": {\"base_url\": \"https://api.openai.com/\"}")));
        assertThat(r.config().providers.get(ProviderType.OPENAI).baseUrl()).isNull();
    }

    // A server whose URL held credentials must stay at that
    // server, never default to api.openai.com.
    @ParameterizedTest
    @CsvSource({
            "openai, https://svc:s3cret@llm.corp.example?token=s3cret, https://llm.corp.example/v1",
            "openai, https://llm.corp.example/v1?api_key=s3cret#x, https://llm.corp.example/v1",
            "anthropic, https://u:s3cret@claude.corp.example, https://claude.corp.example",
    })
    void aBaseUrlWithCredentialsOrAQueryIsWrittenWithoutThemAtItsOwnHost(String bifrost, String url, String written) {
        BifrostImporter.Result r = importJson(provider(bifrost, oneKey("\"value\": \"env.K\", \"models\": [\"*\"]",
                "\"network_config\": {\"base_url\": \"" + url + "\"}")));
        assertThat(r.config().providers.values()).singleElement().satisfies(p -> assertThat(p.baseUrl())
                .as("never left to default to the public API").isEqualTo(UrlValue.literal(written)));
        assertThat(texts(r, Kind.ACTION)).anySatisfy(t -> assertThat(t).contains("not copied").contains("Rotate"));
        assertThat(everything(r)).doesNotContain("s3cret");
    }

    @ParameterizedTest
    @CsvSource({
            "env.NOT-A-NAME",
            "vault.kv/llm-url",
            "not a url",
            "ftp://llm.corp.example",
            "'https://user:p@ss@llm.corp.example'",
    })
    void aBaseUrlThatCannotBeWrittenMeansTheProviderIsNotServed(String url) {
        BifrostImporter.Result r = importJson(provider("openai", oneKey("\"value\": \"env.K\", \"models\": [\"*\"]",
                "\"network_config\": {\"base_url\": \"" + url + "\"}")));
        assertThat(r.config().providers).as("no provider that would call api.openai.com").isEmpty();
        assertThat(texts(r, Kind.ACTION)).anySatisfy(t -> assertThat(t).startsWith("providers.openai")
                .contains("Not served"));
    }

    @Test
    void aBaseUrlFromAVariableGetsThePathTheGatewayAdds() {
        BifrostImporter.Result r = importJson(provider("openai", oneKey("\"value\": \"env.K\", \"models\": [\"*\"]",
                "\"network_config\": {\"base_url\": \"env.LLM_URL\"}")));
        assertThat(r.config().providers.get(ProviderType.OPENAI).baseUrl()).isEqualTo(UrlValue.fromEnv("LLM_URL", "/v1"));
        assertThat(texts(r, Kind.ACTION)).anySatisfy(t -> assertThat(t).contains("LLM_URL").contains("/v1"));
    }

    @Test
    void anOpenAiCompatibleServerWithNoUrlIsNotServed() {
        BifrostImporter.Result vllm = importJson(provider("vllm", oneKey(
                "\"value\": \"\", \"models\": [\"m\"], \"vllm_key_config\": {\"model_name\": \"org/m\"}", "")));
        assertThat(vllm.config().providers).isEmpty();
        assertThat(texts(vllm, Kind.ACTION)).anySatisfy(t -> assertThat(t).contains("Not served").contains("no URL"));

        BifrostImporter.Result custom = importJson(provider("acme", oneKey("\"value\": \"env.K\", \"models\": [\"*\"]",
                "\"custom_provider_config\": {\"base_provider_type\": \"openai\"}")));
        assertThat(custom.config().providers).isEmpty();

        BifrostImporter.Result ollama = importJson(provider("ollama", oneKey("\"value\": \"\", \"models\": [\"*\"]", "")));
        assertThat(ollama.config().providers).isEmpty();
    }

    @Test
    void azureWithNoEndpointIsNotServedAndItsProviderBaseUrlIsNotUsed() {
        BifrostImporter.Result r = importJson(provider("azure", oneKey("\"value\": \"env.AZ\", \"models\": [\"gpt-4o\"]",
                "\"network_config\": {\"base_url\": \"https://elsewhere.example\"}")));
        assertThat(r.config().providers).isEmpty();
        assertThat(texts(r, Kind.ACTION)).anySatisfy(t -> assertThat(t).contains("azure_key_config.endpoint"));
    }

    @Test
    void bedrockAtAnEndpointOfItsOwnIsNotServed() {
        BifrostImporter.Result vpc = importJson(provider("bedrock", oneKey(
                "\"value\": \"\", \"models\": [\"*\"], \"bedrock_key_config\": {\"region\": \"us-east-1\","
                        + " \"endpoints\": [\"vpce-1.bedrock-runtime.us-east-1.vpce.amazonaws.com\"]}", "")));
        assertThat(vpc.config().providers).isEmpty();
        BifrostImporter.Result url = importJson(provider("bedrock", oneKey(
                "\"value\": \"\", \"models\": [\"*\"], \"bedrock_key_config\": {\"region\": \"us-east-1\"}",
                "\"network_config\": {\"base_url\": \"https://bedrock.corp.example\"}")));
        assertThat(url.config().providers).isEmpty();
        assertThat(texts(url, Kind.ACTION)).anySatisfy(t -> assertThat(t).contains("public regional endpoint"));
    }

    @Test
    void ollamasProviderBaseUrlWinsOverItsKeyUrlAsInBifrost() {
        BifrostImporter.Result r = importJson(provider("ollama", oneKey(
                "\"value\": \"\", \"models\": [\"*\"], \"ollama_key_config\": {\"url\": \"http://key-url:11434\"}",
                "\"network_config\": {\"base_url\": \"http://provider-url:11434\"}")));
        assertThat(r.config().providers.get(ProviderType.OLLAMA).baseUrl())
                .isEqualTo(UrlValue.literal("http://provider-url:11434"));
    }

    @Test
    void vllmIsServedAsAnOpenAiCompatibleServerAndSendsTheModelItRuns() {
        BifrostImporter.Result r = Fixtures.importFixture("self-hosted.json");
        Provider openai = r.config().providers.get(ProviderType.OPENAI);
        assertThat(openai.baseUrl()).isEqualTo(UrlValue.literal("http://vllm-server:8000/v1"));
        assertThat(openai.apiKey()).isEqualTo(new SecretValue("OPENAI_API_KEY"));
        assertThat(route(r, "vllm/llama-3-70b").pinnedModel()).isEqualTo("meta-llama/Meta-Llama-3-70B-Instruct");
        assertThat(route(r, "llama-3-70b").pinnedModel()).isEqualTo("meta-llama/Meta-Llama-3-70B-Instruct");
        // The second vLLM server and the custom provider would need a second openai endpoint.
        assertThat(findRoute(r, "mistral-7b")).isEmpty();
        assertThat(texts(r, Kind.NOT_MAPPED)).anySatisfy(t -> assertThat(t).startsWith("providers.acme-llm")
                .contains("already uses"));
        assertThat(texts(r, Kind.ACTION)).anySatisfy(t -> assertThat(t).contains("any value it accepts"));
    }

    // ------------------------------------------------------------------------------------------------
    // Several keys: one endpoint per provider
    // ------------------------------------------------------------------------------------------------

    @Test
    void severalKeysWithDifferentCredentialsServeTheFirstAndSayWhy() {
        BifrostImporter.Result r = Fixtures.importFixture("load-balancing.json");
        assertThat(r.config().providers.get(ProviderType.OPENAI).apiKey()).isEqualTo(new SecretValue("OPENAI_KEY_1"));
        assertThat(findRoute(r, "o4-mini")).as("only the second key served it").isEmpty();
        assertThat(texts(r, Kind.NOT_MAPPED)).anySatisfy(t -> assertThat(t)
                .startsWith("providers.openai.keys[1] (openai-secondary)").contains("different key or URL"));
        assertThat(texts(r, Kind.CHANGED)).anySatisfy(t -> assertThat(t).startsWith("providers.openai:")
                .contains("3 keys by weight").contains("openai-primary"));
        // The keys that are not served add nothing to the instructions.
        assertThat(texts(r, Kind.ACTION)).noneSatisfy(t -> assertThat(t).contains("OPENAI_KEY_2"));
        assertThat(texts(r, Kind.ACTION)).noneSatisfy(t -> assertThat(t).contains("AZURE_ENDPOINT_WEST"));
    }

    @Test
    void keysWithTheSameCredentialsShareTheirModels() {
        BifrostImporter.Result r = importJson(provider("openai", """
                {"keys": [
                  {"name": "a", "value": "env.K", "models": ["gpt-4o"], "weight": 2},
                  {"name": "b", "value": "env.K", "models": ["gpt-4o-mini"], "weight": 1}
                ]}"""));
        assertThat(r.config().routes).extracting(Route::model).contains("gpt-4o", "gpt-4o-mini");
    }

    @Test
    void aSecondProviderOnTheSameGatewayProviderIsNotServed() {
        BifrostImporter.Result r = importJson("""
                {"providers": {
                  "openai": {"keys": [{"name": "o", "value": "env.O", "models": ["gpt-4o"], "weight": 1}]},
                  "sgl": {"keys": [{"name": "s", "value": "", "models": ["*"], "weight": 1,
                                    "sgl_key_config": {"url": "http://sgl:30000"}}]}
                }}""");
        assertThat(r.config().providers.get(ProviderType.OPENAI).baseUrl()).isNull();
        assertThat(texts(r, Kind.NOT_MAPPED)).anySatisfy(t -> assertThat(t).startsWith("providers.sgl")
                .contains("one openai endpoint"));
    }

    // ------------------------------------------------------------------------------------------------
    // Model lists, aliases and blacklists: allowlists fail closed
    // ------------------------------------------------------------------------------------------------

    @Test
    void theAllowlistNamesEveryModelARequestMayNameOrBeSent() {
        BifrostImporter.Result r = Fixtures.importFixture("load-balancing.json");
        DvaraConfig.Policy p = policy(r, BifrostImporter.SERVED_POLICY);
        assertThat(p.workspace()).isNull();
        assertThat(p.dsl()).contains("\"gpt-4o\"", "\"openai/gpt-4o\"", "\"azure/gpt-4o\"",
                "\"azure/gpt-4o-prod-deployment\"", "\"claude-sonnet-4-5\"", "\"anthropic/claude-haiku-4-5\"",
                "action: DENY");
    }

    @Test
    void aliasesBecomeRoutesThatSendTheirModel() {
        BifrostImporter.Result r = Fixtures.importFixture("restricted.json");
        assertThat(route(r, "best-model").pinnedModel()).isEqualTo("gpt-4o-2024-11-20");
        assertThat(route(r, "openai/best-model").pinnedModel()).isEqualTo("gpt-4o-2024-11-20");
        assertThat(findRoute(r, "fast-model")).as("an alias the key's models do not list").isEmpty();
        assertThat(texts(r, Kind.NOT_MAPPED)).anySatisfy(t -> assertThat(t).contains("Alias `fast-model`"));
    }

    @Test
    void anAliasThatOverridesItsEndpointIsNotRouted() {
        BifrostImporter.Result r = importJson(provider("azure", oneKey(
                "\"value\": \"env.AZ\", \"models\": [\"*\"], \"azure_key_config\": {\"endpoint\": \"env.AZ_URL\"},"
                        + " \"aliases\": {\"big\": {\"model_id\": \"dep-2\", \"endpoint\": \"env.OTHER\"},"
                        + " \"small\": {\"model_id\": \"dep-1\", \"model_name\": \"gpt-4o-mini\"}}", "")));
        assertThat(findRoute(r, "big")).isEmpty();
        assertThat(route(r, "small").pinnedModel()).isEqualTo("azure/dep-1");
        assertThat(texts(r, Kind.NOT_MAPPED)).anySatisfy(t -> assertThat(t).contains("`big` overrides endpoint"));
    }

    @Test
    void aBlacklistedModelIsLeftOffTheAllowlistOrDeniedWhenEveryModelIsServed() {
        BifrostImporter.Result r = Fixtures.importFixture("restricted.json");
        assertThat(findRoute(r, "gpt-4o-mini")).isEmpty();
        assertThat(policy(r, BifrostImporter.SERVED_POLICY).dsl()).doesNotContain("\"gpt-4o-mini\"");
        assertThat(policy(r, BifrostImporter.BLOCKED_POLICY).dsl()).contains("denylist", "\"gemini-1.5-pro\"",
                "\"gemini/gemini-1.5-pro\"");
    }

    @Test
    void aBlacklistOfEverythingServesNothing() {
        BifrostImporter.Result r = importJson(provider("openai", oneKey(
                "\"value\": \"env.K\", \"models\": [\"*\"], \"blacklisted_models\": [\"*\"]", "")));
        assertThat(r.config().providers).isEmpty();
    }

    @Test
    void aBlacklistPatternIsAppliedToNamedModelsButMeansNotServedWhenEveryModelIsServed() {
        BifrostImporter.Result named = importJson(provider("openai", oneKey(
                "\"value\": \"env.K\", \"models\": [\"gpt-4o\", \"gpt-4o-mini\"], \"blacklisted_models\":"
                        + " [\"regex:.*-mini\"]", "")));
        assertThat(named.config().routes).extracting(Route::model).contains("gpt-4o").doesNotContain("gpt-4o-mini");

        BifrostImporter.Result open = importJson(provider("openai", oneKey(
                "\"value\": \"env.K\", \"models\": [\"*\"], \"blacklisted_models\": [\"regex:.*-mini\"]", "")));
        assertThat(open.config().providers).as("a refusal by pattern cannot be written, so fail closed").isEmpty();
        assertThat(texts(open, Kind.ACTION)).anySatisfy(t -> assertThat(t).contains("regex:.*-mini"));
    }

    @Test
    void modelsServedByPatternOrWildcardAreRefusedByTheAllowlistAndTheReportSaysSo() {
        BifrostImporter.Result r = Fixtures.importFixture("restricted.json");
        assertThat(policy(r, BifrostImporter.SERVED_POLICY).dsl()).doesNotContain("regex:").doesNotContain("*");
        assertThat(texts(r, Kind.ACTION))
                .anySatisfy(t -> assertThat(t).startsWith("providers.openai").contains("by pattern")
                        .contains(BifrostImporter.SERVED_POLICY))
                .anySatisfy(t -> assertThat(t).startsWith("providers.gemini").contains("every model (`*`)"));
    }

    // ------------------------------------------------------------------------------------------------
    // Routing rules and fallbacks
    // ------------------------------------------------------------------------------------------------

    @Test
    void aRuleOnTheModelsNameBecomesARouteWithItsFallbacks() {
        BifrostImporter.Result r = Fixtures.importFixture("load-balancing.json");
        Route gpt4o = route(r, "gpt-4o");
        assertThat(gpt4o.providers()).containsExactly(new WeightedProvider(ProviderType.AZURE_OPENAI, 1));
        assertThat(gpt4o.pinnedModel()).isEqualTo("azure/gpt-4o-prod-deployment");
        assertThat(gpt4o.fallbacks()).containsExactly(new Fallback(ProviderType.OPENAI, "gpt-4o"),
                new Fallback(ProviderType.ANTHROPIC, "claude-sonnet-4-5"));
        // Callers who name the provider keep it.
        assertThat(route(r, "openai/gpt-4o").providers()).extracting(WeightedProvider::type)
                .containsExactly(ProviderType.OPENAI);
        assertThat(texts(r, Kind.NOT_MAPPED)).anySatisfy(t -> assertThat(t)
                .startsWith("governance.routing_rules[1]").contains("more than `model == '<name>'`"));
        // The rule decides where the bare name goes, so the note about two providers sharing it is not written.
        assertThat(texts(r, Kind.CHANGED)).noneSatisfy(t -> assertThat(t).startsWith("model gpt-4o"));
    }

    @Test
    void targetsThatSendTheSameModelShareTheTrafficAndOthersBecomeBackups() {
        BifrostImporter.Result r = importJson("""
                {"providers": {
                  "openai": {"keys": [{"name": "o", "value": "env.O", "models": ["*"], "weight": 1}]},
                  "deepseek": {"keys": [{"name": "d", "value": "env.D", "models": ["*"], "weight": 1}]},
                  "anthropic": {"keys": [{"name": "a", "value": "env.A", "models": ["*"], "weight": 1}]}
                },
                "governance": {"routing_rules": [
                  {"name": "split", "cel_expression": "model == \\"fast\\"", "priority": 2, "targets": [
                    {"provider": "openai", "model": "gpt-4o-mini", "weight": 0.7},
                    {"provider": "deepseek", "model": "gpt-4o-mini", "weight": 0.3},
                    {"provider": "anthropic", "model": "claude-haiku-4-5", "weight": 0.0}
                  ], "fallbacks": ["anthropic/", {"provider": "openai", "model": "gpt-4o"}]},
                  {"name": "first", "cel_expression": "request.model == 'fast'", "priority": 1,
                   "targets": [{"provider": "anthropic", "model": "claude-haiku-4-5", "weight": 1.0}]},
                  {"name": "team only", "scope": "team", "scope_id": "t", "cel_expression": "model == 'x'",
                   "targets": [{"provider": "openai", "weight": 1.0}]},
                  {"name": "off", "enabled": false, "cel_expression": "model == 'y'",
                   "targets": [{"provider": "openai", "weight": 1.0}]}
                ]}}""");
        // "first" has the lower priority number, so Bifrost runs it first and it wins.
        Route fast = route(r, "fast");
        assertThat(fast.providers()).containsExactly(new WeightedProvider(ProviderType.ANTHROPIC, 1));
        assertThat(texts(r, Kind.NOT_MAPPED))
                .anySatisfy(t -> assertThat(t).contains("(split)").contains("already routes `fast`"))
                .anySatisfy(t -> assertThat(t).contains("(team only)").contains("Scoped to a team"))
                .anySatisfy(t -> assertThat(t).contains("(off)").contains("enabled: false"));

        BifrostImporter.Result split = importJson("""
                {"providers": {
                  "openai": {"keys": [{"name": "o", "value": "env.O", "models": ["*"], "weight": 1}]},
                  "deepseek": {"keys": [{"name": "d", "value": "env.D", "models": ["*"], "weight": 1}]},
                  "anthropic": {"keys": [{"name": "a", "value": "env.A", "models": ["*"], "weight": 1}]}
                },
                "governance": {"routing_rules": [
                  {"name": "split", "cel_expression": "model == 'fast'", "targets": [
                    {"provider": "openai", "model": "gpt-4o-mini", "weight": 0.7},
                    {"provider": "deepseek", "model": "gpt-4o-mini", "weight": 0.3},
                    {"provider": "anthropic", "model": "claude-haiku-4-5", "weight": 0.0}
                  ], "fallbacks": ["anthropic/", {"provider": "openai", "model": "gpt-4o"}]}
                ]}}""");
        Route s = route(split, "fast");
        assertThat(s.providers()).containsExactly(new WeightedProvider(ProviderType.OPENAI, 70),
                new WeightedProvider(ProviderType.DEEPSEEK, 30));
        assertThat(s.pinnedModel()).isEqualTo("gpt-4o-mini");
        // "anthropic/" keeps the incoming model, "fast", which anthropic serves as its own name.
        assertThat(s.fallbacks()).containsExactly(new Fallback(ProviderType.ANTHROPIC, "claude-haiku-4-5"),
                new Fallback(ProviderType.ANTHROPIC, "fast"), new Fallback(ProviderType.OPENAI, "gpt-4o"));
        assertThat(texts(split, Kind.CHANGED)).anySatisfy(t -> assertThat(t).contains("traffic split became failover"));
    }

    @Test
    void aTargetOnAProviderThatIsNotServedIsDroppedAndNamed() {
        BifrostImporter.Result r = importJson("""
                {"providers": {
                  "openai": {"keys": [{"name": "o", "value": "env.O", "models": ["gpt-4o"], "weight": 1}]},
                  "vertex": {"keys": [{"name": "v", "value": "", "models": ["*"], "weight": 1}]}
                },
                "governance": {"routing_rules": [
                  {"name": "r", "cel_expression": "model == 'gpt-4o'",
                   "targets": [{"provider": "openai", "weight": 1.0}],
                   "fallbacks": ["vertex/gemini-2.5-pro", "openai/gpt-4o-mini"]}
                ]}}""");
        assertThat(route(r, "gpt-4o").fallbacks()).isEmpty();
        assertThat(texts(r, Kind.NOT_MAPPED))
                .anySatisfy(t -> assertThat(t).contains("Target `vertex` dropped").contains("not served"))
                .anySatisfy(t -> assertThat(t).contains("`openai/gpt-4o-mini` dropped"));
    }

    @Test
    void aChainLongerThanTheGatewayAllowsIsCutAndSaysSo() {
        StringBuilder fallbacks = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            fallbacks.append(i == 0 ? "" : ", ").append("\"openai/m").append(i).append("\"");
        }
        BifrostImporter.Result r = importJson("""
                {"providers": {"openai": {"keys": [{"name": "o", "value": "env.O", "models": ["*"], "weight": 1}]}},
                 "governance": {"routing_rules": [{"name": "r", "cel_expression": "model == 'm'",
                   "targets": [{"provider": "openai", "weight": 1.0}], "fallbacks": [%s]}]}}""".formatted(fallbacks));
        assertThat(route(r, "m").fallbacks()).hasSize(BifrostImporter.MAX_FALLBACKS);
        assertThat(texts(r, Kind.NOT_MAPPED)).anySatisfy(t -> assertThat(t).contains("at most 5 backups"));
    }

    @Test
    void aModelTwoProvidersServeGoesToTheFirstByItsBareName() {
        BifrostImporter.Result r = importJson("""
                {"providers": {
                  "openai": {"keys": [{"name": "o", "value": "env.O", "models": ["gpt-4o"], "weight": 1}]},
                  "azure": {"keys": [{"name": "a", "value": "env.A", "models": ["gpt-4o"], "weight": 1,
                                      "azure_key_config": {"endpoint": "https://r.openai.azure.com"}}]}
                }}""");
        assertThat(route(r, "gpt-4o").providers()).extracting(WeightedProvider::type).containsExactly(ProviderType.OPENAI);
        assertThat(route(r, "azure/gpt-4o").providers()).extracting(WeightedProvider::type)
                .containsExactly(ProviderType.AZURE_OPENAI);
        assertThat(texts(r, Kind.CHANGED)).anySatisfy(t -> assertThat(t).startsWith("model gpt-4o")
                .contains("goes to openai"));
    }

    // ------------------------------------------------------------------------------------------------
    // Virtual keys, teams and limits
    // ------------------------------------------------------------------------------------------------

    @Test
    void aVirtualKeyBecomesAWorkspaceWithItsOwnModelList() {
        BifrostImporter.Result r = Fixtures.importFixture("enterprise.json");
        assertThat(r.config().workspaces).extracting(Workspace::id)
                .containsExactly("default", "vk-team-platform", "vk-support-bot", "vk-all");
        DvaraConfig.Policy platform = policy(r, "vk-team-platform-models");
        assertThat(platform.workspace()).isEqualTo("vk-team-platform");
        assertThat(platform.dsl()).contains("\"gpt-4o\"", "\"openai/gpt-4o-mini\"", "\"claude-sonnet-4-5\"",
                "\"anthropic/claude-sonnet-4-5\"");
        assertThat(policy(r, "vk-support-bot-models").dsl()).contains("\"gpt-4o-mini\"").doesNotContain("\"gpt-4o\"");
        // allow_all_providers: the workspace may call all the gateway serves, so it needs no policy of its own.
        assertThat(r.config().policies).extracting(DvaraConfig.Policy::workspace).doesNotContain("vk-all");
        assertThat(texts(r, Kind.NOT_MAPPED))
                .anySatisfy(t -> assertThat(t).contains("(vk-retired)").contains("is_active: false"))
                .anySatisfy(t -> assertThat(t).contains("(vk-nothing)").contains("no model the gateway serves"));
    }

    @Test
    void aVirtualKeysKeyIsNeverCopied() {
        BifrostImporter.Result r = Fixtures.importFixture("enterprise.json");
        assertThat(texts(r, Kind.ACTION))
                .anySatisfy(t -> assertThat(t).contains("(vk-team-platform)").contains("$VK_PLATFORM_TEAM")
                        .contains("--hash-key -"))
                .anySatisfy(t -> assertThat(t).contains("(vk-support-bot)").contains("literal").contains("rotate"))
                .anySatisfy(t -> assertThat(t).contains("(vk-all)").contains("--generate-key"));
        assertThat(everything(r)).doesNotContain("LITERAL-VK-SECRET-0005");
    }

    @Test
    void keyIdsThatLeaveOutTheServedKeyKeepTheWorkspaceOffThatProvider() {
        BifrostImporter.Result r = importJson("""
                {"providers": {
                  "openai": {"keys": [{"id": "k1", "name": "first", "value": "env.O", "models": ["gpt-4o"], "weight": 1},
                                      {"id": "k2", "name": "second", "value": "env.O2", "models": ["gpt-4o"], "weight": 1}]},
                  "anthropic": {"keys": [{"name": "a", "value": "env.A", "models": ["claude-haiku-4-5"], "weight": 1}]}
                },
                "governance": {"virtual_keys": [{"id": "vk1", "name": "one", "provider_configs": [
                  {"provider": "openai", "allowed_models": ["*"], "key_ids": ["k2"]},
                  {"provider": "anthropic", "allowed_models": ["*"], "key_ids": ["*"]}
                ]}]}}""");
        String dsl = policy(r, "vk1-models").dsl();
        assertThat(dsl).contains("\"claude-haiku-4-5\"").doesNotContain("\"gpt-4o\"");
        assertThat(texts(r, Kind.CHANGED)).anySatisfy(t -> assertThat(t).contains("key_ids").contains("`first`"));
    }

    @Test
    void aVirtualKeyThatMayCallOnlySomeModelsOfAWildcardProviderGetsThoseRoutes() {
        BifrostImporter.Result r = importJson("""
                {"providers": {"openai": {"keys": [{"name": "o", "value": "env.O", "models": ["*"], "weight": 1}]}},
                 "governance": {"virtual_keys": [{"id": "vk1", "provider_configs": [
                   {"provider": "openai", "allowed_models": ["openai/gpt-4o-mini"], "key_ids": ["*"]}]}]}}""");
        assertThat(route(r, "openai/gpt-4o-mini").pinnedModel()).isEqualTo("gpt-4o-mini");
        assertThat(policy(r, "vk1-models").dsl()).contains("\"gpt-4o-mini\"", "\"openai/gpt-4o-mini\"");
        assertThat(r.config().policies).extracting(DvaraConfig.Policy::id).doesNotContain(BifrostImporter.SERVED_POLICY);
    }

    @Test
    void aVirtualKeyWhoseModelsCannotBeListedIsLeftOutRatherThanLeftOpen() {
        BifrostImporter.Result r = importJson("""
                {"providers": {"openai": {"keys": [{"name": "o", "value": "env.O", "models": ["*"], "weight": 1}]}},
                 "governance": {"virtual_keys": [{"id": "vk1", "provider_configs": [
                   {"provider": "openai", "allowed_models": ["regex:^gpt-4o.*"], "key_ids": ["*"]}]}]}}""");
        assertThat(r.config().workspaces).extracting(Workspace::id).containsExactly("default");
        assertThat(texts(r, Kind.ACTION)).anySatisfy(t -> assertThat(t).contains("(vk1)").contains("Not carried over"));
    }

    @Test
    void rateLimitsBecomePerMinuteWorkspaceLimitsAndTheLowerOneIsKept() {
        BifrostImporter.Result r = Fixtures.importFixture("enterprise.json");
        Workspace ws = r.config().workspaces.stream().filter(w -> w.id().equals("vk-team-platform")).findFirst()
                .orElseThrow();
        // Its own 6000 an hour is 100 a minute; its team's 60 a minute is lower, so it is kept.
        assertThat(ws.metadata()).containsEntry("rate-limit.requests-per-minute", 60)
                .containsEntry("rate-limit.tokens-per-minute", 16_666);
        assertThat(texts(r, Kind.CHANGED))
                .anySatisfy(t -> assertThat(t).contains("6000 requests per 1h").contains("per-minute share"))
                .anySatisfy(t -> assertThat(t).contains("team team-ml").contains("shared it"));
        assertThat(texts(r, Kind.NOT_MAPPED)).anySatisfy(t -> assertThat(t).startsWith("governance.rate_limits rl-unused"))
                .anySatisfy(t -> assertThat(t).startsWith("governance.budgets"));
    }

    @ParameterizedTest
    @CsvSource({"30s, 30", "1m, 60", "1h, 3600", "1d, 86400", "1w, 604800", "1M, 2592000", "1Y, 31536000"})
    void bifrostWindowLengthsAreRead(String duration, long seconds) {
        assertThat(BifrostImporter.durationSeconds(duration)).isEqualTo(seconds);
    }

    @Test
    void aVirtualKeyIdThatCannotBeAWorkspaceIdIsReported() {
        BifrostImporter.Result r = importJson("""
                {"providers": {"openai": {"keys": [{"name": "o", "value": "env.O", "models": ["*"], "weight": 1}]}},
                 "governance": {"virtual_keys": [{"id": "has spaces", "allow_all_providers": true},
                                                 {"id": "default", "allow_all_providers": true}]}}""");
        assertThat(r.config().workspaces).hasSize(1);
        assertThat(texts(r, Kind.NOT_MAPPED)).filteredOn(t -> t.contains("cannot be a workspace id")).hasSize(2);
    }

    // ------------------------------------------------------------------------------------------------
    // Settings, plugins and guardrails
    // ------------------------------------------------------------------------------------------------

    @Test
    void networkSettingsBecomePerProviderResilienceProperties() {
        BifrostImporter.Result r = Fixtures.importFixture("load-balancing.json");
        assertThat(r.config().properties)
                .containsEntry("dvara.llm-gateway.resilience.providers.openai.timeout.chat-timeout-ms", "120000")
                .containsEntry("dvara.llm-gateway.resilience.providers.openai.retry.max-attempts", "4")
                .containsEntry("dvara.llm-gateway.resilience.providers.openai.retry.initial-backoff-ms", "500")
                .containsEntry("dvara.llm-gateway.resilience.providers.openai.retry.max-backoff-ms", "5000")
                .containsEntry("dvara.llm-gateway.resilience.providers.anthropic.timeout.chat-timeout-ms", "180000");
        assertThat(texts(r, Kind.CHANGED))
                .anySatisfy(t -> assertThat(t).contains("providers.azure").contains("No timeout set"))
                .anySatisfy(t -> assertThat(t).contains("concurrency_and_buffer_size"));
    }

    @Test
    void pluginsMapToWhatTheGatewayHas() {
        BifrostImporter.Result r = Fixtures.importFixture("enterprise.json");
        assertThat(texts(r, Kind.NOT_MAPPED))
                .anySatisfy(t -> assertThat(t).contains("(semantic_cache)").contains("no response cache"))
                .anySatisfy(t -> assertThat(t).contains("(maxim)").contains("No maxim integration"))
                .anySatisfy(t -> assertThat(t).contains("(datadog)").contains("Not enabled"));
        assertThat(texts(r, Kind.CHANGED)).anySatisfy(t -> assertThat(t).contains("(otel)")
                .contains("management.otlp.tracing.endpoint"));
    }

    @Test
    void piiAndPromptInjectionGuardrailsSetTheWorkspaceActions() {
        BifrostImporter.Result r = Fixtures.importFixture("enterprise.json");
        for (Workspace w : r.config().workspaces) {
            assertThat(w.metadata()).as(w.id()).containsEntry("pii.action", "REDACT")
                    .containsEntry("guardrail.action", "BLOCK");
        }
        assertThat(texts(r, Kind.NOT_MAPPED)).anySatisfy(t -> assertThat(t).contains("`grayswan` is not called"));
    }

    @Test
    void servingWithoutAVirtualKeyIsNamedAsAChange() {
        BifrostImporter.Result open = Fixtures.importFixture("quickstart.json");
        assertThat(texts(open, Kind.CHANGED)).anySatisfy(t -> assertThat(t).startsWith("client.enforce_auth_on_inference"));
        BifrostImporter.Result enforced = Fixtures.importFixture("enterprise.json");
        assertThat(texts(enforced, Kind.MAPPED)).anySatisfy(t -> assertThat(t).startsWith("client.enforce_auth_on_inference"));
    }

    @Test
    void everyUnknownKeyIsNamedInTheReport() {
        BifrostImporter.Result r = importJson("""
                {"providers": {"openai": {"keys": [{"name": "o", "value": "env.O", "models": ["*"], "weight": 1,
                                                    "future_key_field": 1}],
                                          "network_config": {"future_network_field": 1},
                                          "future_provider_field": true}},
                 "client": {"future_client_field": 1},
                 "governance": {"future_governance_section": []},
                 "surprise_section": {}}""");
        List<String> notMapped = texts(r, Kind.NOT_MAPPED);
        assertThat(notMapped).anySatisfy(t -> assertThat(t).contains("future_key_field"));
        assertThat(notMapped).anySatisfy(t -> assertThat(t).startsWith("providers.openai.network_config.future_network_field"));
        assertThat(notMapped).anySatisfy(t -> assertThat(t).startsWith("providers.openai.future_provider_field"));
        assertThat(notMapped).anySatisfy(t -> assertThat(t).startsWith("client.future_client_field"));
        assertThat(notMapped).anySatisfy(t -> assertThat(t).startsWith("governance.future_governance_section"));
        assertThat(notMapped).anySatisfy(t -> assertThat(t).startsWith("surprise_section"));
    }

    @Test
    void aKeyThatAppearsTwiceIsReportedAndTheLastOneWins() {
        BifrostImporter.Result r = importJson("""
                {"providers": {"openai": {"keys": [{"name": "o", "value": "env.FIRST", "value": "env.SECOND",
                                                    "models": ["*"], "weight": 1}]}}}""");
        assertThat(r.config().providers.get(ProviderType.OPENAI).apiKey()).isEqualTo(new SecretValue("SECOND"));
        assertThat(texts(r, Kind.CHANGED)).anySatisfy(t -> assertThat(t).contains("value").contains("twice"));
    }

    @Test
    void theReportAlwaysSaysKeysMustBeMintedAndWhatTheGatewayAdds() {
        BifrostImporter.Result r = Fixtures.importFixture("quickstart.json");
        assertThat(r.report().of(Kind.ACTION)).extracting(Finding::where).contains("api_keys", "environment");
        assertThat(r.report().of(Kind.ADDED)).isNotEmpty();
        assertThat(r.report().of(Kind.NOT_MAPPED)).extracting(Finding::where).contains("(Bifrost config store)");
    }

    @Test
    void inputThatIsNotABifrostConfigIsRefusedWithTheReason() {
        assertThatThrownBy(() -> importJson("[1, 2]")).hasMessageContaining("not an object");
        assertThatThrownBy(() -> importJson("{\"client\": {}}")).hasMessageContaining("no providers");
        assertThatThrownBy(() -> importJson("{\"providers\": []}")).hasMessageContaining("providers is not an object");
        assertThatThrownBy(() -> importJson("{\"providers\": {")).hasMessageContaining("not valid JSON");
    }

    @Test
    void quotingEscapesTheDollarSoTheGatewayNeverReadsAValueAsAVariable() {
        assertThat(GatewayYamlWriter.quote("a${HOME}b\"c")).isEqualTo("\"a\\u0024{HOME}b\\\"c\"");
    }
}
