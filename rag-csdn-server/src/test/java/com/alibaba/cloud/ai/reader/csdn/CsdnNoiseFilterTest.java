package com.alibaba.cloud.ai.reader.csdn;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CsdnNoiseFilterTest {

    @Test
    void isNoiseLine_shouldRejectStandaloneMetricsAndShareLines() {
        CsdnNoiseFilter filter = new CsdnNoiseFilter();

        assertThat(filter.isNoiseLine("点赞数 12")).isTrue();
        assertThat(filter.isNoiseLine("分享至微信")).isTrue();
        assertThat(filter.isNoiseLine("这是正文")).isFalse();
    }
}
