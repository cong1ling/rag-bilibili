package com.alibaba.cloud.ai.reader.csdn;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CsdnAccessBlockDetectorTest {

    @Test
    void detectShouldReturnBlockedReasonForLoginGatePage() {
        CsdnAccessBlockDetector detector = new CsdnAccessBlockDetector();

        org.jsoup.nodes.Document page = Jsoup.parse("""
                <html>
                  <head><title>访问校验</title></head>
                  <body>
                    <div class="passport-login-container">登录后您可以享受更多权益</div>
                    <div class="content-box">请完成访问校验后继续阅读</div>
                  </body>
                </html>
                """);

        String reason = detector.detect(page, null);

        assertThat(reason).contains("登录或访问校验");
    }
}
