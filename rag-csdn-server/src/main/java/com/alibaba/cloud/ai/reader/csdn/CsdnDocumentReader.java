package com.alibaba.cloud.ai.reader.csdn;

import com.example.ragcsdn.cleaning.StructuredArticleCleaner;
import com.example.ragcsdn.cleaning.model.CleaningBlock;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class CsdnDocumentReader implements DocumentReader {
    private static final int MAX_FETCH_ATTEMPTS = 3;
    private static final long BASE_RETRY_DELAY_MILLIS = 400L;
    private static final Pattern METRIC_ONLY_LINE = Pattern.compile(
            "^(点赞数?|点赞|评论数?|评论|浏览量|阅读量|浏览|阅读|收藏数?|收藏|转发数?|转发|分享数?|分享)"
                    + "(?:[：:：\\s-]*[0-9a-zA-Z.,万wW+kK]+)?$");
    private static final Pattern SHARE_LINE = Pattern.compile("^(分享至.*|分享到.*|微信扫码.*|扫码.*)$");
    private static final Pattern DIRECTORY_LINE = Pattern.compile("^(目录|文章目录|目录\\s*收起|目录\\s*展开)$");

    private static final Logger log = LoggerFactory.getLogger(CsdnDocumentReader.class);

    private final CsdnResource csdnResource;
    private final List<CsdnResource> csdnResourceList;
    private final HttpClient httpClient;
    private final String cookieHeader;
    private final StructuredArticleCleaner structuredArticleCleaner;

    public CsdnDocumentReader(CsdnResource csdnResource) {
        this(csdnResource, null);
    }

    public CsdnDocumentReader(CsdnResource csdnResource, String cookieHeader) {
        this.csdnResource = csdnResource;
        this.csdnResourceList = null;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        this.cookieHeader = cookieHeader;
        this.structuredArticleCleaner = new StructuredArticleCleaner();
    }

    public CsdnDocumentReader(List<CsdnResource> csdnResourceList) {
        this(csdnResourceList, null);
    }

    public CsdnDocumentReader(List<CsdnResource> csdnResourceList, String cookieHeader) {
        this.csdnResource = null;
        this.csdnResourceList = csdnResourceList;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        this.cookieHeader = cookieHeader;
        this.structuredArticleCleaner = new StructuredArticleCleaner();
    }

    @Override
    public List<Document> get() {
        List<CsdnResource> resources = this.csdnResourceList;
        if (resources == null) {
            resources = List.of(this.csdnResource);
        }

        List<Document> documents = new ArrayList<>();
        for (CsdnResource resource : resources) {
            documents.addAll(readResource(resource));
        }
        return documents;
    }

    private List<Document> readResource(CsdnResource resource) {
        try {
            String html = sendGet(resource.getArticleUrl());
            return parseDocuments(resource, html);
        } catch (Exception ex) {
            log.error("Failed to read CSDN article: {}", resource.getArticleUrl(), ex);
            throw new RuntimeException(firstNonBlank(ex.getMessage(), "Failed to read CSDN article: " + resource.getArticleUrl()), ex);
        }
    }

    List<Document> parseDocuments(CsdnResource resource, String html) {
        org.jsoup.nodes.Document page = Jsoup.parse(html, resource.getArticleUrl());

        String title = firstNonBlank(
                attr(page, "meta[property=og:title]", "content"),
                attr(page, "meta[name=title]", "content"),
                text(page, "h1.title-article"),
                text(page, "h1"));

        Element contentElement = findContentElement(page);
        String accessBlockedReason = detectAccessBlocked(page, contentElement);
        if (!accessBlockedReason.isBlank()) {
            throw new IllegalStateException(accessBlockedReason);
        }
        if (contentElement == null) {
            throw new IllegalStateException("文章正文为空或无法提取有效内容");
        }

        Element sanitizedContent = contentElement.clone();
        sanitizedContent.select(
                "script,style,noscript,button,svg,aside,.passport-login-container,.tool-box," +
                        ".recommend-box,.hide-article-box,.blog_extension_box,.article-copyright").remove();

        String content = extractCleanContent(sanitizedContent);
        if (content.isBlank()) {
            throw new IllegalStateException("文章正文为空或无法提取有效内容");
        }

        String description = firstNonBlank(
                attr(page, "meta[name=description]", "content"),
                attr(page, "meta[property=og:description]", "content"),
                summarize(content, 160));
        String author = firstNonBlank(
                attr(page, "meta[name=author]", "content"),
                text(page, ".follow-nickName"),
                text(page, ".article-title-box + .article-info-box a"));
        String canonicalUrl = firstNonBlank(
                attr(page, "link[rel=canonical]", "href"),
                resource.getArticleUrl());

        String documentText = String.format(
                "Article Title: %s%nContent:%n%s",
                title,
                content);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("sourceId", resource.getArticleId());
        metadata.put("sourceUrl", canonicalUrl);
        metadata.put("document_type", "content");
        metadata.put("title", title);
        metadata.put("description", description);
        metadata.put("author", author);
        return List.of(new Document(documentText, metadata));
    }

    private String sendGet(String url) throws IOException, InterruptedException {
        IOException lastException = null;

        for (int attempt = 1; attempt <= MAX_FETCH_ATTEMPTS; attempt++) {
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                        .GET()
                        .timeout(Duration.ofSeconds(30))
                        .header("Accept", "text/html,application/xhtml+xml")
                        .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                        .header("User-Agent", userAgent());
                if (cookieHeader != null && !cookieHeader.isBlank()) {
                    request.header("Cookie", cookieHeader);
                }

                HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return response.body();
                }

                String message = buildHttpStatusErrorMessage(response.statusCode(), url, attempt);
                if (!isRetryableHttpStatus(response.statusCode()) || attempt == MAX_FETCH_ATTEMPTS) {
                    throw new IOException(message);
                }

                lastException = new IOException(message);
                log.warn("CSDN article fetch returned retryable status {} on attempt {}/{}: {}",
                        response.statusCode(), attempt, MAX_FETCH_ATTEMPTS, url);
            } catch (IOException ex) {
                lastException = ex;
                if (attempt == MAX_FETCH_ATTEMPTS || !isRetryableTransportError(ex)) {
                    throw ex;
                }
                log.warn("CSDN article fetch failed on attempt {}/{}: {}", attempt, MAX_FETCH_ATTEMPTS, ex.getMessage());
            }

            sleepBeforeRetry(attempt);
        }

        if (lastException != null) {
            throw lastException;
        }
        throw new IOException("Failed to fetch CSDN article: " + url);
    }

    boolean isRetryableHttpStatus(int statusCode) {
        return switch (statusCode) {
            case 408, 429, 500, 502, 503, 504, 521, 522, 523, 524 -> true;
            default -> false;
        };
    }

    String buildHttpStatusErrorMessage(int statusCode, String url, int attempt) {
        if (statusCode == 521 || statusCode == 522 || statusCode == 523 || statusCode == 524) {
            return "HTTP " + statusCode + " for url: " + url
                    + "。CSDN 当前网络波动或边缘节点暂时不可用，已尝试第" + attempt + "次抓取";
        }
        return "HTTP " + statusCode + " for url: " + url;
    }

    private boolean isRetryableTransportError(IOException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase();
        return normalized.contains("http 408")
                || normalized.contains("http 429")
                || normalized.contains("http 500")
                || normalized.contains("http 502")
                || normalized.contains("http 503")
                || normalized.contains("http 504")
                || normalized.contains("http 521")
                || normalized.contains("http 522")
                || normalized.contains("http 523")
                || normalized.contains("http 524")
                || normalized.contains("timed out")
                || normalized.contains("connection reset")
                || normalized.contains("connection refused");
    }

    private void sleepBeforeRetry(int attempt) throws InterruptedException {
        try {
            Thread.sleep(BASE_RETRY_DELAY_MILLIS * attempt);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw ex;
        }
    }

    private Element findContentElement(org.jsoup.nodes.Document page) {
        for (String selector : List.of(
                "main #content_views",
                "article #content_views",
                "#content_views",
                ".blog-content-box",
                ".article_content",
                "article")) {
            Element element = page.selectFirst(selector);
            if (element != null && !normalizeWhitespace(element.text()).isBlank()) {
                return element;
            }
        }
        return null;
    }

    private String detectAccessBlocked(org.jsoup.nodes.Document page, Element contentElement) {
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

    private String extractCleanContent(Element contentElement) {
        List<CleaningBlock> blocks = structuredArticleCleaner.extractBlocks(contentElement.outerHtml());
        String structuredContent = buildStructuredContent(blocks);
        if (!structuredContent.isBlank()) {
            return structuredContent;
        }

        Set<String> segments = new LinkedHashSet<>();
        for (Element element : contentElement.select("p,li,pre,code,blockquote,h2,h3,h4,h5,h6,tr")) {
            appendMeaningfulSegment(segments, element.text());
        }

        if (segments.isEmpty()) {
            String wholeText = contentElement.wholeText();
            if (wholeText != null && !wholeText.isBlank()) {
                for (String line : wholeText.split("\\R+")) {
                    appendMeaningfulSegment(segments, line);
                }
            }
        }

        if (segments.isEmpty()) {
            appendMeaningfulSegment(segments, contentElement.text());
        }

        return String.join("\n", segments);
    }

    private String buildStructuredContent(List<CleaningBlock> blocks) {
        List<String> segments = new ArrayList<>();
        for (CleaningBlock block : blocks) {
            String segment = switch (block.type()) {
                case NOISE -> "";
                case HEADING -> formatHeading(block);
                case CODE -> preserveCodeWhitespace(block.content());
                default -> normalizeWhitespace(block.content());
            };
            if (segment.isBlank() || isNoiseLine(segment)) {
                continue;
            }
            if (!segments.contains(segment)) {
                segments.add(segment);
            }
        }
        return String.join("\n", segments);
    }

    private String formatHeading(CleaningBlock block) {
        int level = Math.max(2, block.level());
        return "#".repeat(level) + " " + normalizeWhitespace(block.content());
    }

    private String preserveCodeWhitespace(String value) {
        String normalized = value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        List<String> keptLines = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank() && keptLines.isEmpty()) {
                continue;
            }
            keptLines.add(line.replace('\u00A0', ' '));
        }
        while (!keptLines.isEmpty() && keptLines.get(keptLines.size() - 1).isBlank()) {
            keptLines.remove(keptLines.size() - 1);
        }
        return String.join("\n", keptLines);
    }

    private void appendMeaningfulSegment(Set<String> segments, String rawText) {
        String text = normalizeWhitespace(rawText);
        if (text.isBlank() || isNoiseLine(text)) {
            return;
        }
        segments.add(text);
    }

    private boolean isNoiseLine(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        if (METRIC_ONLY_LINE.matcher(text).matches()) {
            return true;
        }
        if (SHARE_LINE.matcher(text).matches()) {
            return true;
        }
        if (DIRECTORY_LINE.matcher(text).matches()) {
            return true;
        }
        return text.startsWith("作者：")
                || text.startsWith("作者:")
                || text.startsWith("博主：")
                || text.startsWith("博主:")
                || text.startsWith("分类专栏：")
                || text.startsWith("分类专栏:")
                || text.startsWith("版权声明：")
                || text.startsWith("版权声明:")
                || text.startsWith("文章标签：")
                || text.startsWith("文章标签:");
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private String text(org.jsoup.nodes.Document page, String selector) {
        Element element = page.selectFirst(selector);
        return element == null ? "" : normalizeWhitespace(element.text());
    }

    private String attr(org.jsoup.nodes.Document page, String selector, String attribute) {
        Element element = page.selectFirst(selector);
        return element == null ? "" : normalizeWhitespace(element.attr(attribute));
    }

    private String summarize(String content, int maxLength) {
        if (content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength).trim() + "...";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String normalizeWhitespace(String value) {
        return value == null ? "" : value.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }

    String userAgent() {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36";
    }
}
