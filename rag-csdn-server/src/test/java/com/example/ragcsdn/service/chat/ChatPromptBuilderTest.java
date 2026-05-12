package com.example.ragcsdn.service.chat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatPromptBuilderTest {

    @Test
    void buildContextShouldIncludeSourceHeaderAndChunkText() {
        ChatPromptBuilder builder = new ChatPromptBuilder();

        HashMap<String, Object> metadata = new HashMap<>();
        metadata.put("title", "Spring 实战");
        metadata.put("sourceId", "BV1xx411c7mu");
        metadata.put("chunkIndex", 1);
        metadata.put("totalChunks", 8);
        metadata.put("score", 0.9123d);

        List<Document> documents = List.of(Document.builder()
                .text("Spring Boot 提升了开发效率。")
                .metadata(metadata)
                .build());

        String context = builder.buildContext(documents);

        assertThat(context).contains("文章: Spring 实战");
        assertThat(context).contains("标识: BV1xx411c7mu");
        assertThat(context).contains("片段 2/8");
        assertThat(context).contains("相似度: 0.912");
        assertThat(context).contains("Spring Boot 提升了开发效率。");
    }
}
