package com.example.ragcsdn.service.session;

import com.example.ragcsdn.dto.response.SessionResponse;
import com.example.ragcsdn.entity.Article;
import com.example.ragcsdn.entity.Session;
import com.example.ragcsdn.mapper.ArticleMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionResponseAssemblerTest {

    @Test
    void toResponseShouldIncludeArticleTitleAndFormattedTimes() {
        ArticleMapper articleMapper = mock(ArticleMapper.class);
        Article article = new Article();
        article.setId(9L);
        article.setTitle("结构化清洗");
        when(articleMapper.selectById(9L)).thenReturn(article);

        Session session = new Session();
        session.setId(3L);
        session.setSessionType("SINGLE_VIDEO");
        session.setArticleId(9L);
        session.setCreateTime(LocalDateTime.of(2026, 5, 12, 13, 0, 0));
        session.setConversationSummary("summary");
        session.setSummaryUpdateTime(LocalDateTime.of(2026, 5, 12, 13, 30, 0));

        SessionResponseAssembler assembler = new SessionResponseAssembler(articleMapper);
        SessionResponse response = assembler.toResponse(session);

        assertEquals(3L, response.getId());
        assertEquals("SINGLE_ARTICLE", response.getSessionType());
        assertEquals(9L, response.getArticleId());
        assertEquals("结构化清洗", response.getArticleTitle());
        assertEquals("2026-05-12 13:00:00", response.getCreateTime());
        assertEquals("summary", response.getConversationSummary());
        assertEquals("2026-05-12 13:30:00", response.getSummaryUpdateTime());
    }
}
