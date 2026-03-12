package com.example.ragbilibili.dto.sse;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SSE Error事件
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SseErrorEvent {
    private String type = "error";
    private String message;

    public SseErrorEvent(String message) {
        this.message = message;
    }
}
