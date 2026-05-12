package com.example.ragcsdn.service.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DocumentRerankService {

    private final ChatMetadataHelper chatMetadataHelper;
    private final RetrievalPipelineService retrievalPipelineService;

    public DocumentRerankService(ChatMetadataHelper chatMetadataHelper,
                                 RetrievalPipelineService retrievalPipelineService) {
        this.chatMetadataHelper = chatMetadataHelper;
        this.retrievalPipelineService = retrievalPipelineService;
    }

    public List<Document> rerankDocuments(String query,
                                          List<Document> candidates,
                                          int finalTopK,
                                          int candidateTopK,
                                          boolean modelRerankEnabled,
                                          int modelRerankTopK,
                                          ChatClient.Builder chatClientBuilder) {
        if (candidates.isEmpty()) {
            return candidates;
        }

        List<String> keywords = retrievalPipelineService.extractKeywords(query);
        String normalizedQuery = chatMetadataHelper.normalizeForMatch(query);

        List<Document> ruleRanked = candidates.stream()
                .limit(candidateTopK)
                .map(document -> {
                    double rerankScore = computeRerankScore(document, normalizedQuery, keywords);
                    return document.mutate()
                            .metadata("score", rerankScore)
                            .metadata("scoreLabel", ChatMetadataHelper.SCORE_LABEL_RERANK)
                            .metadata("retrievalSource", "rerank")
                            .build();
                })
                .sorted(Comparator
                        .comparingDouble((Document document) -> chatMetadataHelper.getMetadataDouble(document, "score", 0.0d))
                        .reversed()
                        .thenComparing(document -> chatMetadataHelper.getMetadataString(document, "title", ""), Comparator.reverseOrder()))
                .limit(finalTopK)
                .collect(Collectors.toList());

        if (!modelRerankEnabled || ruleRanked.size() <= 1 || chatClientBuilder == null) {
            return ruleRanked;
        }
        return rerankDocumentsWithModel(query, ruleRanked, finalTopK, modelRerankTopK, chatClientBuilder);
    }

    private List<Document> rerankDocumentsWithModel(String query,
                                                    List<Document> ruleRanked,
                                                    int finalTopK,
                                                    int modelRerankTopK,
                                                    ChatClient.Builder chatClientBuilder) {
        int modelWindowSize = Math.min(ruleRanked.size(), modelRerankTopK);
        if (modelWindowSize <= 1) {
            return ruleRanked;
        }

        List<Document> modelWindow = new ArrayList<>(ruleRanked.subList(0, modelWindowSize));
        try {
            String result = chatClientBuilder.build().prompt()
                    .system("""
                            你是RAG检索重排器。
                            你的任务是根据用户问题，对候选片段按“最有助于回答问题”的顺序重排。
                            评估标准：
                            1. 与问题直接相关
                            2. 能提供更完整、更精确的事实
                            3. 来源信息明确
                            4. 避免重复语义
                            只输出候选编号，使用英文逗号分隔，例如：2,1,3
                            不要输出解释，不要输出编号之外的内容。
                            """)
                    .user(buildModelRerankPrompt(query, modelWindow, finalTopK))
                    .call()
                    .content();

            return applyModelRerankResult(modelWindow, ruleRanked, result, finalTopK);
        } catch (Exception ignored) {
            return ruleRanked;
        }
    }

    String buildModelRerankPrompt(String query, List<Document> candidates, int finalTopK) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("用户问题：").append(query).append("\n");
        prompt.append("请从以下候选片段中选出最相关的前")
                .append(Math.min(finalTopK, candidates.size()))
                .append("个，并按相关性从高到低排序。\n\n");

        for (int i = 0; i < candidates.size(); i++) {
            Document document = candidates.get(i);
            prompt.append("候选").append(i + 1).append("：\n")
                    .append("标题：").append(chatMetadataHelper.getMetadataString(document, "title", "未知文章")).append("\n")
                    .append("标识：").append(chatMetadataHelper.getMetadataString(document, "sourceId", "未知标识")).append("\n")
                    .append("片段：").append(truncateForModelRerank(document.getText())).append("\n\n");
        }

        prompt.append("只输出编号列表。");
        return prompt.toString();
    }

    String truncateForModelRerank(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 220) {
            return normalized;
        }
        return normalized.substring(0, 220) + "...";
    }

    List<Document> applyModelRerankResult(List<Document> modelWindow,
                                          List<Document> ruleRanked,
                                          String rawOrder,
                                          int finalTopK) {
        List<Integer> order = parseModelRerankOrder(rawOrder, modelWindow.size());
        if (order.isEmpty()) {
            return ruleRanked;
        }

        List<Document> ordered = new ArrayList<>();
        Set<String> consumedKeys = new LinkedHashSet<>();
        int scoreSeed = modelWindow.size();

        for (Integer index : order) {
            Document document = modelWindow.get(index);
            ordered.add(document.mutate()
                    .metadata("score", (double) scoreSeed--)
                    .metadata("scoreLabel", ChatMetadataHelper.SCORE_LABEL_MODEL_RERANK)
                    .metadata("retrievalSource", "model-rerank")
                    .build());
            consumedKeys.add(chatMetadataHelper.buildDocumentKey(document));
        }

        for (Document document : modelWindow) {
            String key = chatMetadataHelper.buildDocumentKey(document);
            if (consumedKeys.add(key)) {
                ordered.add(document);
            }
        }

        for (int i = modelWindow.size(); i < ruleRanked.size(); i++) {
            Document document = ruleRanked.get(i);
            String key = chatMetadataHelper.buildDocumentKey(document);
            if (consumedKeys.add(key)) {
                ordered.add(document);
            }
        }

        return ordered.stream().limit(finalTopK).collect(Collectors.toList());
    }

    List<Integer> parseModelRerankOrder(String rawOrder, int candidateSize) {
        if (rawOrder == null || rawOrder.isBlank()) {
            return List.of();
        }

        Set<Integer> orderedIndexes = new LinkedHashSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d+").matcher(rawOrder);
        while (matcher.find()) {
            int oneBasedIndex = Integer.parseInt(matcher.group());
            if (oneBasedIndex >= 1 && oneBasedIndex <= candidateSize) {
                orderedIndexes.add(oneBasedIndex - 1);
            }
        }
        return new ArrayList<>(orderedIndexes);
    }

    double computeRerankScore(Document document, String normalizedQuery, List<String> keywords) {
        String title = chatMetadataHelper.normalizeForMatch(chatMetadataHelper.getMetadataString(document, "title", ""));
        String text = chatMetadataHelper.normalizeForMatch(document.getText());
        double baseScore = chatMetadataHelper.getMetadataDouble(document, "score", 0.0d);
        String retrievalSource = chatMetadataHelper.getMetadataString(document, "retrievalSource", "");

        double score = baseScore * 4.0d;
        if (!normalizedQuery.isBlank()) {
            if (!title.isBlank() && title.contains(normalizedQuery)) {
                score += 4.0d;
            }
            if (!text.isBlank() && text.contains(normalizedQuery)) {
                score += 3.0d;
            }
        }

        int matchedKeywords = 0;
        int effectiveKeywords = 0;
        for (String keyword : keywords) {
            String normalizedKeyword = chatMetadataHelper.normalizeForMatch(keyword);
            if (normalizedKeyword.isBlank() || normalizedKeyword.equals(normalizedQuery)) {
                continue;
            }
            effectiveKeywords++;
            if (!title.isBlank() && title.contains(normalizedKeyword)) {
                matchedKeywords++;
                score += 1.8d;
                continue;
            }
            if (!text.isBlank() && text.contains(normalizedKeyword)) {
                matchedKeywords++;
                score += 1.1d;
            }
        }

        if (effectiveKeywords > 0) {
            score += ((double) matchedKeywords / effectiveKeywords) * 2.5d;
        }
        if ("hybrid".equals(retrievalSource)) {
            score += 0.5d;
        } else if ("keyword".equals(retrievalSource)) {
            score += 0.2d;
        }
        return score;
    }
}
