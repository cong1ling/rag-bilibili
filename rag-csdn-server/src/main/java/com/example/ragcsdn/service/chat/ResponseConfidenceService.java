package com.example.ragcsdn.service.chat;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class ResponseConfidenceService {

    public record ResponseConfidence(
            String label,
            double score,
            boolean knowledgeGap
    ) {
    }

    public ResponseConfidence evaluateConfidence(List<Document> documents, boolean confidenceAwareEnabled) {
        if (!confidenceAwareEnabled) {
            return new ResponseConfidence("MEDIUM", 0.5d, false);
        }
        if (documents == null || documents.isEmpty()) {
            return new ResponseConfidence("LOW", 0.0d, true);
        }

        double topScore = getMetadataDouble(documents.get(0), "score", 0.0d);
        double normalizedScore = Math.min(1.0d, topScore / 10.0d);
        if (documents.size() >= 3 && topScore >= 8.0d) {
            return new ResponseConfidence("HIGH", normalizedScore, false);
        }
        if (topScore >= 5.0d || documents.size() >= 2) {
            return new ResponseConfidence("MEDIUM", Math.max(0.5d, normalizedScore), false);
        }
        return new ResponseConfidence("LOW", Math.max(0.2d, normalizedScore), true);
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
