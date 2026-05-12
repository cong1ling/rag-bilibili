package com.alibaba.cloud.ai.reader.csdn;

import com.example.ragcsdn.cleaning.StructuredArticleCleaner;
import com.example.ragcsdn.cleaning.model.CleaningBlock;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CsdnDocumentAssembler {
    private final StructuredArticleCleaner structuredArticleCleaner;
    private final CsdnAccessBlockDetector accessBlockDetector;
    private final CsdnDocumentMetadataBuilder metadataBuilder;
    private final CsdnContentSelector contentSelector;
    private final CsdnNoiseFilter noiseFilter;

    public CsdnDocumentAssembler() {
        this(
                new StructuredArticleCleaner(),
                new CsdnAccessBlockDetector(),
                new CsdnDocumentMetadataBuilder(),
                new CsdnContentSelector(),
                new CsdnNoiseFilter()
        );
    }

    CsdnDocumentAssembler(
            StructuredArticleCleaner structuredArticleCleaner,
            CsdnAccessBlockDetector accessBlockDetector,
            CsdnDocumentMetadataBuilder metadataBuilder,
            CsdnContentSelector contentSelector,
            CsdnNoiseFilter noiseFilter
    ) {
        this.structuredArticleCleaner = structuredArticleCleaner;
        this.accessBlockDetector = accessBlockDetector;
        this.metadataBuilder = metadataBuilder;
        this.contentSelector = contentSelector;
        this.noiseFilter = noiseFilter;
    }

    public List<Document> parseDocuments(CsdnResource resource, String html) {
        org.jsoup.nodes.Document page = Jsoup.parse(html, resource.getArticleUrl());

        String title = firstNonBlank(
                attr(page, "meta[property=og:title]", "content"),
                attr(page, "meta[name=title]", "content"),
                text(page, "h1.title-article"),
                text(page, "h1")
        );

        Element contentElement = contentSelector.findContentElement(page);
        String accessBlockedReason = accessBlockDetector.detect(page, contentElement);
        if (!accessBlockedReason.isBlank()) {
            throw new IllegalStateException(accessBlockedReason);
        }
        if (contentElement == null) {
            throw new IllegalStateException("文章正文为空或无法提取有效内容");
        }

        Element sanitizedContent = contentElement.clone();
        sanitizedContent.select(
                "script,style,noscript,button,svg,aside,.passport-login-container,.tool-box," +
                        ".recommend-box,.hide-article-box,.blog_extension_box,.article-copyright"
        ).remove();

        List<CleaningBlock> structuredBlocks = structuredArticleCleaner.extractBlocks(sanitizedContent.outerHtml());
        String content = extractCleanContent(sanitizedContent, structuredBlocks);
        if (content.isBlank()) {
            throw new IllegalStateException("文章正文为空或无法提取有效内容");
        }

        String description = firstNonBlank(
                attr(page, "meta[name=description]", "content"),
                attr(page, "meta[property=og:description]", "content"),
                summarize(content, 160)
        );
        String author = firstNonBlank(
                attr(page, "meta[name=author]", "content"),
                text(page, ".follow-nickName"),
                text(page, ".article-title-box + .article-info-box a")
        );
        String canonicalUrl = firstNonBlank(
                attr(page, "link[rel=canonical]", "href"),
                resource.getArticleUrl()
        );

        String documentText = String.format("Article Title: %s%nContent:%n%s", title, content);
        Map<String, Object> metadata = metadataBuilder.build(
                resource,
                canonicalUrl,
                title,
                description,
                author,
                structuredBlocks
        );
        return List.of(new Document(documentText, metadata));
    }

    private String extractCleanContent(Element contentElement, List<CleaningBlock> blocks) {
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
            if (segment.isBlank() || noiseFilter.isNoiseLine(segment)) {
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
        if (text.isBlank() || noiseFilter.isNoiseLine(text)) {
            return;
        }
        segments.add(text);
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
}
