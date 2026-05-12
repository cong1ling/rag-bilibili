package com.example.ragcsdn.service.chat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentRerankServiceTest {

    @Test
    void rerankDocuments_shouldPrioritizeExactTitleAndTextMatches() {
        DocumentRerankService service = new DocumentRerankService(new ChatMetadataHelper(), new RetrievalPipelineService(new ChatMetadataHelper()));
        List<Document> candidates = List.of(
                doc("这里主要讨论 JVM 调优。", "Java 性能优化", "BV1java", 0, 5, 0.95d, ChatMetadataHelper.SCORE_LABEL_HYBRID),
                doc("Spring Boot 默认端口是 8080，也可以通过 server.port 修改。", "Spring Boot 配置", "BV1boot", 2, 6, 0.40d, ChatMetadataHelper.SCORE_LABEL_HYBRID),
                doc("Tomcat 默认线程池参数说明。", "Tomcat 原理", "BV1tomcat", 1, 4, 0.80d, ChatMetadataHelper.SCORE_LABEL_HYBRID)
        );

        List<Document> reranked = service.rerankDocuments("Spring Boot 默认端口", candidates, 2, 20, false, 8, null);

        assertThat(reranked).hasSize(2);
        assertThat(reranked.get(0).getText()).contains("8080");
        assertThat(reranked.get(0).getMetadata()).containsEntry("scoreLabel", "重排得分");
        assertThat(reranked.get(0).getMetadata()).containsEntry("retrievalSource", "rerank");
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
        metadata.put("retrievalSource", "hybrid");
        return Document.builder().text(text).metadata(metadata).build();
    }
}
