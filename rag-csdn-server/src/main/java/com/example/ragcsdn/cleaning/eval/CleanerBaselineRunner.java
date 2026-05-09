package com.example.ragcsdn.cleaning.eval;

import com.example.ragcsdn.cleaning.StructuredArticleCleaner;
import com.example.ragcsdn.cleaning.model.CleaningBlock;
import com.example.ragcsdn.cleaning.model.CleaningBlockType;
import com.example.ragcsdn.config.ChunkingProperties;
import com.example.ragcsdn.util.ChunkDocumentSplitter;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public class CleanerBaselineRunner {

    private static final Pattern METRIC_ONLY_LINE = Pattern.compile(
            "^(点赞数?|点赞|评论数?|评论|浏览量|阅读量|浏览|阅读|收藏数?|收藏|转发数?|转发|分享数?|分享)"
                    + "(?:[：:：\\s-]*[0-9a-zA-Z.,万wW+kK]+)?$");
    private static final Pattern SHARE_LINE = Pattern.compile("^(分享至.*|分享到.*|微信扫码.*|扫码.*)$");
    private static final Pattern DIRECTORY_LINE = Pattern.compile("^(目录|文章目录|目录\\s*收起|目录\\s*展开)$");

    private final StructuredArticleCleaner structuredArticleCleaner = new StructuredArticleCleaner();
    private final ChunkDocumentSplitter chunkDocumentSplitter;

    public CleanerBaselineRunner() {
        ChunkingProperties properties = new ChunkingProperties();
        this.chunkDocumentSplitter = new ChunkDocumentSplitter(properties);
    }

    public List<CleaningRunArtifact> runBaseline(List<EvaluationArticleSample> samples) {
        List<CleaningRunArtifact> artifacts = new ArrayList<>();
        for (EvaluationArticleSample sample : samples) {
            String content = extractBaselineContent(sample.rawHtml());
            if (content.isBlank()) {
                continue;
            }
            String title = resolveTitle(sample);
            String documentText = formatDocumentText(title, content);
            List<String> chunks = chunkDocumentSplitter.split(List.of(new Document(documentText))).stream()
                    .map(Document::getText)
                    .toList();
            artifacts.add(new CleaningRunArtifact(
                    sample.articleId(),
                    sample.sourceUrl(),
                    title,
                    sample.sourceType(),
                    documentText,
                    chunks
            ));
        }
        return artifacts;
    }

    public List<CleaningRunArtifact> runOptimized(List<EvaluationArticleSample> samples) {
        List<CleaningRunArtifact> artifacts = new ArrayList<>();
        for (EvaluationArticleSample sample : samples) {
            List<CleaningBlock> blocks = structuredArticleCleaner.extractBlocks(sample.rawHtml());
            List<CleaningBlock> contentBlocks = blocks.stream()
                    .filter(block -> block.type() != CleaningBlockType.NOISE)
                    .toList();
            String content = buildStructuredContent(contentBlocks);
            if (content.isBlank()) {
                continue;
            }

            String title = resolveTitle(sample);
            String documentText = formatDocumentText(title, content);
            List<String> contentChunks = chunkDocumentSplitter.splitStructuredBlocks(contentBlocks);
            List<String> chunks = new ArrayList<>();
            if (contentChunks.isEmpty()) {
                chunks.add(documentText);
            } else {
                chunks.add(formatDocumentText(title, contentChunks.get(0)));
                for (int i = 1; i < contentChunks.size(); i++) {
                    chunks.add(contentChunks.get(i));
                }
            }
            artifacts.add(new CleaningRunArtifact(
                    sample.articleId(),
                    sample.sourceUrl(),
                    title,
                    sample.sourceType(),
                    documentText,
                    chunks
            ));
        }
        return artifacts;
    }

    private String resolveTitle(EvaluationArticleSample sample) {
        if (sample.title() != null && !sample.title().isBlank()) {
            return sample.title().trim();
        }
        org.jsoup.nodes.Document page = Jsoup.parse(sample.rawHtml(), sample.sourceUrl());
        String title = page.selectFirst("meta[property=og:title]") != null
                ? page.selectFirst("meta[property=og:title]").attr("content")
                : page.title();
        return title == null ? "" : title.trim();
    }

    private String extractBaselineContent(String html) {
        org.jsoup.nodes.Document page = Jsoup.parse(html);
        Element contentElement = findContentElement(page);
        if (contentElement == null) {
            return "";
        }

        Element sanitizedContent = contentElement.clone();
        sanitizedContent.select(
                "script,style,noscript,button,svg,aside,.passport-login-container,.tool-box," +
                        ".recommend-box,.hide-article-box,.blog_extension_box,.article-copyright").remove();

        Set<String> segments = new LinkedHashSet<>();
        for (Element element : sanitizedContent.select("p,li,pre,code,blockquote,h2,h3,h4,h5,h6,tr")) {
            appendMeaningfulSegment(segments, element.text());
        }

        if (segments.isEmpty()) {
            String wholeText = sanitizedContent.wholeText();
            if (wholeText != null && !wholeText.isBlank()) {
                for (String line : wholeText.split("\\R+")) {
                    appendMeaningfulSegment(segments, line);
                }
            }
        }

        if (segments.isEmpty()) {
            appendMeaningfulSegment(segments, sanitizedContent.text());
        }

        return String.join("\n", segments);
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

    private String buildStructuredContent(List<CleaningBlock> blocks) {
        List<String> segments = new ArrayList<>();
        for (CleaningBlock block : blocks) {
            String text = switch (block.type()) {
                case HEADING -> "#".repeat(Math.max(2, block.level())) + " " + normalizeWhitespace(block.content());
                case CODE -> preserveCodeWhitespace(block.content());
                default -> normalizeWhitespace(block.content());
            };
            if (!text.isBlank() && !segments.contains(text)) {
                segments.add(text);
            }
        }
        return String.join("\n", segments);
    }

    private String formatDocumentText(String title, String content) {
        return String.format("Article Title: %s%nContent:%n%s", title, content);
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

    private String normalizeWhitespace(String value) {
        return value == null ? "" : value.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }

    private String preserveCodeWhitespace(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\u00A0', ' ')
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .stripTrailing();
    }
}
