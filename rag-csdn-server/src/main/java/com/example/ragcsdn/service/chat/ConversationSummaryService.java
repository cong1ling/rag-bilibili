package com.example.ragcsdn.service.chat;

import com.example.ragcsdn.config.ChatOptimizationProperties;
import com.example.ragcsdn.entity.Message;
import com.example.ragcsdn.mapper.MessageMapper;
import com.example.ragcsdn.mapper.SessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ConversationSummaryService {

    private static final Logger log = LoggerFactory.getLogger(ConversationSummaryService.class);

    private final ChatClient.Builder chatClientBuilder;
    private final MessageMapper messageMapper;
    private final SessionMapper sessionMapper;
    private final ConversationMemoryService conversationMemoryService;
    private final int summaryTriggerMessages;
    private final int summaryRecentMessages;
    private final int summaryMaxLength;
    private final boolean summaryEnabled;

    public ConversationSummaryService(ChatClient.Builder chatClientBuilder,
                                      MessageMapper messageMapper,
                                      SessionMapper sessionMapper,
                                      ConversationMemoryService conversationMemoryService,
                                      ChatOptimizationProperties properties) {
        this(chatClientBuilder, messageMapper, sessionMapper, conversationMemoryService,
                properties == null || properties.getSummaryTriggerMessages() == null ? 10 : properties.getSummaryTriggerMessages(),
                properties == null || properties.getSummaryRecentMessages() == null ? 6 : properties.getSummaryRecentMessages(),
                properties == null || properties.getSummaryMaxLength() == null ? 150 : properties.getSummaryMaxLength(),
                properties == null || !Boolean.FALSE.equals(properties.getSummaryEnabled()));
    }

    ConversationSummaryService(ChatClient.Builder chatClientBuilder,
                               MessageMapper messageMapper,
                               SessionMapper sessionMapper,
                               ConversationMemoryService conversationMemoryService,
                               int summaryTriggerMessages,
                               int summaryRecentMessages,
                               int summaryMaxLength,
                               boolean summaryEnabled) {
        this.chatClientBuilder = chatClientBuilder;
        this.messageMapper = messageMapper;
        this.sessionMapper = sessionMapper;
        this.conversationMemoryService = conversationMemoryService;
        this.summaryTriggerMessages = summaryTriggerMessages;
        this.summaryRecentMessages = summaryRecentMessages;
        this.summaryMaxLength = summaryMaxLength;
        this.summaryEnabled = summaryEnabled;
    }

    public String summarize(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }

        try {
            String transcript = messages.stream()
                    .map(message -> message.getRole() + "：" + message.getContent())
                    .collect(Collectors.joining("\n"));
            String summary = chatClientBuilder.build().prompt()
                    .system(ChatPromptTemplates.SUMMARY_SYSTEM_PROMPT)
                    .user(transcript)
                    .call()
                    .content();
            return conversationMemoryService.normalizeConversationSummary(summary, messages, summaryMaxLength);
        } catch (Exception e) {
            log.warn("对话摘要生成失败，回退到规则摘要", e);
            return conversationMemoryService.normalizeConversationSummary(null, messages, summaryMaxLength);
        }
    }

    public void refreshAndPersist(Long sessionId) {
        if (!summaryEnabled) {
            return;
        }

        List<Message> messages = messageMapper.selectBySessionId(sessionId).stream()
                .sorted(Comparator.comparing(Message::getCreateTime))
                .collect(Collectors.toList());

        if (messages.size() <= summaryTriggerMessages) {
            sessionMapper.updateSummary(sessionId, null, null);
            return;
        }

        int recentCount = Math.min(summaryRecentMessages, messages.size());
        List<Message> olderMessages = messages.subList(0, messages.size() - recentCount);
        String summary = summarize(olderMessages);
        sessionMapper.updateSummary(sessionId, summary, LocalDateTime.now());
    }
}
