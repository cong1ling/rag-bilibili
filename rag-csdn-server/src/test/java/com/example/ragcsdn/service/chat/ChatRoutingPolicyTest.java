package com.example.ragcsdn.service.chat;

import com.example.ragcsdn.config.ChatOptimizationProperties;
import com.example.ragcsdn.entity.Session;
import com.example.ragcsdn.enums.SessionType;
import com.example.ragcsdn.service.impl.QueryComplexityAnalyzer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatRoutingPolicyTest {

    @Test
    void determineTopK_shouldUseComplexBucketForBroadQuery() {
        ChatOptimizationProperties properties = properties();
        ChatRoutingPolicy policy = new ChatRoutingPolicy(properties, new QueryComplexityAnalyzer(properties));
        Session session = new Session();
        session.setSessionType(SessionType.ALL_ARTICLES.getCode());

        int topK = policy.determineTopK(session, "请系统比较Spring AI、RAG和向量检索方案", 5);

        assertThat(topK).isEqualTo(8);
    }

    @Test
    void shouldUseLlmFallback_shouldSkipFallbackForHighConfidenceRuleMode() {
        ChatOptimizationProperties properties = properties();
        properties.setRoutingObservationOnly(false);
        ChatRoutingPolicy policy = new ChatRoutingPolicy(properties, new QueryComplexityAnalyzer(properties));

        boolean usedFallback = policy.shouldUseLlmFallback(0.88d);

        assertThat(usedFallback).isFalse();
    }

    @Test
    void shouldUseLlmFallback_shouldUseFallbackForLowConfidenceRuleMode() {
        ChatOptimizationProperties properties = properties();
        properties.setRoutingObservationOnly(false);
        ChatRoutingPolicy policy = new ChatRoutingPolicy(properties, new QueryComplexityAnalyzer(properties));

        boolean usedFallback = policy.shouldUseLlmFallback(0.41d);

        assertThat(usedFallback).isTrue();
    }

    @Test
    void routeGuards_shouldRequireMatchingIntentAndThreshold() {
        ChatOptimizationProperties properties = properties();
        properties.setRoutingObservationOnly(false);
        properties.setHydeTriggerThreshold(0.72d);
        properties.setDecompositionTriggerThreshold(0.68d);
        ChatRoutingPolicy policy = new ChatRoutingPolicy(properties, new QueryComplexityAnalyzer(properties));

        assertThat(policy.shouldUseHyde("AMBIGUOUS", 0.80d)).isTrue();
        assertThat(policy.shouldUseHyde("DIRECT", 0.80d)).isFalse();
        assertThat(policy.shouldUseDecomposition("BROAD", 0.78d)).isTrue();
        assertThat(policy.shouldUseDecomposition("DIRECT", 0.78d)).isFalse();
    }

    private ChatOptimizationProperties properties() {
        ChatOptimizationProperties properties = new ChatOptimizationProperties();
        properties.setRuleRoutingEnabled(true);
        properties.setRoutingObservationOnly(true);
        properties.setRuleRoutingLlmFallbackEnabled(true);
        properties.setAmbiguityThreshold(0.62d);
        properties.setBreadthThreshold(0.58d);
        properties.setComplexityThreshold(0.55d);
        properties.setLlmFallbackConfidenceThreshold(0.52d);
        properties.setSimpleTopK(3);
        properties.setNormalTopK(5);
        properties.setComplexTopK(8);
        return properties;
    }
}
