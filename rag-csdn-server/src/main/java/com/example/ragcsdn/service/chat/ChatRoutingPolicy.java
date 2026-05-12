package com.example.ragcsdn.service.chat;

import com.example.ragcsdn.config.ChatOptimizationProperties;
import com.example.ragcsdn.entity.Session;
import com.example.ragcsdn.enums.SessionType;
import com.example.ragcsdn.service.impl.QueryComplexityAnalyzer;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ChatRoutingPolicy {

    private final ChatOptimizationProperties chatOptimizationProperties;
    private final QueryComplexityAnalyzer queryComplexityAnalyzer;
    private final RetrievalPipelineService retrievalPipelineService;
    private final ChatMetadataHelper chatMetadataHelper;

    public ChatRoutingPolicy(ChatOptimizationProperties chatOptimizationProperties,
                             QueryComplexityAnalyzer queryComplexityAnalyzer) {
        this(chatOptimizationProperties, queryComplexityAnalyzer, null, new ChatMetadataHelper());
    }

    public ChatRoutingPolicy(ChatOptimizationProperties chatOptimizationProperties,
                             QueryComplexityAnalyzer queryComplexityAnalyzer,
                             RetrievalPipelineService retrievalPipelineService,
                             ChatMetadataHelper chatMetadataHelper) {
        this.chatOptimizationProperties = chatOptimizationProperties;
        this.queryComplexityAnalyzer = queryComplexityAnalyzer;
        this.retrievalPipelineService = retrievalPipelineService;
        this.chatMetadataHelper = chatMetadataHelper;
    }

    public int determineTopK(Session session, String query, int defaultTopK) {
        if (!isDynamicTopKEnabled()) {
            return defaultTopK;
        }

        QueryComplexityAnalyzer.Analysis analysis = queryComplexityAnalyzer.analyze(query, false);
        if (analysis.complexityScore() >= getComplexityThreshold()
                || analysis.suggestedIntent() != QueryComplexityAnalyzer.QueryIntentHint.DIRECT
                || (session != null && SessionType.isAllArticles(session.getSessionType()))) {
            return getComplexTopK(defaultTopK);
        }

        List<String> keywords = retrievalPipelineService == null
                ? List.of()
                : retrievalPipelineService.extractKeywords(query);
        if (isSimpleFactQuery(chatMetadataHelper.normalizeForMatch(query), keywords)
                && analysis.complexityScore() < getComplexityThreshold()) {
            return getSimpleTopK(defaultTopK);
        }
        return getNormalTopK(defaultTopK);
    }

    public boolean shouldUseLlmFallback(double decisionConfidence) {
        if (!isRuleRoutingEnabled() || isRoutingObservationOnly()) {
            return true;
        }
        if (!isRuleRoutingLlmFallbackEnabled()) {
            return false;
        }
        return decisionConfidence < getLlmFallbackConfidenceThreshold();
    }

    public boolean shouldUseHyde(String intentName, double ambiguityScore) {
        return isHydeEnabled()
                && "AMBIGUOUS".equals(intentName)
                && ambiguityScore >= getHydeTriggerThreshold();
    }

    public boolean shouldUseDecomposition(String intentName, double breadthScore) {
        return isDecompositionEnabled()
                && "BROAD".equals(intentName)
                && breadthScore >= getDecompositionTriggerThreshold();
    }

    private boolean isSimpleFactQuery(String normalizedQuery, List<String> keywords) {
        if (normalizedQuery.isBlank()) {
            return false;
        }

        int atomicKeywordCount = (int) keywords.stream()
                .map(chatMetadataHelper::normalizeForMatch)
                .filter(keyword -> !keyword.isBlank())
                .filter(keyword -> !keyword.equals(normalizedQuery))
                .count();
        boolean shortQuery = normalizedQuery.length() <= 24;
        boolean smallKeywordSet = atomicKeywordCount <= 3;
        boolean hasFactCue = normalizedQuery.matches(".*(多少|几|谁|哪(个|位|一)|什么|何时|什么时候|多大|多久|默认端口|default|port|where|when|who|what).*");
        boolean hasComplexCue = normalizedQuery.matches(".*(为什么|原理|流程|步骤|区别|对比|比较|优缺点|总结|分析|实现|怎么|如何).*");
        return shortQuery && smallKeywordSet && hasFactCue && !hasComplexCue;
    }

    private boolean isRuleRoutingEnabled() {
        return chatOptimizationProperties == null
                || !Boolean.FALSE.equals(chatOptimizationProperties.getRuleRoutingEnabled());
    }

    private boolean isRoutingObservationOnly() {
        return chatOptimizationProperties != null
                && Boolean.TRUE.equals(chatOptimizationProperties.getRoutingObservationOnly());
    }

    private boolean isRuleRoutingLlmFallbackEnabled() {
        return chatOptimizationProperties == null
                || !Boolean.FALSE.equals(chatOptimizationProperties.getRuleRoutingLlmFallbackEnabled());
    }

    private boolean isHydeEnabled() {
        return chatOptimizationProperties == null || !Boolean.FALSE.equals(chatOptimizationProperties.getHydeEnabled());
    }

    private boolean isDecompositionEnabled() {
        return chatOptimizationProperties == null || !Boolean.FALSE.equals(chatOptimizationProperties.getDecompositionEnabled());
    }

    private boolean isDynamicTopKEnabled() {
        return chatOptimizationProperties == null || !Boolean.FALSE.equals(chatOptimizationProperties.getDynamicTopKEnabled());
    }

    private double getComplexityThreshold() {
        if (chatOptimizationProperties == null || chatOptimizationProperties.getComplexityThreshold() == null) {
            return 0.55d;
        }
        return chatOptimizationProperties.getComplexityThreshold();
    }

    private double getLlmFallbackConfidenceThreshold() {
        if (chatOptimizationProperties == null || chatOptimizationProperties.getLlmFallbackConfidenceThreshold() == null) {
            return 0.52d;
        }
        return chatOptimizationProperties.getLlmFallbackConfidenceThreshold();
    }

    private double getHydeTriggerThreshold() {
        if (chatOptimizationProperties == null || chatOptimizationProperties.getHydeTriggerThreshold() == null) {
            return 0.72d;
        }
        return chatOptimizationProperties.getHydeTriggerThreshold();
    }

    private double getDecompositionTriggerThreshold() {
        if (chatOptimizationProperties == null || chatOptimizationProperties.getDecompositionTriggerThreshold() == null) {
            return 0.68d;
        }
        return chatOptimizationProperties.getDecompositionTriggerThreshold();
    }

    private int getSimpleTopK(int fallback) {
        if (chatOptimizationProperties == null || chatOptimizationProperties.getSimpleTopK() == null
                || chatOptimizationProperties.getSimpleTopK() <= 0) {
            return Math.min(fallback, 3);
        }
        return chatOptimizationProperties.getSimpleTopK();
    }

    private int getNormalTopK(int fallback) {
        if (chatOptimizationProperties == null || chatOptimizationProperties.getNormalTopK() == null
                || chatOptimizationProperties.getNormalTopK() <= 0) {
            return fallback;
        }
        return chatOptimizationProperties.getNormalTopK();
    }

    private int getComplexTopK(int fallback) {
        if (chatOptimizationProperties == null || chatOptimizationProperties.getComplexTopK() == null
                || chatOptimizationProperties.getComplexTopK() <= 0) {
            return Math.max(fallback, 8);
        }
        return chatOptimizationProperties.getComplexTopK();
    }
}
