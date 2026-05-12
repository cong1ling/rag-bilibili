package com.alibaba.cloud.ai.reader.csdn;

import org.jsoup.nodes.Element;

public class CsdnAccessBlockDetector {

    public String detect(org.jsoup.nodes.Document page, Element contentElement) {
        String pageText = normalizeWhitespace(page.text()).toLowerCase();
        String title = normalizeWhitespace(page.title()).toLowerCase();
        boolean hasLoginGate = page.selectFirst(".passport-login-container,.passport-login-tip,.hljs-button.signin") != null;
        boolean hasVerifyText = containsAny(pageText,
                "访问校验",
                "请完成访问校验",
                "验证码",
                "安全验证",
                "登录后您可以享受更多权益",
                "登录后可查看全文");
        boolean looksBlocked = hasLoginGate || containsAny(title, "访问校验", "安全验证");

        if (looksBlocked || hasVerifyText) {
            return "CSDN返回了登录或访问校验页面，请稍后重试，或更新可用的 CSDN Cookie";
        }

        if (contentElement == null && containsAny(pageText, "阅读全文", "展开阅读全文", "登录后可查看")) {
            return "当前文章未返回可解析正文，可能需要有效的 CSDN Cookie 或稍后重试";
        }
        return "";
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private String normalizeWhitespace(String value) {
        return value == null ? "" : value.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }
}
