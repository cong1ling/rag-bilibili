package com.example.ragcsdn.cleaning;

import com.example.ragcsdn.cleaning.model.CleaningBlock;
import com.example.ragcsdn.cleaning.model.CleaningBlockType;
import com.example.ragcsdn.cleaning.model.NoiseLabel;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;

public class StructuredArticleCleaner {

    private static final String AUTHOR_PREFIX = "作者";
    private static final String BLOGGER_PREFIX = "博主";
    private static final String CATEGORY_PREFIX = "分类专栏";
    private static final String COPYRIGHT_PREFIX = "版权声明";
    private static final String TAG_PREFIX = "文章标签";

    public List<CleaningBlock> extractBlocks(String html) {
        org.jsoup.nodes.Document document = Jsoup.parse(html);
        Element content = document.selectFirst("#content_views");
        List<CleaningBlock> blocks = new ArrayList<>();
        if (content == null) {
            return blocks;
        }

        for (Element element : content.children()) {
            String tag = element.tagName();
            if (tag.matches("h2|h3|h4|h5|h6")) {
                blocks.add(new CleaningBlock(CleaningBlockType.HEADING, element.text(), headingLevel(tag), NoiseLabel.NONE));
            } else if ("p".equals(tag)) {
                blocks.add(classifyParagraph(element.text()));
            } else if (tag.matches("ol|ul")) {
                for (Element item : element.select("> li")) {
                    blocks.add(new CleaningBlock(CleaningBlockType.LIST, item.text(), 0, NoiseLabel.NONE));
                }
            } else if ("li".equals(tag)) {
                blocks.add(new CleaningBlock(CleaningBlockType.LIST, element.text(), 0, NoiseLabel.NONE));
            } else if ("blockquote".equals(tag)) {
                blocks.add(new CleaningBlock(CleaningBlockType.BLOCKQUOTE, element.text(), 0, NoiseLabel.NONE));
            } else if ("tr".equals(tag)) {
                blocks.add(new CleaningBlock(CleaningBlockType.TABLE_ROW, element.text(), 0, NoiseLabel.NONE));
            } else if ("pre".equals(tag) || "code".equals(tag)) {
                String codeText = element.wholeText().isBlank() ? element.text() : element.wholeText();
                blocks.add(new CleaningBlock(CleaningBlockType.CODE, trimBlankLines(codeText), 0, NoiseLabel.NONE));
            }
        }
        return blocks;
    }

    private CleaningBlock classifyParagraph(String text) {
        if (text.matches("^(点赞数?|点赞|评论数?|评论|浏览量|阅读量|收藏数?|收藏).*$")) {
            return new CleaningBlock(CleaningBlockType.NOISE, text, 0, NoiseLabel.ENGAGEMENT_METRIC);
        }
        if (startsWithLabel(text, AUTHOR_PREFIX) || startsWithLabel(text, BLOGGER_PREFIX)) {
            return new CleaningBlock(CleaningBlockType.NOISE, text, 0, NoiseLabel.AUTHOR_INFO);
        }
        if (startsWithLabel(text, CATEGORY_PREFIX)) {
            return new CleaningBlock(CleaningBlockType.NOISE, text, 0, NoiseLabel.CATEGORY_INFO);
        }
        if (startsWithLabel(text, COPYRIGHT_PREFIX)) {
            return new CleaningBlock(CleaningBlockType.NOISE, text, 0, NoiseLabel.COPYRIGHT_NOTICE);
        }
        if (startsWithLabel(text, TAG_PREFIX)) {
            return new CleaningBlock(CleaningBlockType.NOISE, text, 0, NoiseLabel.PROMOTIONAL_COPY);
        }
        if (text.matches("^(分享至.*|分享到.*|微信扫码.*|扫码.*)$")) {
            return new CleaningBlock(CleaningBlockType.NOISE, text, 0, NoiseLabel.SHARE_PROMPT);
        }
        if (text.matches("^(目录|文章目录|目录\\s*收起|目录\\s*展开)$")) {
            return new CleaningBlock(CleaningBlockType.NOISE, text, 0, NoiseLabel.DIRECTORY_RESIDUE);
        }
        return new CleaningBlock(CleaningBlockType.PARAGRAPH, text, 0, NoiseLabel.NONE);
    }

    private boolean startsWithLabel(String text, String prefix) {
        return text.startsWith(prefix + "：") || text.startsWith(prefix + ":");
    }

    private String trimBlankLines(String value) {
        String normalized = value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n');
        int start = 0;
        int end = normalized.length();
        while (start < end && normalized.charAt(start) == '\n') {
            start++;
        }
        while (end > start && normalized.charAt(end - 1) == '\n') {
            end--;
        }
        return normalized.substring(start, end);
    }

    private int headingLevel(String tagName) {
        return Integer.parseInt(tagName.substring(1));
    }
}
