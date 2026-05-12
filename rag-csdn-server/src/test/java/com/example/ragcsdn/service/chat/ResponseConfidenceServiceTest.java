package com.example.ragcsdn.service.chat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseConfidenceServiceTest {

    @Test
    void evaluateConfidenceShouldReturnLowWhenNoDocuments() {
        ResponseConfidenceService service = new ResponseConfidenceService();

        ResponseConfidenceService.ResponseConfidence confidence =
                service.evaluateConfidence(List.of(), true);

        assertThat(confidence.label()).isEqualTo("LOW");
        assertThat(confidence.score()).isEqualTo(0.0d);
        assertThat(confidence.knowledgeGap()).isTrue();
    }

    @Test
    void evaluateConfidenceShouldReturnHighForStrongTopScoreWithEnoughDocs() {
        ResponseConfidenceService service = new ResponseConfidenceService();

        List<Document> docs = List.of(
                doc(8.8d),
                doc(7.5d),
                doc(6.9d)
        );

        ResponseConfidenceService.ResponseConfidence confidence =
                service.evaluateConfidence(docs, true);

        assertThat(confidence.label()).isEqualTo("HIGH");
        assertThat(confidence.score()).isCloseTo(0.88d, org.assertj.core.data.Offset.offset(0.0001d));
        assertThat(confidence.knowledgeGap()).isFalse();
    }

    private Document doc(double score) {
        HashMap<String, Object> metadata = new HashMap<>();
        metadata.put("score", score);
        return Document.builder()
                .text("text")
                .metadata(metadata)
                .build();
    }
}
