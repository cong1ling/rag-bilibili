package com.alibaba.cloud.ai.reader.csdn;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CsdnHttpFetcherTest {

    @Test
    void isRetryableHttpStatus_shouldReadFromFetchPolicy() {
        CsdnFetchPolicy policy = CsdnFetchPolicy.defaultPolicy();
        CsdnHttpFetcher fetcher = new CsdnHttpFetcher(null, policy);

        assertThat(policy.retryableStatusCodes()).contains(408, 429, 503, 524);
        assertThat(fetcher.isRetryableHttpStatus(521)).isTrue();
        assertThat(fetcher.isRetryableHttpStatus(404)).isFalse();
    }
}
