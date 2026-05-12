package com.example.ragcsdn.service.chat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalPipelineServiceTest {

    @Test
    void mergeHybridResults_shouldPreferFusedScoreAndPreserveDistinctChunks() {
        RetrievalPipelineService service = new RetrievalPipelineService(new ChatMetadataHelper());
        List<Document> merged = service.mergeHybridResults(List.of(
                doc("A", "标题", "sid", 0, 1, 0.8d)
        ), List.of(
                doc("A", "标题", "sid", 0, 1, 3.0d, ChatMetadataHelper.SCORE_LABEL_KEYWORD)
        ), 5);

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).getMetadata().get("scoreLabel")).isEqualTo(ChatMetadataHelper.SCORE_LABEL_HYBRID);
        assertThat(merged.get(0).getMetadata().get("retrievalSource")).isEqualTo("hybrid");
    }

    private Document doc(String text, String title, String sourceId, int chunkIndex,
                         int totalChunks, double score) {
        return doc(text, title, sourceId, chunkIndex, totalChunks, score, ChatMetadataHelper.SCORE_LABEL_VECTOR);
    }

    private Document doc(String text, String title, String sourceId, int chunkIndex,
                         int totalChunks, double score, String scoreLabel) {
        HashMap<String, Object> metadata = new HashMap<>();
        metadata.put("title", title);
        metadata.put("sourceId", sourceId);
        metadata.put("chunkIndex", chunkIndex);
        metadata.put("totalChunks", totalChunks);
        metadata.put("score", score);
        metadata.put("scoreLabel", scoreLabel);
        return Document.builder().text(text).metadata(metadata).build();
    }
}
