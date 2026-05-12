package com.example.ragcsdn.service.chat;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Component
public class ChatMetadataHelper {

    public static final String SCORE_LABEL_VECTOR = "相似度";
    public static final String SCORE_LABEL_KEYWORD = "关键词得分";
    public static final String SCORE_LABEL_HYBRID = "融合得分";
    public static final String SCORE_LABEL_MULTI_QUERY = "多查询融合得分";
    public static final String SCORE_LABEL_RERANK = "重排得分";
    public static final String SCORE_LABEL_MODEL_RERANK = "模型重排得分";

    public String getMetadataString(Document document, String key, String defaultValue) {
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

    public int getMetadataInt(Document document, String key, int defaultValue) {
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

    public double getMetadataDouble(Document document, String key, double defaultValue) {
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

    public String buildDocumentKey(Document document) {
        return buildChunkKey(
                getMetadataString(document, "sourceId", ""),
                getMetadataInt(document, "chunkIndex", -1),
                document.getText()
        );
    }

    public String buildChunkKey(String sourceId, int chunkIndex, String text) {
        return sourceId + "#" + chunkIndex + "#" + Objects.hashCode(text);
    }

    public String normalizeForMatch(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }
}
