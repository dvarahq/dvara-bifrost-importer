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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the importer writes into {@code gateway.yaml}. Field names follow the file, not Java style, so the
 * writer and the reader of this class see the same thing.
 *
 * <p>Every secret field holds a {@code ${NAME}} reference, never a value. {@link SecretValue} is the only
 * way to make one, which keeps that true by construction.
 */
final class DvaraConfig {

    /** One provider. The open gateway has one endpoint per provider type, so the type is the key. */
    record Provider(ProviderType type, SecretValue apiKey, UrlValue baseUrl,
                    SecretValue accessKey, SecretValue secretKey, UrlValue region) {}

    /** A provider a route sends to, with its share of the traffic. */
    record WeightedProvider(ProviderType type, int weight) {}

    /** A backup the route tries when its provider fails: a provider and the model to ask it for. */
    record Fallback(ProviderType type, String model) {}

    /**
     * A route. {@code model} is what the caller sends; {@code pinnedModel}, when set, is what the provider
     * is sent instead. That pair is how a Bifrost {@code provider/model} name or key alias maps.
     */
    record Route(String id, String model, String pinnedModel, List<WeightedProvider> providers,
                 List<Fallback> fallbacks) {}

    /** A per-model context window or output limit. */
    record ModelLimit(String model, ProviderType provider, Integer contextTokens, Integer outputTokens) {}

    /** A workspace and its settings. */
    record Workspace(String id, String name, Map<String, Object> metadata) {}

    /** A policy in the policy DSL. {@code workspace} null means every workspace. */
    record Policy(String id, String workspace, String dsl) {}

    final Map<ProviderType, Provider> providers = new LinkedHashMap<>();
    final List<Route> routes = new ArrayList<>();
    final List<ModelLimit> modelLimits = new ArrayList<>();
    final List<Workspace> workspaces = new ArrayList<>();
    final List<Policy> policies = new ArrayList<>();

    /**
     * Spring properties for settings {@code gateway.yaml} cannot carry, such as retries and timeouts. They
     * go in a separate file the operator passes with {@code --spring.config.additional-location}.
     */
    final Map<String, String> properties = new LinkedHashMap<>();
}
