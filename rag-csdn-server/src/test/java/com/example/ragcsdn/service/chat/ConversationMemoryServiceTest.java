package com.example.ragcsdn.service.chat;

import com.example.ragcsdn.entity.Message;
import com.example.ragcsdn.entity.Session;
import com.example.ragcsdn.enums.MessageRole;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationMemoryServiceTest {

    @Test
    void buildConversationMemory_shouldSummarizeOlderMessagesAndKeepRecentWindow() {
        ConversationMemoryService service = new ConversationMemoryService();
        Session session = new Session();
        session.setConversationSummary("旧摘要");
        List<Message> messages = List.of(
                msg(1L, MessageRole.USER.getCode(), "问题一", LocalDateTime.now().minusMinutes(3)),
                msg(2L, MessageRole.ASSISTANT.getCode(), "回答一", LocalDateTime.now().minusMinutes(2)),
                msg(3L, MessageRole.USER.getCode(), "问题二", LocalDateTime.now().minusMinutes(1))
        );

        ConversationMemoryService.ConversationMemory memory =
                service.buildConversationMemory(session, messages, null, 2, 2, 10, true, older -> "不应命中");

        assertThat(memory.summary()).isEqualTo("旧摘要");
        assertThat(memory.summaryUsed()).isTrue();
        assertThat(memory.recentMessages()).hasSize(2);
        assertThat(memory.recentMessages().get(0).getText()).isEqualTo("回答一");
        assertThat(memory.recentMessages().get(1).getText()).isEqualTo("问题二");
    }

    private Message msg(Long id, String role, String content, LocalDateTime time) {
        Message m = new Message();
        m.setId(id);
        m.setRole(role);
        m.setContent(content);
        m.setCreateTime(time);
        return m;
    }
}
