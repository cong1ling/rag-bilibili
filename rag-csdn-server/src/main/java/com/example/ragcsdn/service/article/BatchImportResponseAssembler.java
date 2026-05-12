package com.example.ragcsdn.service.article;

import com.alibaba.cloud.ai.reader.csdn.CsdnArticleLink;
import com.example.ragcsdn.dto.response.ArticleResponse;
import com.example.ragcsdn.dto.response.BatchImportItemResponse;
import com.example.ragcsdn.dto.response.BatchImportResponse;
import org.springframework.stereotype.Component;

@Component
public class BatchImportResponseAssembler {

    public BatchImportResponse newResponse(String mode, String target, int discoveredCount) {
        BatchImportResponse response = new BatchImportResponse();
        response.setMode(mode);
        response.setTarget(target);
        response.setDiscoveredCount(discoveredCount);
        response.setSubmittedCount(0);
        response.setDuplicateCount(0);
        response.setFailedCount(0);
        return response;
    }

    public void addSubmitted(BatchImportResponse response, CsdnArticleLink link, ArticleResponse imported) {
        BatchImportItemResponse item = baseItem(link);
        item.setArticleId(imported.getId());
        item.setStatus(BatchImportStatus.SUBMITTED.code());
        item.setMessage(BatchImportStatus.SUBMITTED.defaultMessage());
        response.getItems().add(item);
        response.setSubmittedCount(response.getSubmittedCount() + 1);
    }

    public void addDuplicate(BatchImportResponse response, CsdnArticleLink link, String message) {
        BatchImportItemResponse item = baseItem(link);
        item.setStatus(BatchImportStatus.SKIPPED_DUPLICATE.code());
        item.setMessage(defaultMessageIfBlank(message, BatchImportStatus.SKIPPED_DUPLICATE));
        response.getItems().add(item);
        response.setDuplicateCount(response.getDuplicateCount() + 1);
    }

    public void addFailure(BatchImportResponse response, CsdnArticleLink link, String message) {
        BatchImportItemResponse item = baseItem(link);
        item.setStatus(BatchImportStatus.FAILED.code());
        item.setMessage(defaultMessageIfBlank(message, BatchImportStatus.FAILED));
        response.getItems().add(item);
        response.setFailedCount(response.getFailedCount() + 1);
    }

    private BatchImportItemResponse baseItem(CsdnArticleLink link) {
        BatchImportItemResponse item = new BatchImportItemResponse();
        item.setSourceId(link.sourceId());
        item.setSourceUrl(link.sourceUrl());
        item.setTitle(link.title());
        return item;
    }

    private String defaultMessageIfBlank(String message, BatchImportStatus status) {
        return message == null || message.isBlank() ? status.defaultMessage() : message;
    }
}
