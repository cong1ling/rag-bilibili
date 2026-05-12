package com.example.ragcsdn.service.session;

import com.example.ragcsdn.dto.response.SessionResponse;
import com.example.ragcsdn.entity.Article;
import com.example.ragcsdn.entity.Session;
import com.example.ragcsdn.enums.SessionType;
import com.example.ragcsdn.mapper.ArticleMapper;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

@Component
public class SessionResponseAssembler {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ArticleMapper articleMapper;

    public SessionResponseAssembler(ArticleMapper articleMapper) {
        this.articleMapper = articleMapper;
    }

    public SessionResponse toResponse(Session session) {
        SessionResponse response = new SessionResponse();
        response.setId(session.getId());
        response.setSessionType(SessionType.normalize(session.getSessionType()));
        response.setArticleId(session.getArticleId());
        response.setCreateTime(session.getCreateTime().format(FORMATTER));
        response.setConversationSummary(session.getConversationSummary());
        if (session.getSummaryUpdateTime() != null) {
            response.setSummaryUpdateTime(session.getSummaryUpdateTime().format(FORMATTER));
        }

        if (session.getArticleId() != null) {
            Article article = articleMapper.selectById(session.getArticleId());
            if (article != null) {
                response.setArticleTitle(article.getTitle());
            }
        }

        return response;
    }
}
