package com.alibaba.cloud.ai.reader.csdn;

import com.example.ragcsdn.cleaning.model.CleaningBlock;
import com.example.ragcsdn.cleaning.model.CleaningBlockType;
import com.example.ragcsdn.cleaning.model.NoiseLabel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CsdnDocumentMetadataBuilderTest {

    @Test
    void buildShouldExposeSourceTitleDescriptionAndStructuredBlocks() {
        CsdnDocumentMetadataBuilder builder = new CsdnDocumentMetadataBuilder();
        CsdnResource resource = new CsdnResource("https://blog.csdn.net/test_author/article/details/147000001");

        Map<String, Object> metadata = builder.build(
                resource,
                "https://blog.csdn.net/test_author/article/details/147000001",
                "结构测试",
                "desc",
                "author",
                List.of(
                        new CleaningBlock(CleaningBlockType.HEADING, "一、安装", 2, NoiseLabel.NONE),
                        new CleaningBlock(CleaningBlockType.NOISE, "点赞数 10", 0, NoiseLabel.ENGAGEMENT_METRIC)
                )
        );

        assertThat(metadata).containsEntry("sourceId", "147000001");
        assertThat(metadata).containsEntry("title", "结构测试");
        assertThat(metadata).containsEntry("description", "desc");
        assertThat(metadata).containsEntry("author", "author");
        assertThat((List<?>) metadata.get("structuredBlocks")).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> block = (Map<String, Object>) ((List<?>) metadata.get("structuredBlocks")).get(0);

        assertThat(block)
                .containsEntry("type", "HEADING")
                .containsEntry("content", "一、安装");
    }
}
