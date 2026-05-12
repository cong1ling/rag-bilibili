package com.example.ragcsdn.service.chat;

import com.example.ragcsdn.entity.Message;
import com.example.ragcsdn.entity.Session;
import com.example.ragcsdn.enums.MessageRole;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ConversationMemoryService {

    public record ConversationMemory(
            List<org.springframework.ai.chat.messages.Message> recentMessages,
            String summary,
            boolean summaryUsed
    ) {
    }

    public ConversationMemory buildConversationMemory(
            Session session,
            List<Message> messages,
            Long excludeId,
            int summaryTriggerMessages,
            int summaryRecentMessages,
            int maxHistory,
            boolean summaryEnabled,
            Function<List<Message>, String> summaryGenerator) {
        List<Message> filtered = messages.stream()
                .filter(m -> !m.getId().equals(excludeId))
                .sorted(Comparator.comparing(Message::getCreateTime))
                .collect(Collectors.toList());

        if (!summaryEnabled || filtered.size() <= summaryTriggerMessages) {
            return new ConversationMemory(buildMessageHistory(filtered, null, maxHistory), null, false);
        }

        int recentCount = Math.min(summaryRecentMessages, filtered.size());
        List<Message> olderMessages = filtered.subList(0, filtered.size() - recentCount);
        List<Message> recentMessages = filtered.subList(filtered.size() - recentCount, filtered.size());
        String summary = session == null ? null : session.getConversationSummary();
        if (summary == null || summary.isBlank()) {
            summary = summaryGenerator.apply(olderMessages);
        }

        return new ConversationMemory(
                buildMessageHistory(recentMessages, null, maxHistory),
                summary,
                summary != null && !summary.isBlank()
        );
    }

    public List<org.springframework.ai.chat.messages.Message> buildMessageHistory(
            List<Message> messages, Long excludeId, int maxHistory) {
        List<Message> filtered = messages.stream()
                .filter(m -> !m.getId().equals(excludeId))
                .sorted(Comparator.comparing(Message::getCreateTime))
                .collect(Collectors.toList());

        int start = Math.max(0, filtered.size() - maxHistory);
        return filtered.subList(start, filtered.size()).stream()
                .map(m -> m.getRole().equals(MessageRole.USER.getCode())
                        ? new UserMessage(m.getContent())
                        : new AssistantMessage(m.getContent()))
                .collect(Collectors.toList());
    }

    public String normalizeConversationSummary(String summary, List<Message> messages, int summaryMaxLength) {
        if (summary != null && !summary.isBlank()) {
            String normalized = summary.trim().replaceAll("\\s+", " ");
            return normalized.length() <= summaryMaxLength
                    ? normalized
                    : normalized.substring(0, summaryMaxLength);
        }

        String fallback = messages.stream()
                .skip(Math.max(0, messages.size() - 4))
                .map(message -> message.getRole() + ":" + message.getContent())
                .collect(Collectors.joining("；"));
        if (fallback.length() <= summaryMaxLength) {
            return fallback;
        }
        return fallback.substring(0, summaryMaxLength);
    }
}
