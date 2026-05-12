package com.example.ragcsdn.service.article;

import com.example.ragcsdn.dto.response.ArticleResponse;
import com.example.ragcsdn.entity.Article;
import com.example.ragcsdn.mapper.ChunkMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArticleResponseAssemblerTest {

    @Test
    void toResponseShouldIncludeChunkCountAndFailureFields() {
        ChunkMapper chunkMapper = mock(ChunkMapper.class);
        when(chunkMapper.countByArticleId(100L)).thenReturn(3);

        Article article = new Article();
        article.setId(100L);
        article.setSourceId("147000001");
        article.setSourceUrl("https://blog.csdn.net/test/article/details/147000001");
        article.setTitle("结构化清洗");
        article.setDescription("desc");
        article.setImportTime(LocalDateTime.of(2026, 5, 12, 10, 0, 0));
        article.setStatus("FAILED");
        article.setFailReason("boom");

        ArticleResponseAssembler assembler = new ArticleResponseAssembler(chunkMapper);
        ArticleResponse response = assembler.toResponse(article);

        assertEquals(100L, response.getId());
        assertEquals("147000001", response.getSourceId());
        assertEquals("https://blog.csdn.net/test/article/details/147000001", response.getSourceUrl());
        assertEquals("结构化清洗", response.getTitle());
        assertEquals("desc", response.getDescription());
        assertEquals(3, response.getChunkCount());
        assertEquals("2026-05-12 10:00:00", response.getImportTime());
        assertEquals("FAILED", response.getStatus());
        assertEquals("boom", response.getFailReason());
    }
}
