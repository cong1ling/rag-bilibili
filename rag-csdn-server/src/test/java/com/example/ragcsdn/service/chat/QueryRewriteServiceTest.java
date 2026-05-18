package com.example.ragcsdn.service.chat;

import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryRewriteServiceTest {

    @Test
    void rewrite_shouldReturnOriginalWhenFeatureDisabled() {
        QueryUnderstandingService understandingService = new QueryUnderstandingService(null);
        QueryRewriteService service = new QueryRewriteService(null, understandingService, false);

        String rewritten = service.rewrite("它的优点呢", List.of(new UserMessage("Spring Boot")), "旧摘要");

        assertThat(rewritten).isEqualTo("它的优点呢");
    }

    @Test
    void rewrite_shouldNormalizeSuccessfulResultAndIncludeSummaryPrompt() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class, Answers.RETURNS_DEEP_STUBS);
        when(builder.build().prompt().system(anyString()).messages(anyList()).user(eq("它的优点呢")).call().content())
                .thenReturn("Rewrite Query: Spring Boot 的优点是什么");

        QueryRewriteService service = new QueryRewriteService(
                builder,
                new QueryUnderstandingService(null),
                true
        );
        clearInvocations(builder, builder.build(), builder.build().prompt());

        String rewritten = service.rewrite("它的优点呢", List.of(new UserMessage("Spring Boot")), "旧摘要");

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(builder.build().prompt()).system(systemPrompt.capture());
        assertThat(systemPrompt.getValue()).contains("旧摘要");
        assertThat(rewritten).isEqualTo("Spring Boot 的优点是什么");
    }

    @Test
    void rewrite_shouldFallbackToOriginalWhenLlmThrows() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class, Answers.RETURNS_DEEP_STUBS);
        when(builder.build().prompt().system(anyString()).messages(anyList()).user(eq("它的优点呢")).call().content())
                .thenThrow(new IllegalStateException("boom"));

        QueryRewriteService service = new QueryRewriteService(
                builder,
                new QueryUnderstandingService(null),
                true
        );

        String rewritten = service.rewrite("它的优点呢", List.of(new UserMessage("Spring Boot")), "会话摘要");

        assertThat(rewritten).isEqualTo("它的优点呢");
    }
}
