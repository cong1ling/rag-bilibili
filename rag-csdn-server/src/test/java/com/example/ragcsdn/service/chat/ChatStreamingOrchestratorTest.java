package com.example.ragcsdn.service.chat;

import com.example.ragcsdn.dto.sse.SseEndEvent;
import com.example.ragcsdn.dto.sse.SseErrorEvent;
import com.example.ragcsdn.entity.Message;
import com.example.ragcsdn.mapper.MessageMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatStreamingOrchestratorTest {

    @Test
    void stream_shouldSendStartContentAndEndAndPersistAssistantMessage() throws Exception {
        ChatClient.Builder builder = mock(ChatClient.Builder.class, Answers.RETURNS_DEEP_STUBS);
        when(builder.build().prompt().system(anyString()).messages(anyList()).user(anyString()).stream().chatResponse())
                .thenReturn(Flux.just(chatResponse("第一段"), chatResponse("第二段")));

        MessageMapper messageMapper = mock(MessageMapper.class);
        doAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            message.setId(22L);
            return 1;
        }).when(messageMapper).insert(any(Message.class));

        ConversationSummaryService summaryService = mock(ConversationSummaryService.class);
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        ObjectMapper objectMapper = new ObjectMapper();

        ChatStreamingOrchestrator orchestrator = new ChatStreamingOrchestrator(
                builder,
                messageMapper,
                objectMapper,
                new SyncTaskExecutor(),
                summaryService
        );

        orchestrator.stream(new ChatStreamingOrchestrator.StreamRequest(
                emitter,
                7L,
                9L,
                11L,
                "用户问题",
                "system prompt",
                List.of(),
                new QueryExpansionService.QueryPlan(
                        QueryExpansionService.QueryIntent.DIRECT,
                        "用户问题",
                        "改写后问题",
                        List.of(new QueryExpansionService.RetrievalQuery("改写后问题", "改写后问题", "direct"))
                ),
                new ResponseConfidenceService.ResponseConfidence("HIGH", 0.91d, false),
                List.of(),
                false
        ));

        assertThat(emitter.eventNames()).containsExactly("start", "content", "content", "end");
        verify(messageMapper).insert(any(Message.class));
        verify(summaryService).refreshAndPersist(7L);

        SseEndEvent endEvent = objectMapper.readValue(emitter.lastPayload(), SseEndEvent.class);
        assertThat(endEvent.getAssistantMessageId()).isEqualTo(22L);
        assertThat(endEvent.getFullContent()).isEqualTo("第一段第二段");
        assertThat(endEvent.getRewrittenQuery()).isEqualTo("改写后问题");
        assertThat(endEvent.getConfidenceLabel()).isEqualTo("HIGH");
        assertThat(endEvent.getSummaryUsed()).isFalse();
    }

    @Test
    void stream_shouldSendErrorEventWhenFluxFails() throws Exception {
        ChatClient.Builder builder = mock(ChatClient.Builder.class, Answers.RETURNS_DEEP_STUBS);
        when(builder.build().prompt().system(anyString()).messages(anyList()).user(anyString()).stream().chatResponse())
                .thenReturn(Flux.error(new IllegalStateException("boom")));

        MessageMapper messageMapper = mock(MessageMapper.class);
        ConversationSummaryService summaryService = mock(ConversationSummaryService.class);
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        ObjectMapper objectMapper = new ObjectMapper();

        ChatStreamingOrchestrator orchestrator = new ChatStreamingOrchestrator(
                builder,
                messageMapper,
                objectMapper,
                new SyncTaskExecutor(),
                summaryService
        );

        orchestrator.stream(new ChatStreamingOrchestrator.StreamRequest(
                emitter,
                7L,
                9L,
                11L,
                "用户问题",
                "system prompt",
                List.of(),
                new QueryExpansionService.QueryPlan(
                        QueryExpansionService.QueryIntent.DIRECT,
                        "用户问题",
                        "改写后问题",
                        List.of(new QueryExpansionService.RetrievalQuery("改写后问题", "改写后问题", "direct"))
                ),
                new ResponseConfidenceService.ResponseConfidence("HIGH", 0.91d, false),
                List.of(),
                false
        ));

        assertThat(emitter.eventNames()).containsExactly("start", "error");
        SseErrorEvent errorEvent = objectMapper.readValue(emitter.lastPayload(), SseErrorEvent.class);
        assertThat(errorEvent.getMessage()).isEqualTo("boom");
        verifyNoInteractions(summaryService);
        verifyNoInteractions(messageMapper);
    }

    private ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static final class RecordingSseEmitter extends SseEmitter {
        private final List<String> eventNames = new ArrayList<>();
        private final List<String> payloads = new ArrayList<>();

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            Set<ResponseBodyEmitter.DataWithMediaType> items = new LinkedHashSet<>(builder.build());
            String eventName = null;
            String payload = null;
            for (ResponseBodyEmitter.DataWithMediaType item : items) {
                Object data = item.getData();
                if (!(data instanceof String text)) {
                    continue;
                }
                if (text.startsWith("event:")) {
                    int lineBreak = text.indexOf('\n');
                    eventName = lineBreak >= 0
                            ? text.substring("event:".length(), lineBreak).trim()
                            : text.substring("event:".length()).trim();
                } else if (!text.isBlank()) {
                    payload = text;
                }
            }
            eventNames.add(eventName);
            payloads.add(payload);
        }

        @Override
        public synchronized void send(Object object, MediaType mediaType) {
        }

        List<String> eventNames() {
            return eventNames;
        }

        String lastPayload() {
            return payloads.get(payloads.size() - 1);
        }
    }
}
