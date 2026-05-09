package com.example.ragcsdn.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag.chat")
public class ChatOptimizationProperties {
    private Integer maxHistory = 10;
    private Boolean queryRewriteEnabled = true;
    private Boolean queryUnderstandingEnabled = true;
    // Rule-first routing expectations:
    // 1. routing can be disabled globally
    // 2. observation mode can run without changing behavior
    // 3. LLM fallback can be switched off independently
    // 4. HyDE/decomposition thresholds are tuned separately from intent thresholds
    private Boolean ruleRoutingEnabled = true;
    private Boolean routingObservationOnly = true;
    private Boolean ruleRoutingLlmFallbackEnabled = true;
    private Double ambiguityThreshold = 0.62d;
    private Double breadthThreshold = 0.58d;
    private Double complexityThreshold = 0.55d;
    private Double llmFallbackConfidenceThreshold = 0.52d;
    private Double hydeTriggerThreshold = 0.72d;
    private Double decompositionTriggerThreshold = 0.68d;
    private Boolean hydeEnabled = true;
    private Boolean decompositionEnabled = true;
    private Integer maxDecomposedQueries = 4;
    private Boolean hybridSearchEnabled = true;
    private Integer keywordTopK = 8;
    private Boolean rerankEnabled = true;
    private Integer rerankCandidateTopK = 20;
    private Boolean modelRerankEnabled = false;
    private Integer modelRerankTopK = 8;
    private Boolean dynamicTopKEnabled = true;
    private Integer simpleTopK = 3;
    private Integer normalTopK = 5;
    private Integer complexTopK = 8;
    private Boolean summaryEnabled = true;
    private Integer summaryTriggerMessages = 10;
    private Integer summaryRecentMessages = 6;
    private Integer summaryMaxLength = 150;
    private Boolean confidenceAwareEnabled = true;

    public Integer getMaxHistory() {
        return maxHistory;
    }

    public void setMaxHistory(Integer maxHistory) {
        this.maxHistory = maxHistory;
    }

    public Boolean getQueryRewriteEnabled() {
        return queryRewriteEnabled;
    }

    public void setQueryRewriteEnabled(Boolean queryRewriteEnabled) {
        this.queryRewriteEnabled = queryRewriteEnabled;
    }

    public Boolean getQueryUnderstandingEnabled() {
        return queryUnderstandingEnabled;
    }

    public void setQueryUnderstandingEnabled(Boolean queryUnderstandingEnabled) {
        this.queryUnderstandingEnabled = queryUnderstandingEnabled;
    }

    public Boolean getRuleRoutingEnabled() {
        return ruleRoutingEnabled;
    }

    public void setRuleRoutingEnabled(Boolean ruleRoutingEnabled) {
        this.ruleRoutingEnabled = ruleRoutingEnabled;
    }

    public Boolean getRoutingObservationOnly() {
        return routingObservationOnly;
    }

    public void setRoutingObservationOnly(Boolean routingObservationOnly) {
        this.routingObservationOnly = routingObservationOnly;
    }

    public Boolean getRuleRoutingLlmFallbackEnabled() {
        return ruleRoutingLlmFallbackEnabled;
    }

    public void setRuleRoutingLlmFallbackEnabled(Boolean ruleRoutingLlmFallbackEnabled) {
        this.ruleRoutingLlmFallbackEnabled = ruleRoutingLlmFallbackEnabled;
    }

    public Double getAmbiguityThreshold() {
        return ambiguityThreshold;
    }

    public void setAmbiguityThreshold(Double ambiguityThreshold) {
        this.ambiguityThreshold = ambiguityThreshold;
    }

    public Double getBreadthThreshold() {
        return breadthThreshold;
    }

    public void setBreadthThreshold(Double breadthThreshold) {
        this.breadthThreshold = breadthThreshold;
    }

    public Double getComplexityThreshold() {
        return complexityThreshold;
    }

    public void setComplexityThreshold(Double complexityThreshold) {
        this.complexityThreshold = complexityThreshold;
    }

    public Double getLlmFallbackConfidenceThreshold() {
        return llmFallbackConfidenceThreshold;
    }

    public void setLlmFallbackConfidenceThreshold(Double llmFallbackConfidenceThreshold) {
        this.llmFallbackConfidenceThreshold = llmFallbackConfidenceThreshold;
    }

    public Double getHydeTriggerThreshold() {
        return hydeTriggerThreshold;
    }

    public void setHydeTriggerThreshold(Double hydeTriggerThreshold) {
        this.hydeTriggerThreshold = hydeTriggerThreshold;
    }

    public Double getDecompositionTriggerThreshold() {
        return decompositionTriggerThreshold;
    }

    public void setDecompositionTriggerThreshold(Double decompositionTriggerThreshold) {
        this.decompositionTriggerThreshold = decompositionTriggerThreshold;
    }

    public Boolean getHydeEnabled() {
        return hydeEnabled;
    }

    public void setHydeEnabled(Boolean hydeEnabled) {
        this.hydeEnabled = hydeEnabled;
    }

    public Boolean getDecompositionEnabled() {
        return decompositionEnabled;
    }

    public void setDecompositionEnabled(Boolean decompositionEnabled) {
        this.decompositionEnabled = decompositionEnabled;
    }

    public Integer getMaxDecomposedQueries() {
        return maxDecomposedQueries;
    }

    public void setMaxDecomposedQueries(Integer maxDecomposedQueries) {
        this.maxDecomposedQueries = maxDecomposedQueries;
    }

    public Boolean getHybridSearchEnabled() {
        return hybridSearchEnabled;
    }

    public void setHybridSearchEnabled(Boolean hybridSearchEnabled) {
        this.hybridSearchEnabled = hybridSearchEnabled;
    }

    public Integer getKeywordTopK() {
        return keywordTopK;
    }

    public void setKeywordTopK(Integer keywordTopK) {
        this.keywordTopK = keywordTopK;
    }

    public Boolean getRerankEnabled() {
        return rerankEnabled;
    }

    public void setRerankEnabled(Boolean rerankEnabled) {
        this.rerankEnabled = rerankEnabled;
    }

    public Integer getRerankCandidateTopK() {
        return rerankCandidateTopK;
    }

    public void setRerankCandidateTopK(Integer rerankCandidateTopK) {
        this.rerankCandidateTopK = rerankCandidateTopK;
    }

    public Boolean getModelRerankEnabled() {
        return modelRerankEnabled;
    }

    public void setModelRerankEnabled(Boolean modelRerankEnabled) {
        this.modelRerankEnabled = modelRerankEnabled;
    }

    public Integer getModelRerankTopK() {
        return modelRerankTopK;
    }

    public void setModelRerankTopK(Integer modelRerankTopK) {
        this.modelRerankTopK = modelRerankTopK;
    }

    public Boolean getDynamicTopKEnabled() {
        return dynamicTopKEnabled;
    }

    public void setDynamicTopKEnabled(Boolean dynamicTopKEnabled) {
        this.dynamicTopKEnabled = dynamicTopKEnabled;
    }

    public Integer getSimpleTopK() {
        return simpleTopK;
    }

    public void setSimpleTopK(Integer simpleTopK) {
        this.simpleTopK = simpleTopK;
    }

    public Integer getNormalTopK() {
        return normalTopK;
    }

    public void setNormalTopK(Integer normalTopK) {
        this.normalTopK = normalTopK;
    }

    public Integer getComplexTopK() {
        return complexTopK;
    }

    public void setComplexTopK(Integer complexTopK) {
        this.complexTopK = complexTopK;
    }

    public Boolean getSummaryEnabled() {
        return summaryEnabled;
    }

    public void setSummaryEnabled(Boolean summaryEnabled) {
        this.summaryEnabled = summaryEnabled;
    }

    public Integer getSummaryTriggerMessages() {
        return summaryTriggerMessages;
    }

    public void setSummaryTriggerMessages(Integer summaryTriggerMessages) {
        this.summaryTriggerMessages = summaryTriggerMessages;
    }

    public Integer getSummaryRecentMessages() {
        return summaryRecentMessages;
    }

    public void setSummaryRecentMessages(Integer summaryRecentMessages) {
        this.summaryRecentMessages = summaryRecentMessages;
    }

    public Integer getSummaryMaxLength() {
        return summaryMaxLength;
    }

    public void setSummaryMaxLength(Integer summaryMaxLength) {
        this.summaryMaxLength = summaryMaxLength;
    }

    public Boolean getConfidenceAwareEnabled() {
        return confidenceAwareEnabled;
    }

    public void setConfidenceAwareEnabled(Boolean confidenceAwareEnabled) {
        this.confidenceAwareEnabled = confidenceAwareEnabled;
    }
}

