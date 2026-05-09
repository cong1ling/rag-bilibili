package com.example.ragcsdn.cleaning;

import com.example.ragcsdn.cleaning.model.CleaningBlock;
import com.example.ragcsdn.cleaning.model.CleaningBlockType;
import com.example.ragcsdn.cleaning.model.NoiseLabel;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;

public class StructuredArticleCleaner {

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
            } else if ("p".equals(tag) && element.text().matches("^(点赞数?|点赞|评论数?|评论|浏览量|阅读量|收藏数?|收藏).*$")) {
                blocks.add(new CleaningBlock(CleaningBlockType.NOISE, element.text(), 0, NoiseLabel.ENGAGEMENT_METRIC));
            } else if ("p".equals(tag)) {
                blocks.add(new CleaningBlock(CleaningBlockType.PARAGRAPH, element.text(), 0, NoiseLabel.NONE));
            } else if ("pre".equals(tag) || "code".equals(tag)) {
                String codeText = element.wholeText().isBlank() ? element.text() : element.wholeText();
                blocks.add(new CleaningBlock(CleaningBlockType.CODE, codeText, 0, NoiseLabel.NONE));
            }
        }
        return blocks;
    }

    private int headingLevel(String tagName) {
        return Integer.parseInt(tagName.substring(1));
    }
}
