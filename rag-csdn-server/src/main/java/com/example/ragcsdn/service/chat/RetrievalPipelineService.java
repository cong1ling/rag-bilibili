package com.example.ragcsdn.service.chat;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RetrievalPipelineService {

    private static final int HYBRID_RRF_K = 60;

    private final ChatMetadataHelper chatMetadataHelper;

    public RetrievalPipelineService(ChatMetadataHelper chatMetadataHelper) {
        this.chatMetadataHelper = chatMetadataHelper;
    }

    public List<String> extractKeywords(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        String normalized = query.trim();
        Set<String> keywords = new LinkedHashSet<>();
        if (normalized.length() >= 2) {
            keywords.add(normalized);
        }

        for (String part : normalized.split("[^\\p{L}\\p{N}\\u4E00-\\u9FFF]+")) {
            String token = part.trim();
            if (token.length() >= 2) {
                keywords.add(token);
            }
            if (keywords.size() >= 6) {
                break;
            }
        }

        return new ArrayList<>(keywords);
    }

    public String buildKeywordSearchText(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return "";
        }

        return keywords.stream()
                .map(this::sanitizeFullTextTerm)
                .filter(term -> !term.isBlank())
                .distinct()
                .limit(6)
                .collect(Collectors.joining(" "));
    }

    public String sanitizeFullTextTerm(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }

        String sanitized = input
                .replaceAll("[^\\p{L}\\p{N}\\u4E00-\\u9FFF]+", " ")
                .replaceAll("\\s+", " ")
                .trim();

        if (sanitized.length() < 2) {
            return "";
        }
        return sanitized;
    }

    public List<Document> mergeHybridResults(List<Document> vectorResults, List<Document> keywordResults, int limit) {
        if (keywordResults.isEmpty()) {
            return vectorResults.stream().limit(limit).collect(Collectors.toList());
        }
        if (vectorResults.isEmpty()) {
            return keywordResults.stream()
                    .limit(limit)
                    .map(document -> document.mutate().metadata("score", 1.0d).build())
                    .collect(Collectors.toList());
        }

        Map<String, Document> documentByKey = new LinkedHashMap<>();
        Map<String, Double> fusedScores = new LinkedHashMap<>();
        Map<String, Set<String>> sourceByKey = new LinkedHashMap<>();

        accumulateHybridScores(vectorResults, "vector", 1.0d, documentByKey, fusedScores, sourceByKey);
        accumulateHybridScores(keywordResults, "keyword", 0.7d, documentByKey, fusedScores, sourceByKey);

        return fusedScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .map(entry -> {
                    Document base = documentByKey.get(entry.getKey());
                    Set<String> sources = sourceByKey.getOrDefault(entry.getKey(), Set.of());
                    String label = sources.size() > 1
                            ? ChatMetadataHelper.SCORE_LABEL_HYBRID
                            : sources.contains("keyword") ? ChatMetadataHelper.SCORE_LABEL_KEYWORD : ChatMetadataHelper.SCORE_LABEL_VECTOR;
                    String retrievalSource = sources.size() > 1 ? "hybrid" : sources.stream().findFirst().orElse("vector");
                    return base.mutate()
                            .metadata("score", entry.getValue())
                            .metadata("scoreLabel", label)
                            .metadata("retrievalSource", retrievalSource)
                            .build();
                })
                .collect(Collectors.toList());
    }

    public List<Document> mergeQueryCandidates(List<List<Document>> rankedLists, int limit) {
        if (rankedLists.isEmpty()) {
            return List.of();
        }
        if (rankedLists.size() == 1) {
            return rankedLists.get(0).stream().limit(limit).collect(Collectors.toList());
        }

        Map<String, Document> documentByKey = new LinkedHashMap<>();
        Map<String, Double> fusedScores = new LinkedHashMap<>();
        Map<String, Set<String>> sourceByKey = new LinkedHashMap<>();

        for (int i = 0; i < rankedLists.size(); i++) {
            accumulateHybridScores(rankedLists.get(i), "query-" + i, 1.0d, documentByKey, fusedScores, sourceByKey);
        }

        return fusedScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .map(entry -> documentByKey.get(entry.getKey()).mutate()
                        .metadata("score", entry.getValue())
                        .metadata("scoreLabel", ChatMetadataHelper.SCORE_LABEL_MULTI_QUERY)
                        .metadata("retrievalSource", "multi-query")
                        .build())
                .collect(Collectors.toList());
    }

    private void accumulateHybridScores(List<Document> documents,
                                        String source,
                                        double weight,
                                        Map<String, Document> documentByKey,
                                        Map<String, Double> fusedScores,
                                        Map<String, Set<String>> sourceByKey) {
        for (int i = 0; i < documents.size(); i++) {
            Document document = documents.get(i);
            String key = chatMetadataHelper.buildDocumentKey(document);
            documentByKey.putIfAbsent(key, document);
            fusedScores.merge(key, weight / (HYBRID_RRF_K + i + 1), Double::sum);
            sourceByKey.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(source);
        }
    }
}
