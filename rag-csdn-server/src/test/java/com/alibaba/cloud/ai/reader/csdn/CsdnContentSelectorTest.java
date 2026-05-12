package com.alibaba.cloud.ai.reader.csdn;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CsdnContentSelectorTest {

    @Test
    void findContentElement_shouldPreferStructuredSelectorsInOrder() {
        org.jsoup.nodes.Document page = Jsoup.parse("""
                <html><body><article><div id="content_views">正文</div></article></body></html>
                """);
        CsdnContentSelector selector = new CsdnContentSelector();

        Element content = selector.findContentElement(page);

        assertThat(content).isNotNull();
        assertThat(content.id()).isEqualTo("content_views");
    }
}
