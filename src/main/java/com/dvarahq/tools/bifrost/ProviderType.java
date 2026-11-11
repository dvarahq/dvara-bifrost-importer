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

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The open gateway's provider types, and how a Bifrost provider maps onto each.
 *
 * <p>The gateway names some models with a prefix of its own ({@code azure/}, {@code bedrock/},
 * {@code groq/}, {@code ollama/}) and others bare ({@code gpt-4o}, {@code claude-sonnet-4-5}).
 * {@link #modelPrefix} is that prefix.
 *
 * <p>Bifrost's base URLs stop before the API version (it adds {@code /v1/chat/completions}); the gateway's
 * include it for the OpenAI-style providers. {@link #baseUrlSuffix} is what is added.
 */
enum ProviderType {
    OPENAI("openai", "", "OPENAI_API_KEY", true, "/v1", "https://api.openai.com"),
    AZURE_OPENAI("azure-openai", "azure/", "AZURE_API_KEY", true, "/openai", null),
    ANTHROPIC("anthropic", "", "ANTHROPIC_API_KEY", true, "", "https://api.anthropic.com"),
    GEMINI("gemini", "", "GEMINI_API_KEY", true, "", "https://generativelanguage.googleapis.com/v1beta"),
    MISTRAL("mistral", "", "MISTRAL_API_KEY", true, "/v1", "https://api.mistral.ai"),
    COHERE("cohere", "", "COHERE_API_KEY", true, "/v2", "https://api.cohere.ai"),
    GROQ("groq", "groq/", "GROQ_API_KEY", true, "/v1", "https://api.groq.com/openai"),
    DEEPSEEK("deepseek", "", "DEEPSEEK_API_KEY", true, "", "https://api.deepseek.com"),
    GROK("grok", "", "XAI_API_KEY", true, "/v1", "https://api.x.ai"),
    OLLAMA("ollama", "ollama/", null, false, "", null),
    BEDROCK("bedrock", "bedrock/", null, false, null, null);

    /** The value of {@code type:} in gateway.yaml, which is also the name a route uses. */
    final String yamlType;
    /** The prefix the gateway keeps on this provider's model names. */
    final String modelPrefix;
    /** The variable the output names when the Bifrost file holds the key as a literal, or no key at all. */
    final String defaultKeyEnv;
    /** Whether the provider takes an API key. Ollama and Bedrock do not. */
    final boolean takesApiKey;
    /** Added to a Bifrost base URL to make the gateway's; null when the gateway reads no base URL (Bedrock). */
    final String baseUrlSuffix;
    /** Bifrost's own default base URL: setting it changes nothing, so it is not written. */
    final String bifrostDefaultBaseUrl;

    ProviderType(String yamlType, String modelPrefix, String defaultKeyEnv, boolean takesApiKey,
                 String baseUrlSuffix, String bifrostDefaultBaseUrl) {
        this.yamlType = yamlType;
        this.modelPrefix = modelPrefix;
        this.defaultKeyEnv = defaultKeyEnv;
        this.takesApiKey = takesApiKey;
        this.baseUrlSuffix = baseUrlSuffix;
        this.bifrostDefaultBaseUrl = bifrostDefaultBaseUrl;
    }

    /** Bifrost provider names the gateway has a provider for. */
    private static final Map<String, ProviderType> BY_BIFROST_NAME = Map.ofEntries(
            Map.entry("openai", OPENAI),
            Map.entry("azure", AZURE_OPENAI),
            Map.entry("anthropic", ANTHROPIC),
            Map.entry("gemini", GEMINI),
            Map.entry("mistral", MISTRAL),
            Map.entry("cohere", COHERE),
            Map.entry("groq", GROQ),
            Map.entry("deepseek", DEEPSEEK),
            Map.entry("xai", GROK),
            Map.entry("ollama", OLLAMA),
            Map.entry("bedrock", BEDROCK),
            // Both serve the OpenAI API from a server of their own, named per key.
            Map.entry("vllm", OPENAI),
            Map.entry("sgl", OPENAI));

    /** Bifrost providers that are an OpenAI-compatible server rather than OpenAI itself. */
    static final Set<String> OPENAI_COMPATIBLE = Set.of("vllm", "sgl");

    /** Reasons for providers Bifrost has and the open gateway does not, where a generic "no provider" would mislead. */
    static final Map<String, String> UNSUPPORTED_REASONS = Map.of(
            "vertex", "The gateway calls Gemini through Google AI Studio (gemini), not Vertex AI.",
            "bedrock_mantle", "The gateway's Bedrock provider calls the Bedrock runtime's Converse API, not Bedrock"
                    + " Mantle.",
            "sagemaker", "The gateway does not call SageMaker endpoints.",
            "databricks", "The gateway does not call Databricks model serving.");

    static Optional<ProviderType> forBifrostName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_BIFROST_NAME.get(name.toLowerCase(Locale.ROOT)));
    }

    /** The model name the gateway uses for a model id this provider is sent. */
    String dvaraModel(String bifrostModel) {
        return modelPrefix + bifrostModel;
    }
}
