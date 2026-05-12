package com.example.ragcsdn.service.impl;

import com.example.ragcsdn.dto.request.ImportArticleRequest;
import com.example.ragcsdn.dto.request.RebuildArticleRequest;
import com.example.ragcsdn.dto.response.ArticleResponse;
import com.example.ragcsdn.entity.Article;
import com.example.ragcsdn.enums.ArticleStatus;
import com.example.ragcsdn.exception.BusinessException;
import com.example.ragcsdn.mapper.ArticleMapper;
import com.example.ragcsdn.mapper.ChunkMapper;
import com.example.ragcsdn.mapper.MessageMapper;
import com.example.ragcsdn.mapper.SessionMapper;
import com.example.ragcsdn.mapper.VectorMappingMapper;
import com.example.ragcsdn.service.UserService;
import com.example.ragcsdn.service.article.ArticleImportFailureHandler;
import com.example.ragcsdn.service.article.ArticleResponseAssembler;
import com.example.ragcsdn.service.article.BatchImportResponseAssembler;
import com.example.ragcsdn.util.ChunkDocumentSplitter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import com.alibaba.cloud.ai.vectorstore.dashvector.DashVectorStore;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArticleServiceImplTest {

    @Mock
    private ArticleMapper articleMapper;

    @Mock
    private ChunkMapper chunkMapper;

    @Mock
    private VectorMappingMapper vectorMappingMapper;

    @Mock
    private SessionMapper sessionMapper;

    @Mock
    private MessageMapper messageMapper;

    @Mock
    private ChunkDocumentSplitter chunkDocumentSplitter;

    @Mock
    private DashVectorStore dashVectorStore;

    @Mock
    private UserService userService;

    @Mock
    private ArticleResponseAssembler articleResponseAssembler;

    @Mock
    private BatchImportResponseAssembler batchImportResponseAssembler;

    @Mock
    private ArticleImportFailureHandler articleImportFailureHandler;

    @Mock
    private TaskExecutor articleImportTaskExecutor;

    @InjectMocks
    private ArticleServiceImpl articleService;

    /**
     * 验证同步阶段：importArticle() 应创建 IMPORTING 状态的文章记录并立即返回，
     * 实际导入工作被提交给 taskExecutor 异步执行。
     */
    @Test
    void importArticleShouldCreateImportingRecordAndReturnImmediately() {
        ImportArticleRequest request = new ImportArticleRequest();
        request.setArticleUrl("https://blog.csdn.net/test_author/article/details/147000001");

        when(articleMapper.selectByUserIdAndSourceId(1L, "147000001")).thenReturn(null);

        doAnswer(invocation -> {
            Article article = invocation.getArgument(0);
            article.setId(100L);
            return 1;
        }).when(articleMapper).insert(any(Article.class));

        when(articleResponseAssembler.toResponse(any(Article.class))).thenAnswer(invocation -> {
            Article article = invocation.getArgument(0);
            ArticleResponse response = new ArticleResponse();
            response.setId(article.getId());
            response.setStatus(article.getStatus());
            return response;
        });
        doNothing().when(articleImportTaskExecutor).execute(any(Runnable.class));

        ArticleResponse response = articleService.importArticle(request, 1L);

        // 验证：创建了 IMPORTING 状态的文章记录
        ArgumentCaptor<Article> articleCaptor = ArgumentCaptor.forClass(Article.class);
        verify(articleMapper).insert(articleCaptor.capture());
        Article insertedArticle = articleCaptor.getValue();

        assertEquals("147000001", insertedArticle.getSourceId());
        assertEquals("https://blog.csdn.net/test_author/article/details/147000001", insertedArticle.getSourceUrl());
        assertEquals(ArticleStatus.IMPORTING.getCode(), insertedArticle.getStatus());
        assertEquals("导入中...", insertedArticle.getTitle());
        assertNotNull(response.getId());

        // 验证：异步任务已提交
        verify(articleImportTaskExecutor).execute(any(Runnable.class));
    }

    /**
     * 验证同步阶段的去重校验：当文章已存在时，应直接抛出 BusinessException，
     * 不创建新记录也不提交异步任务。
     */
    @Test
    void importArticleShouldRejectDuplicateArticle() {
        ImportArticleRequest request = new ImportArticleRequest();
        request.setArticleUrl("https://blog.csdn.net/test_author/article/details/147000001");

        Article existingArticle = new Article();
        existingArticle.setId(100L);
        existingArticle.setSourceId("147000001");
        when(articleMapper.selectByUserIdAndSourceId(1L, "147000001")).thenReturn(existingArticle);

        assertThrows(BusinessException.class, () -> articleService.importArticle(request, 1L));
    }

    @Test
    void importArticleShouldRetryFailedRecordInsteadOfReportingDuplicate() {
        ImportArticleRequest request = new ImportArticleRequest();
        request.setArticleUrl("https://blog.csdn.net/test_author/article/details/147000001");

        Article failedArticle = new Article();
        failedArticle.setId(100L);
        failedArticle.setUserId(1L);
        failedArticle.setSourceId("147000001");
        failedArticle.setSourceUrl("https://blog.csdn.net/test_author/article/details/147000001");
        failedArticle.setTitle("旧失败记录");
        failedArticle.setStatus(ArticleStatus.FAILED.getCode());
        failedArticle.setFailReason("deadline exceeded");

        when(articleMapper.selectByUserIdAndSourceId(1L, "147000001")).thenReturn(failedArticle);
        when(articleResponseAssembler.toResponse(any(Article.class))).thenAnswer(invocation -> {
            Article article = invocation.getArgument(0);
            ArticleResponse response = new ArticleResponse();
            response.setId(article.getId());
            response.setStatus(article.getStatus());
            return response;
        });
        doNothing().when(articleImportTaskExecutor).execute(any(Runnable.class));

        ArticleResponse response = articleService.importArticle(request, 1L);

        assertEquals(100L, response.getId());
        assertEquals(ArticleStatus.IMPORTING.getCode(), failedArticle.getStatus());
        assertEquals(null, failedArticle.getFailReason());
        verify(articleMapper).update(failedArticle);
        verify(articleImportTaskExecutor).execute(any(Runnable.class));
        verify(articleMapper, never()).insert(any(Article.class));
    }

    @Test
    void rebuildArticleShouldMarkRecordImportingAndSubmitAsyncTask() {
        RebuildArticleRequest request = new RebuildArticleRequest();

        Article article = new Article();
        article.setId(100L);
        article.setUserId(1L);
        article.setSourceId("147000001");
        article.setSourceUrl("https://blog.csdn.net/test_author/article/details/147000001");
        article.setTitle("原文章标题");
        article.setStatus(ArticleStatus.SUCCESS.getCode());

        when(articleMapper.selectById(100L)).thenReturn(article);
        when(articleResponseAssembler.toResponse(any(Article.class))).thenAnswer(invocation -> {
            Article current = invocation.getArgument(0);
            ArticleResponse response = new ArticleResponse();
            response.setId(current.getId());
            response.setStatus(current.getStatus());
            return response;
        });
        doNothing().when(articleImportTaskExecutor).execute(any(Runnable.class));

        ArticleResponse response = articleService.rebuildArticle(100L, request, 1L);

        assertEquals("IMPORTING", response.getStatus());
        verify(articleMapper).update(article);
        verify(articleImportTaskExecutor).execute(any(Runnable.class));
    }

    @Test
    void cleanupExistingIndexShouldDeleteVectorsMappingsAndChunks() throws Exception {
        Method cleanupExistingIndex = ArticleServiceImpl.class.getDeclaredMethod("cleanupExistingIndex", Long.class);
        cleanupExistingIndex.setAccessible(true);

        when(vectorMappingMapper.selectVectorIdsByArticleId(9L)).thenReturn(List.of("vec-1", "vec-2"));

        cleanupExistingIndex.invoke(articleService, 9L);

        var order = inOrder(vectorMappingMapper, dashVectorStore, chunkMapper);
        order.verify(vectorMappingMapper).selectVectorIdsByArticleId(9L);
        order.verify(dashVectorStore).delete(eq(List.of("vec-1", "vec-2")));
        order.verify(vectorMappingMapper).deleteByArticleId(9L);
        order.verify(chunkMapper).deleteByArticleId(9L);
    }
}
