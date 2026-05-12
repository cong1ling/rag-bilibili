package com.example.ragcsdn.service.chat;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class ChatPromptBuilder {

    public String buildContext(List<Document> documents) {
        if (documents.isEmpty()) {
            return "没有找到相关的文章内容。";
        }

        StringBuilder context = new StringBuilder();
        context.append("以下是检索到的相关文章片段，请优先依据片段头部的来源信息进行回答：\n\n");

        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            context.append(buildSourceHeader(doc, i + 1)).append("\n");
            context.append(doc.getText());
            context.append("\n\n");
        }

        return context.toString();
    }

    public String buildSystemPrompt(String context) {
        return buildSystemPrompt(
                context,
                null,
                new ResponseConfidenceService.ResponseConfidence("MEDIUM", 0.5d, false),
                true
        );
    }

    public String buildSystemPrompt(String context, String memorySummary,
                                    ResponseConfidenceService.ResponseConfidence confidence,
                                    boolean confidenceAwareEnabled) {
        String memorySection = (memorySummary == null || memorySummary.isBlank())
                ? ""
                : "\n对话摘要（优先作为历史背景，不可覆盖检索事实）：\n" + memorySummary + "\n";
        String confidenceSection = (confidence == null || !confidenceAwareEnabled)
                ? ""
                : switch (confidence.label()) {
                    case "HIGH" -> "\n当前检索置信度：高。回答时直接给出结论并附来源。\n";
                    case "LOW" -> "\n当前检索置信度：低。回答时必须明确标注“仅供参考”，并说明信息可能不足。\n";
                    default -> "\n当前检索置信度：中。回答时保持审慎，关键结论必须带来源。\n";
                };
        return String.format(
                "你是一个基于CSDN文章内容的智能问答助手。\n\n" +
                        "你的任务是根据提供的文章内容片段，准确、克制地回答用户的问题。\n\n" +
                        "注意事项：\n" +
                        "1. 仅基于提供的文章内容片段回答，不要补充片段之外的事实\n" +
                        "2. 回答中的关键结论后要附上来源，优先使用“(来源: 文章名 片段x/y)”格式\n" +
                        "3. 如果多个片段存在冲突，单独列出“不一致信息”并说明各自来源\n" +
                        "4. 如果信息不足以完整回答，请明确说明“根据当前检索片段，无法完整回答该问题”，再给出已知部分\n" +
                        "5. 回答要准确、简洁、有条理，避免编造\n" +
                        "%s" +
                        "%s\n" +
                        "%s",
                memorySection,
                confidenceSection,
                context
        );
    }

    private String buildSourceHeader(Document document, int fallbackIndex) {
        String title = getMetadataString(document, "title", "未知文章");
        String sourceId = getMetadataString(document, "sourceId", "未知标识");
        int chunkIndex = getMetadataInt(document, "chunkIndex", fallbackIndex - 1) + 1;
        int totalChunks = getMetadataInt(document, "totalChunks", 0);
        double score = getMetadataDouble(document, "score", 0.0d);
        String scoreLabel = getMetadataString(document, "scoreLabel", "相似度");

        if (totalChunks > 0) {
            return String.format(Locale.ROOT,
                    "[文章: %s, 标识: %s, 片段 %d/%d, %s: %.3f]",
                    title, sourceId, chunkIndex, totalChunks, scoreLabel, score);
        }

        return String.format(Locale.ROOT,
                "[文章: %s, 标识: %s, 片段 %d, %s: %.3f]",
                title, sourceId, chunkIndex, scoreLabel, score);
    }

    private String getMetadataString(Document document, String key, String defaultValue) {
        Map<String, Object> metadata = document.getMetadata();
        if (metadata == null) {
            return defaultValue;
        }
        Object value = metadata.get(key);
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? defaultValue : text;
    }

    private int getMetadataInt(Document document, String key, int defaultValue) {
        Map<String, Object> metadata = document.getMetadata();
        if (metadata == null) {
            return defaultValue;
        }
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private double getMetadataDouble(Document document, String key, double defaultValue) {
        Map<String, Object> metadata = document.getMetadata();
        if (metadata == null) {
            return defaultValue;
        }
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
}
