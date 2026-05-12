package com.example.ragcsdn.service.article;

import com.example.ragcsdn.entity.Article;
import com.example.ragcsdn.enums.ArticleStatus;
import com.example.ragcsdn.exception.BusinessException;
import com.example.ragcsdn.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArticleImportDecisionServiceTest {

    private final ArticleImportDecisionService service = new ArticleImportDecisionService();

    @Test
    void decideImport_shouldRetryFailedArticleInsteadOfThrowingDuplicate() {
        Article failed = new Article();
        failed.setStatus(ArticleStatus.FAILED.getCode());
        failed.setSourceId("147000001");

        ArticleImportDecisionService.ImportDecision decision = service.decideExistingArticle(failed);

        assertThat(decision.action()).isEqualTo(ArticleImportDecisionService.Action.RETRY_FAILED);
        assertThat(decision.article()).isSameAs(failed);
    }

    @Test
    void decideImport_shouldRejectNonFailedExistingArticleAsDuplicate() {
        Article existing = new Article();
        existing.setStatus(ArticleStatus.SUCCESS.getCode());

        ArticleImportDecisionService.ImportDecision decision = service.decideExistingArticle(existing);

        assertThat(decision.action()).isEqualTo(ArticleImportDecisionService.Action.REJECT_DUPLICATE);
        assertThat(decision.article()).isSameAs(existing);
    }

    @Test
    void isDuplicateArticle_shouldRecognizeEnumAndCodeBasedExceptions() {
        BusinessException byEnum = new BusinessException(ErrorCode.VIDEO_ALREADY_EXISTS);
        BusinessException byCode = new BusinessException(ErrorCode.VIDEO_ALREADY_EXISTS.getCode(), "duplicate");

        assertThat(service.isDuplicateArticle(byEnum)).isTrue();
        assertThat(service.isDuplicateArticle(byCode)).isTrue();
    }
}
