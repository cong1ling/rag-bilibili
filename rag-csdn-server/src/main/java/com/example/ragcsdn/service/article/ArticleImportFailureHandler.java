package com.example.ragcsdn.service.article;

import com.example.ragcsdn.entity.Article;
import com.example.ragcsdn.mapper.ArticleMapper;
import com.example.ragcsdn.service.impl.ArticleStatusWriter;
import org.springframework.stereotype.Component;

@Component
public class ArticleImportFailureHandler {

    private final ArticleMapper articleMapper;
    private final ArticleStatusWriter articleStatusWriter;

    public ArticleImportFailureHandler(ArticleMapper articleMapper, ArticleStatusWriter articleStatusWriter) {
        this.articleMapper = articleMapper;
        this.articleStatusWriter = articleStatusWriter;
    }

    public void markFailed(Long articleId, String reason) {
        Article article = articleMapper.selectById(articleId);
        if (article != null) {
            articleStatusWriter.markFailed(article, reason);
        }
    }
}
