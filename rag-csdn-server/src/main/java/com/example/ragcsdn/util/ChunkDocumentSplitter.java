package com.example.ragcsdn.util;

import com.example.ragcsdn.cleaning.BlockAwareChunker;
import com.example.ragcsdn.cleaning.model.CleaningBlock;
import com.example.ragcsdn.config.ChunkingProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

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
        List<Document> splitDocuments = delegate.apply(documents);
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

    public List<String> splitStructuredBlocks(List<CleaningBlock> blocks) {
        return new BlockAwareChunker(chunkSize).chunk(blocks);
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

