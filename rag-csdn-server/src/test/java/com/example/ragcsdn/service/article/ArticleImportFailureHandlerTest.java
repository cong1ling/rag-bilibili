package com.example.ragcsdn.service.article;

import com.example.ragcsdn.entity.Article;
import com.example.ragcsdn.mapper.ArticleMapper;
import com.example.ragcsdn.service.impl.ArticleStatusWriter;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArticleImportFailureHandlerTest {

    @Test
    void markFailedShouldLoadArticleAndDelegateToStatusWriter() {
        ArticleMapper articleMapper = mock(ArticleMapper.class);
        ArticleStatusWriter articleStatusWriter = mock(ArticleStatusWriter.class);

        Article article = new Article();
        article.setId(88L);
        when(articleMapper.selectById(88L)).thenReturn(article);

        ArticleImportFailureHandler handler = new ArticleImportFailureHandler(articleMapper, articleStatusWriter);
        handler.markFailed(88L, "boom");

        verify(articleMapper).selectById(88L);
        verify(articleStatusWriter).markFailed(article, "boom");
    }

    @Test
    void markFailedShouldDoNothingWhenArticleDoesNotExist() {
        ArticleMapper articleMapper = mock(ArticleMapper.class);
        ArticleStatusWriter articleStatusWriter = mock(ArticleStatusWriter.class);
        when(articleMapper.selectById(99L)).thenReturn(null);

        ArticleImportFailureHandler handler = new ArticleImportFailureHandler(articleMapper, articleStatusWriter);
        handler.markFailed(99L, "missing");

        verify(articleMapper).selectById(99L);
        verify(articleStatusWriter, never()).markFailed(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }
}
