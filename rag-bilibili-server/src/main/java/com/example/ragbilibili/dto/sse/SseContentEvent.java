package com.example.ragbilibili.dto.sse;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SSE Content事件
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SseContentEvent {
    private String type = "content";
    private String delta;

    public SseContentEvent(String delta) {
        this.delta = delta;
    }
}
