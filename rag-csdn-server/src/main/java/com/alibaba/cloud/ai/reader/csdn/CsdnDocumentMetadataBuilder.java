package com.alibaba.cloud.ai.reader.csdn;

import com.example.ragcsdn.cleaning.model.CleaningBlock;
import com.example.ragcsdn.cleaning.model.CleaningBlockType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CsdnDocumentMetadataBuilder {

    public Map<String, Object> build(CsdnResource resource, String canonicalUrl, String title,
                                     String description, String author, List<CleaningBlock> structuredBlocks) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("sourceId", resource.getArticleId());
        metadata.put("sourceUrl", canonicalUrl);
        metadata.put("document_type", "content");
        metadata.put("title", title);
        metadata.put("description", description);
        metadata.put("author", author);
        metadata.put("structuredBlocks", structuredBlocks.stream()
                .filter(block -> block.type() != CleaningBlockType.NOISE)
                .map(block -> Map.<String, Object>of(
                        "type", block.type().name(),
                        "content", block.content(),
                        "level", block.level(),
                        "noiseLabel", block.noiseLabel().name()))
                .toList());
        return metadata;
    }
}
