package com.example.ragcsdn.service.article;

import com.example.ragcsdn.dto.response.ArticleResponse;
import com.example.ragcsdn.entity.Article;
import com.example.ragcsdn.enums.ArticleStatus;
import com.example.ragcsdn.mapper.ArticleMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArticleImportCommandServiceTest {

    @Mock
    private ArticleMapper articleMapper;

    @Mock
    private ArticleResponseAssembler articleResponseAssembler;

    @Mock
    private TaskExecutor articleImportTaskExecutor;

    @InjectMocks
    private ArticleImportCommandService service;

    @Test
    void submitNewImport_shouldCreateImportingRecordAndScheduleAsyncTask() {
        doAnswer(invocation -> {
            Article article = invocation.getArgument(0);
            article.setId(101L);
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

        ArticleResponse response = service.submitNewImport(
                1L,
                "147000001",
                "https://blog.csdn.net/test_author/article/details/147000001",
                () -> {
                }
        );

        ArgumentCaptor<Article> articleCaptor = ArgumentCaptor.forClass(Article.class);
        verify(articleMapper).insert(articleCaptor.capture());
        Article inserted = articleCaptor.getValue();

        assertThat(inserted.getStatus()).isEqualTo(ArticleStatus.IMPORTING.getCode());
        assertThat(inserted.getTitle()).isEqualTo("导入中...");
        assertThat(inserted.getSourceId()).isEqualTo("147000001");
        assertThat(response.getId()).isEqualTo(101L);
        verify(articleImportTaskExecutor).execute(any(Runnable.class));
    }

    @Test
    void retryFailedImport_shouldResetFailedStateAndScheduleAsyncTask() {
        Article failed = new Article();
        failed.setId(100L);
        failed.setSourceId("147000001");
        failed.setStatus(ArticleStatus.FAILED.getCode());
        failed.setFailReason("timeout");
        failed.setTitle("");

        when(articleResponseAssembler.toResponse(failed)).thenAnswer(invocation -> {
            Article article = invocation.getArgument(0);
            ArticleResponse response = new ArticleResponse();
            response.setId(article.getId());
            response.setStatus(article.getStatus());
            return response;
        });
        doNothing().when(articleImportTaskExecutor).execute(any(Runnable.class));

        ArticleResponse response = service.retryFailedImport(
                failed,
                "https://blog.csdn.net/test_author/article/details/147000001",
                () -> {
                }
        );

        assertThat(failed.getStatus()).isEqualTo(ArticleStatus.IMPORTING.getCode());
        assertThat(failed.getFailReason()).isNull();
        assertThat(failed.getTitle()).isEqualTo("重新导入中...");
        assertThat(response.getId()).isEqualTo(100L);
        verify(articleMapper).update(failed);
        verify(articleImportTaskExecutor).execute(any(Runnable.class));
    }
}
