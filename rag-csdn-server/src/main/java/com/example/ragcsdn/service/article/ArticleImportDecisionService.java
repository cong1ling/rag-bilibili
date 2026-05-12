package com.example.ragcsdn.service.article;

import com.example.ragcsdn.entity.Article;
import com.example.ragcsdn.enums.ArticleStatus;
import com.example.ragcsdn.exception.BusinessException;
import com.example.ragcsdn.exception.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class ArticleImportDecisionService {

    public ImportDecision decideExistingArticle(Article existingArticle) {
        if (existingArticle == null) {
            return new ImportDecision(Action.CREATE_NEW, null);
        }
        if (ArticleStatus.FAILED.getCode().equals(existingArticle.getStatus())) {
            return new ImportDecision(Action.RETRY_FAILED, existingArticle);
        }
        return new ImportDecision(Action.REJECT_DUPLICATE, existingArticle);
    }

    public boolean isDuplicateArticle(BusinessException ex) {
        return ex.getErrorCode() == ErrorCode.VIDEO_ALREADY_EXISTS
                || (ex.getErrorCode() == null && Objects.equals(ex.getCode(), ErrorCode.VIDEO_ALREADY_EXISTS.getCode()));
    }

    public enum Action {
        CREATE_NEW,
        RETRY_FAILED,
        REJECT_DUPLICATE
    }

    public record ImportDecision(Action action, Article article) {
    }
}
