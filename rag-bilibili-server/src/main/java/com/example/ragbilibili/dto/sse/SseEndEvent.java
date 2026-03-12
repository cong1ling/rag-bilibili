package com.example.ragbilibili.dto.sse;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SSE End事件
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SseEndEvent {
    private String type = "end";
    private Long assistantMessageId;
    private String fullContent;

    public SseEndEvent(Long assistantMessageId, String fullContent) {
        this.assistantMessageId = assistantMessageId;
        this.fullContent = fullContent;
    }
}
