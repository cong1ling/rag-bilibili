package com.example.ragcsdn.service.article;

import com.alibaba.cloud.ai.reader.csdn.CsdnArticleLink;
import com.example.ragcsdn.dto.response.ArticleResponse;
import com.example.ragcsdn.dto.response.BatchImportResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BatchImportResponseAssemblerTest {

    @Test
    void assemblerShouldTrackSubmittedDuplicateAndFailedItems() {
        BatchImportResponseAssembler assembler = new BatchImportResponseAssembler();
        BatchImportResponse response = assembler.newResponse("AUTHOR_PUBLIC", "https://blog.csdn.net/test", 3);

        CsdnArticleLink submittedLink = new CsdnArticleLink("a-1", "https://a", "标题A", "author");
        ArticleResponse imported = new ArticleResponse();
        imported.setId(11L);
        assembler.addSubmitted(response, submittedLink, imported);

        CsdnArticleLink duplicateLink = new CsdnArticleLink("a-2", "https://b", "标题B", "author");
        assembler.addDuplicate(response, duplicateLink, "该文章已导入");

        CsdnArticleLink failedLink = new CsdnArticleLink("a-3", "https://c", "标题C", "author");
        assembler.addFailure(response, failedLink, "抓取失败");

        assertEquals("AUTHOR_PUBLIC", response.getMode());
        assertEquals("https://blog.csdn.net/test", response.getTarget());
        assertEquals(3, response.getDiscoveredCount());
        assertEquals(1, response.getSubmittedCount());
        assertEquals(1, response.getDuplicateCount());
        assertEquals(1, response.getFailedCount());
        assertEquals(3, response.getItems().size());
        assertEquals("SUBMITTED", response.getItems().get(0).getStatus());
        assertEquals(11L, response.getItems().get(0).getArticleId());
        assertEquals("SKIPPED_DUPLICATE", response.getItems().get(1).getStatus());
        assertEquals("FAILED", response.getItems().get(2).getStatus());
        assertEquals("抓取失败", response.getItems().get(2).getMessage());
    }
}
