package com.example.ragbilibili.dto.sse;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SSE Start事件
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SseStartEvent {
    private String type = "start";
    private Long userMessageId;

    public SseStartEvent(Long userMessageId) {
        this.userMessageId = userMessageId;
    }
}
