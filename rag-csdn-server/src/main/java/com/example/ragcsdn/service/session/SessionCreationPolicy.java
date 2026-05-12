package com.example.ragcsdn.service.session;

import com.example.ragcsdn.dto.request.CreateSessionRequest;
import com.example.ragcsdn.entity.Article;
import com.example.ragcsdn.enums.SessionType;
import com.example.ragcsdn.exception.BusinessException;
import com.example.ragcsdn.exception.ErrorCode;
import com.example.ragcsdn.mapper.ArticleMapper;
import org.springframework.stereotype.Component;

@Component
public class SessionCreationPolicy {

    private final ArticleMapper articleMapper;

    public SessionCreationPolicy(ArticleMapper articleMapper) {
        this.articleMapper = articleMapper;
    }

    public String validateAndNormalize(CreateSessionRequest request, Long userId) {
        String normalizedSessionType = SessionType.normalize(request.getSessionType());
        if (!SessionType.isValid(request.getSessionType())) {
            throw new BusinessException(ErrorCode.SESSION_TYPE_ERROR);
        }

        if (SessionType.isSingleArticle(normalizedSessionType)) {
            if (request.getArticleId() == null) {
                throw new BusinessException(ErrorCode.PARAM_ERROR);
            }
            Article article = articleMapper.selectById(request.getArticleId());
            if (article == null || !article.getUserId().equals(userId)) {
                throw new BusinessException(ErrorCode.VIDEO_NOT_FOUND);
            }
        }

        return normalizedSessionType;
    }
}
