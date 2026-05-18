package com.example.ragcsdn.service.chat;

import com.example.ragcsdn.config.ChatOptimizationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class QueryRewriteService {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriteService.class);

    private final ChatClient.Builder chatClientBuilder;
    private final QueryUnderstandingService queryUnderstandingService;
    private final boolean queryRewriteEnabled;

    public QueryRewriteService(ChatClient.Builder chatClientBuilder,
                               QueryUnderstandingService queryUnderstandingService,
                               ChatOptimizationProperties properties) {
        this(chatClientBuilder, queryUnderstandingService,
                properties == null || !Boolean.FALSE.equals(properties.getQueryRewriteEnabled()));
    }

    QueryRewriteService(ChatClient.Builder chatClientBuilder,
                        QueryUnderstandingService queryUnderstandingService,
                        boolean queryRewriteEnabled) {
        this.chatClientBuilder = chatClientBuilder;
        this.queryUnderstandingService = queryUnderstandingService;
        this.queryRewriteEnabled = queryRewriteEnabled;
    }

    public String rewrite(String query, List<Message> historyMessages, String memorySummary) {
        if (!queryRewriteEnabled || historyMessages == null || historyMessages.isEmpty()) {
            return query;
        }

        try {
            String rewritten = chatClientBuilder.build().prompt()
                    .system(ChatPromptTemplates.QUERY_REWRITE_SYSTEM_PROMPT
                            + queryUnderstandingService.buildSummaryPrompt(memorySummary))
                    .messages(historyMessages)
                    .user(query)
                    .call()
                    .content();

            String normalized = queryUnderstandingService.normalizeRewrittenQuery(query, rewritten);
            if (!normalized.equals(query)) {
                log.info("查询改写完成: original={}, rewritten={}", query, normalized);
            }
            return normalized;
        } catch (Exception e) {
            log.warn("查询改写失败，回退到原始问题: query={}", query, e);
            return query;
        }
    }
}
