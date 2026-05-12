package com.alibaba.cloud.ai.reader.csdn;

import org.jsoup.nodes.Element;

import java.util.List;

public class CsdnContentSelector {
    private static final List<String> CONTENT_SELECTORS = List.of(
            "main #content_views",
            "article #content_views",
            "#content_views",
            ".blog-content-box",
            ".article_content",
            "article"
    );

    public Element findContentElement(org.jsoup.nodes.Document page) {
        for (String selector : CONTENT_SELECTORS) {
            Element element = page.selectFirst(selector);
            if (element != null && !normalizeWhitespace(element.text()).isBlank()) {
                return element;
            }
        }
        return null;
    }

    private String normalizeWhitespace(String value) {
        return value == null ? "" : value.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }
}
