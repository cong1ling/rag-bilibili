package com.example.ragcsdn.service.article;

import com.example.ragcsdn.dto.response.ArticleResponse;
import com.example.ragcsdn.entity.Article;
import com.example.ragcsdn.enums.ArticleStatus;
import com.example.ragcsdn.mapper.ArticleMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class ArticleImportCommandService {
    private static final String IMPORTING_TITLE = "导入中...";
    private static final String RETRY_IMPORTING_TITLE = "重新导入中...";
    private static final String REBUILDING_TITLE = "重建中...";

    private final ArticleMapper articleMapper;
    private final ArticleResponseAssembler articleResponseAssembler;
    private final TaskExecutor articleImportTaskExecutor;

    public ArticleImportCommandService(
            ArticleMapper articleMapper,
            ArticleResponseAssembler articleResponseAssembler,
            @Qualifier("articleImportTaskExecutor")
            TaskExecutor articleImportTaskExecutor
    ) {
        this.articleMapper = articleMapper;
        this.articleResponseAssembler = articleResponseAssembler;
        this.articleImportTaskExecutor = articleImportTaskExecutor;
    }

    public ArticleResponse submitNewImport(Long userId, String sourceId, String normalizedArticleUrl, Runnable importTask) {
        Article article = new Article();
        article.setUserId(userId);
        article.setSourceId(sourceId);
        article.setSourceUrl(normalizedArticleUrl);
        article.setTitle(IMPORTING_TITLE);
        article.setStatus(ArticleStatus.IMPORTING.getCode());
        article.setImportTime(LocalDateTime.now());
        articleMapper.insert(article);

        scheduleImport(importTask);
        return articleResponseAssembler.toResponse(article);
    }

    public ArticleResponse retryFailedImport(Article article, String normalizedArticleUrl, Runnable importTask) {
        article.setSourceUrl(normalizedArticleUrl);
        article.setStatus(ArticleStatus.IMPORTING.getCode());
        article.setFailReason(null);
        if (article.getTitle() == null || article.getTitle().isBlank()) {
            article.setTitle(RETRY_IMPORTING_TITLE);
        }
        articleMapper.update(article);

        scheduleImport(importTask);
        return articleResponseAssembler.toResponse(article);
    }

    public ArticleResponse submitRebuild(Article article, Runnable importTask) {
        article.setStatus(ArticleStatus.IMPORTING.getCode());
        article.setFailReason(null);
        if (article.getTitle() == null || article.getTitle().isBlank()) {
            article.setTitle(REBUILDING_TITLE);
        }
        articleMapper.update(article);

        scheduleImport(importTask);
        return articleResponseAssembler.toResponse(article);
    }

    private void scheduleImport(Runnable importTask) {
        articleImportTaskExecutor.execute(importTask);
    }
}
