package com.example.ragcsdn.service.impl;

import com.example.ragcsdn.config.ChatOptimizationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

@Component
public class QueryComplexityAnalyzer {

    public enum QueryIntentHint {
        DIRECT,
        AMBIGUOUS,
        BROAD
    }

    public record Analysis(
            double ambiguityScore,
            double breadthScore,
            double complexityScore,
            double decisionConfidence,
            QueryIntentHint suggestedIntent
    ) {
    }

    private final ChatOptimizationProperties properties;

    public QueryComplexityAnalyzer(ChatOptimizationProperties properties) {
        this.properties = properties;
    }

    public Analysis analyze(String query, boolean conversationDependent) {
        String normalized = normalize(query);
        if (normalized.isBlank()) {
            return new Analysis(0.0d, 0.0d, 0.0d, 1.0d, QueryIntentHint.DIRECT);
        }

        int keywordCount = countKeywords(normalized);
        boolean shortQuery = normalized.length() <= 18;
        boolean hasPronounCue = containsAny(normalized, "它", "这个", "那个", "这部分", "前面", "this", "that", "it");
        boolean hasFactCue = containsAny(normalized, "多少", "几", "谁", "什么", "when", "who", "what", "默认端口", "port");
        boolean hasBroadCue = containsAny(normalized, "为什么", "原理", "流程", "步骤", "区别", "对比", "总结", "分析", "如何", "tradeoff", "architecture", "取舍");
        boolean hasMultiAskCue = containsAny(normalized, "以及", "并且", "和", "与", "区别", "对比");

        double ambiguity = 0.0d;
        if (shortQuery) {
            ambiguity += 0.18d;
        }
        if (hasPronounCue) {
            ambiguity += 0.34d;
        }
        if (conversationDependent) {
            ambiguity += 0.28d;
        }
        if (!hasFactCue && keywordCount <= 2) {
            ambiguity += 0.12d;
        }

        double breadth = 0.0d;
        if (hasBroadCue) {
            breadth += 0.34d;
        }
        if (hasMultiAskCue) {
            breadth += 0.20d;
        }
        if (keywordCount >= 5) {
            breadth += 0.18d;
        }
        if (normalized.length() >= 24) {
            breadth += 0.16d;
        }

        double complexity = clamp(Math.max(ambiguity * 0.85d, breadth) + (keywordCount >= 4 ? 0.08d : 0.0d));
        QueryIntentHint intent = suggestIntent(ambiguity, breadth);
        double confidence = confidence(ambiguity, breadth, shortQuery, hasFactCue, conversationDependent);

        return new Analysis(
                clamp(ambiguity),
                clamp(breadth),
                clamp(complexity),
                clamp(confidence),
                intent
        );
    }

    private QueryIntentHint suggestIntent(double ambiguity, double breadth) {
        if (ambiguity >= properties.getAmbiguityThreshold() && ambiguity - breadth >= 0.08d) {
            return QueryIntentHint.AMBIGUOUS;
        }
        if (breadth >= properties.getBreadthThreshold() && breadth - ambiguity >= 0.08d) {
            return QueryIntentHint.BROAD;
        }
        return QueryIntentHint.DIRECT;
    }

    private double confidence(double ambiguity, double breadth, boolean shortQuery,
                              boolean hasFactCue, boolean conversationDependent) {
        double dominance = Math.abs(ambiguity - breadth);
        double confidence = 0.42d + dominance;
        if (ambiguity >= 0.30d && breadth >= 0.30d && dominance <= 0.15d) {
            confidence -= 0.24d;
        }
        if (conversationDependent && shortQuery && breadth >= 0.40d) {
            confidence -= 0.20d;
        }
        if (hasFactCue && !conversationDependent && !shortQuery) {
            confidence += 0.14d;
        }
        if (conversationDependent && ambiguity < properties.getAmbiguityThreshold()) {
            confidence -= 0.12d;
        }
        return confidence;
    }

    private int countKeywords(String normalized) {
        Set<String> keywords = new LinkedHashSet<>();
        for (String token : normalized.split("[^\\p{L}\\p{N}\\u4E00-\\u9FFF]+")) {
            String value = token.trim();
            if (value.length() >= 2) {
                keywords.add(value);
            }
        }
        return keywords.size();
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private double clamp(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }
}
