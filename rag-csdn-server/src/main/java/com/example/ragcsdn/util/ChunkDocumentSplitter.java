package com.example.ragcsdn.util;

import com.example.ragcsdn.cleaning.BlockAwareChunker;
import com.example.ragcsdn.cleaning.model.CleaningBlock;
import com.example.ragcsdn.cleaning.model.CleaningBlockType;
import com.example.ragcsdn.cleaning.model.NoiseLabel;
import com.example.ragcsdn.config.ChunkingProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ChunkDocumentSplitter {

    private final TokenTextSplitter delegate;
    private final int chunkSize;
    private final int overlapChars;

    @Autowired
    public ChunkDocumentSplitter(ChunkingProperties properties) {
        this(new TokenTextSplitter(
                properties.getChunkSize(),
                properties.getMinChunkSizeChars(),
                properties.getMinChunkLengthToEmbed(),
                properties.getMaxNumChunks(),
                properties.isKeepSeparator()
        ), properties.getChunkSize(), properties.getOverlapChars());
    }

    ChunkDocumentSplitter(TokenTextSplitter delegate, int overlapChars) {
        this(delegate, 512, overlapChars);
    }

    ChunkDocumentSplitter(TokenTextSplitter delegate, int chunkSize, int overlapChars) {
        this.delegate = delegate;
        this.chunkSize = chunkSize;
        this.overlapChars = Math.max(0, overlapChars);
    }

    public List<Document> split(List<Document> documents) {
        List<Document> splitDocuments = new ArrayList<>();
        for (Document document : documents) {
            List<CleaningBlock> structuredBlocks = extractStructuredBlocks(document);
            if (!structuredBlocks.isEmpty()) {
                splitDocuments.addAll(splitStructuredDocument(document, structuredBlocks));
            } else {
                splitDocuments.addAll(applyOverlap(delegate.apply(List.of(document))));
            }
        }
        return splitDocuments;
    }

    public List<String> splitStructuredBlocks(List<CleaningBlock> blocks) {
        return new BlockAwareChunker(chunkSize).chunk(blocks);
    }

    private List<Document> splitStructuredDocument(Document document, List<CleaningBlock> blocks) {
        List<String> structuredChunks = splitStructuredBlocks(blocks);
        List<Document> documents = new ArrayList<>(structuredChunks.size());
        for (int i = 0; i < structuredChunks.size(); i++) {
            documents.add(document.mutate()
                    .text(structuredChunks.get(i))
                    .metadata("chunkMode", "structured")
                    .metadata("chunkIndex", i)
                    .metadata("totalChunks", structuredChunks.size())
                    .build());
        }
        return documents;
    }

    private List<Document> applyOverlap(List<Document> splitDocuments) {
        if (overlapChars == 0 || splitDocuments.size() < 2) {
            return splitDocuments;
        }

        List<Document> overlappedDocuments = new ArrayList<>(splitDocuments.size());
        String previousText = null;
        for (Document document : splitDocuments) {
            String currentText = document.getText();
            int actualOverlap = previousText == null ? 0 : Math.min(overlapChars, previousText.length());
            String mergedText = actualOverlap == 0
                    ? currentText
                    : mergeWithOverlap(previousText, currentText, actualOverlap);

            overlappedDocuments.add(document.mutate()
                    .text(mergedText)
                    .metadata("overlapChars", actualOverlap)
                    .build());

            previousText = currentText;
        }
        return overlappedDocuments;
    }

    @SuppressWarnings("unchecked")
    private List<CleaningBlock> extractStructuredBlocks(Document document) {
        Object rawBlocks = document.getMetadata().get("structuredBlocks");
        if (!(rawBlocks instanceof List<?> rawList)) {
            return List.of();
        }

        List<CleaningBlock> blocks = new ArrayList<>();
        for (Object item : rawList) {
            if (!(item instanceof Map<?, ?> rawMap)) {
                continue;
            }
            Map<String, Object> map = (Map<String, Object>) rawMap;
            String type = String.valueOf(map.get("type"));
            String content = String.valueOf(map.getOrDefault("content", ""));
            int level = ((Number) map.getOrDefault("level", 0)).intValue();
            String noiseLabel = String.valueOf(map.getOrDefault("noiseLabel", "NONE"));
            blocks.add(new CleaningBlock(
                    CleaningBlockType.valueOf(type),
                    content,
                    level,
                    NoiseLabel.valueOf(noiseLabel)
            ));
        }
        return blocks;
    }

    private String mergeWithOverlap(String previousText, String currentText, int actualOverlap) {
        String overlapText = previousText.substring(previousText.length() - actualOverlap);
        if (currentText.startsWith(overlapText)) {
            return currentText;
        }
        if (overlapText.isEmpty() || currentText.isEmpty()) {
            return overlapText + currentText;
        }
        if (Character.isWhitespace(overlapText.charAt(overlapText.length() - 1))
                || Character.isWhitespace(currentText.charAt(0))) {
            return overlapText + currentText;
        }
        return overlapText + System.lineSeparator() + currentText;
    }
}

