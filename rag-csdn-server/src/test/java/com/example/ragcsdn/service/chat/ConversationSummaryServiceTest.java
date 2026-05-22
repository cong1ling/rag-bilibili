package com.example.ragcsdn.service.chat;

import com.example.ragcsdn.entity.Message;
import com.example.ragcsdn.enums.MessageRole;
import com.example.ragcsdn.mapper.MessageMapper;
import com.example.ragcsdn.mapper.SessionMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.ai.chat.client.ChatClient;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationSummaryServiceTest {

    @Test
    void summarize_shouldFallbackToNormalizedRuleSummaryWhenLlmFails() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class, Answers.RETURNS_DEEP_STUBS);
        when(builder.build().prompt().system(anyString()).user(anyString()).call().content())
                .thenThrow(new IllegalStateException("boom"));

        ConversationSummaryService service = new ConversationSummaryService(
                builder,
                mock(MessageMapper.class),
                mock(SessionMapper.class),
                new ConversationMemoryService(),
                2,
                2,
                150,
                true
        );

        String summary = service.summarize(List.of(
                msg(1L, MessageRole.USER.getCode(), "第一问"),
                msg(2L, MessageRole.ASSISTANT.getCode(), "第一答"),
                msg(3L, MessageRole.USER.getCode(), "第二问")
        ));

        assertThat(summary).contains("USER:第一问");
        assertThat(summary).contains("ASSISTANT:第一答");
        assertThat(summary).contains("USER:第二问");
    }

    @Test
    void refreshAndPersist_shouldClearSummaryWhenConversationBelowThreshold() {
        MessageMapper messageMapper = mock(MessageMapper.class);
        SessionMapper sessionMapper = mock(SessionMapper.class);
        when(messageMapper.selectBySessionId(99L))
                .thenReturn(List.of(msg(1L, MessageRole.USER.getCode(), "单条消息")));

        ConversationSummaryService service = new ConversationSummaryService(
                mock(ChatClient.Builder.class, Answers.RETURNS_DEEP_STUBS),
                messageMapper,
                sessionMapper,
                new ConversationMemoryService(),
                2,
                2,
                150,
                true
        );

        service.refreshAndPersist(99L);

        verify(sessionMapper).updateSummary(99L, null, null);
    }

    private Message msg(Long id, String role, String content) {
        Message message = new Message();
        message.setId(id);
        message.setRole(role);
        message.setContent(content);
        message.setCreateTime(LocalDateTime.now().plusSeconds(id));
        return message;
    }
}
