package com.alibaba.cloud.ai.reader.csdn;

import java.time.Duration;
import java.util.Set;

public record CsdnFetchPolicy(
        int maxFetchAttempts,
        long baseRetryDelayMillis,
        Duration connectTimeout,
        Duration requestTimeout,
        Set<Integer> retryableStatusCodes
) {

    public static CsdnFetchPolicy defaultPolicy() {
        return new CsdnFetchPolicy(
                3,
                400L,
                Duration.ofSeconds(15),
                Duration.ofSeconds(30),
                Set.of(408, 429, 500, 502, 503, 504, 521, 522, 523, 524)
        );
    }
}
