package com.example.ragcsdn.service.chat;

import com.example.ragcsdn.dto.sse.SseContentEvent;
import com.example.ragcsdn.dto.sse.SseEndEvent;
import com.example.ragcsdn.dto.sse.SseErrorEvent;
import com.example.ragcsdn.dto.sse.SseStartEvent;
import com.example.ragcsdn.entity.Message;
import com.example.ragcsdn.enums.MessageRole;
import com.example.ragcsdn.mapper.MessageMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ChatStreamingOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamingOrchestrator.class);

    public record StreamRequest(
            SseEmitter emitter,
            Long sessionId,
            Long userId,
            Long userMessageId,
            String userContent,
            String systemPrompt,
            List<org.springframework.ai.chat.messages.Message> memoryMessages,
            QueryExpansionService.QueryPlan queryPlan,
            ResponseConfidenceService.ResponseConfidence confidence,
            List<Document> relevantDocs,
            boolean summaryUsed
    ) {
    }

    private final ChatClient.Builder chatClientBuilder;
    private final MessageMapper messageMapper;
    private final ObjectMapper objectMapper;
    private final TaskExecutor taskExecutor;
    private final ConversationSummaryService conversationSummaryService;

    public ChatStreamingOrchestrator(ChatClient.Builder chatClientBuilder,
                                     MessageMapper messageMapper,
                                     ObjectMapper objectMapper,
                                     TaskExecutor taskExecutor,
                                     ConversationSummaryService conversationSummaryService) {
        this.chatClientBuilder = chatClientBuilder;
        this.messageMapper = messageMapper;
        this.objectMapper = objectMapper;
        this.taskExecutor = taskExecutor;
        this.conversationSummaryService = conversationSummaryService;
    }

    public void stream(StreamRequest request) {
        taskExecutor.execute(() -> {
            try {
                request.emitter().send(SseEmitter.event()
                        .name("start")
                        .data(objectMapper.writeValueAsString(new SseStartEvent(request.userMessageId()))));

                Flux<ChatResponse> responseFlux = chatClientBuilder.build().prompt()
                        .system(request.systemPrompt())
                        .messages(request.memoryMessages())
                        .user(request.userContent())
                        .stream()
                        .chatResponse();

                StringBuilder fullResponse = new StringBuilder();
                responseFlux.subscribe(
                        response -> sendChunk(request.emitter(), fullResponse, response),
                        error -> sendError(request.emitter(), error),
                        () -> completeSuccess(request, fullResponse.toString())
                );
            } catch (Exception e) {
                log.error("对话流处理失败: sessionId={}, userId={}", request.sessionId(), request.userId(), e);
                sendError(request.emitter(), e);
            }
        });
    }

    private void sendChunk(SseEmitter emitter, StringBuilder fullResponse, ChatResponse response) {
        String chunk = response.getResult().getOutput().getText();
        if (chunk == null || chunk.isEmpty()) {
            return;
        }

        fullResponse.append(chunk);
        try {
            emitter.send(SseEmitter.event()
                    .name("content")
                    .data(objectMapper.writeValueAsString(new SseContentEvent(chunk))));
        } catch (Exception e) {
            log.error("SSE发送失败", e);
            emitter.completeWithError(e);
        }
    }

    private void sendError(SseEmitter emitter, Throwable error) {
        log.error("LLM调用失败", error);
        try {
            SseErrorEvent errorEvent = new SseErrorEvent(
                    error.getMessage() != null ? error.getMessage() : "未知错误");
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(objectMapper.writeValueAsString(errorEvent)));
        } catch (Exception e) {
            log.error("发送错误事件失败", e);
        }
        emitter.completeWithError(error);
    }

    private void completeSuccess(StreamRequest request, String fullContent) {
        try {
            Message assistantMessage = new Message();
            assistantMessage.setSessionId(request.sessionId());
            assistantMessage.setRole(MessageRole.ASSISTANT.getCode());
            assistantMessage.setContent(fullContent);
            assistantMessage.setCreateTime(LocalDateTime.now());
            messageMapper.insert(assistantMessage);

            conversationSummaryService.refreshAndPersist(request.sessionId());

            SseEndEvent endEvent = new SseEndEvent(assistantMessage.getId(), fullContent);
            endEvent.setQueryIntent(request.queryPlan().intent().name());
            endEvent.setRewrittenQuery(request.queryPlan().rewrittenQuery());
            endEvent.setConfidenceLabel(request.confidence().label());
            endEvent.setConfidenceScore(request.confidence().score());
            endEvent.setSourceCount(request.relevantDocs().size());
            endEvent.setKnowledgeGap(request.confidence().knowledgeGap());
            endEvent.setSummaryUsed(request.summaryUsed());
            request.emitter().send(SseEmitter.event()
                    .name("end")
                    .data(objectMapper.writeValueAsString(endEvent)));

            request.emitter().complete();
            log.info("对话完成: sessionId={}, userId={}", request.sessionId(), request.userId());
        } catch (Exception e) {
            log.error("发送end事件失败", e);
            request.emitter().completeWithError(e);
        }
    }
}
