package com.example.ragcsdn.cleaning;

import com.example.ragcsdn.cleaning.model.CleaningBlock;
import com.example.ragcsdn.cleaning.model.CleaningBlockType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredArticleCleanerTest {

    @Test
    void clean_extractsHeadingCodeAndNoiseBlocks() {
        StructuredArticleCleaner cleaner = new StructuredArticleCleaner();

        List<CleaningBlock> blocks = cleaner.extractBlocks("""
                <html><body>
                  <div id="content_views">
                    <h2>一、安装</h2>
                    <p>正文说明</p>
                    <pre><code>if (a &lt; b) {\n    return a;\n}</code></pre>
                    <p>点赞数 10</p>
                  </div>
                </body></html>
                """);

        assertThat(blocks).extracting(CleaningBlock::type).contains(
                CleaningBlockType.HEADING,
                CleaningBlockType.PARAGRAPH,
                CleaningBlockType.CODE,
                CleaningBlockType.NOISE
        );
        assertThat(blocks.stream()
                .filter(block -> block.type() == CleaningBlockType.CODE)
                .findFirst()
                .orElseThrow()
                .content()).contains("    return a;");
    }
}
