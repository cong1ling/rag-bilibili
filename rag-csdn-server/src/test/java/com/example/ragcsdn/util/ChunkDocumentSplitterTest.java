package com.example.ragcsdn.util;

import com.example.ragcsdn.cleaning.model.CleaningBlock;
import com.example.ragcsdn.cleaning.model.CleaningBlockType;
import com.example.ragcsdn.cleaning.model.NoiseLabel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChunkDocumentSplitterTest {

    @Test
    void shouldAddConfiguredOverlapToFollowingChunks() {
        TokenTextSplitter delegate = mock(TokenTextSplitter.class);
        when(delegate.apply(anyList())).thenReturn(List.of(
                Document.builder().text("abcdef").build(),
                Document.builder().text("ghijkl").metadata("chunkIndex", 1).build()
        ));

        ChunkDocumentSplitter splitter = new ChunkDocumentSplitter(delegate, 3);

        List<Document> result = splitter.split(List.of(new Document("source")));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getText()).isEqualTo("abcdef");
        assertThat(result.get(0).getMetadata()).containsEntry("overlapChars", 0);
        assertThat(result.get(1).getText()).isEqualTo("def" + System.lineSeparator() + "ghijkl");
        assertThat(result.get(1).getMetadata()).containsEntry("overlapChars", 3);
        assertThat(result.get(1).getMetadata()).containsEntry("chunkIndex", 1);
    }

    @Test
    void shouldSkipOverlapWhenDisabled() {
        TokenTextSplitter delegate = mock(TokenTextSplitter.class);
        when(delegate.apply(anyList())).thenReturn(List.of(
                Document.builder().text("abcdef").build(),
                Document.builder().text("ghijkl").build()
        ));

        ChunkDocumentSplitter splitter = new ChunkDocumentSplitter(delegate, 0);

        List<Document> result = splitter.split(List.of(new Document("source")));

        assertThat(result).extracting(Document::getText).containsExactly("abcdef", "ghijkl");
    }

    @Test
    void shouldSplitStructuredBlocksWithBlockAwareChunker() {
        TokenTextSplitter delegate = mock(TokenTextSplitter.class);
        ChunkDocumentSplitter splitter = new ChunkDocumentSplitter(delegate, 0);

        List<String> chunks = splitter.splitStructuredBlocks(List.of(
                new CleaningBlock(CleaningBlockType.HEADING, "一、示例", 2, NoiseLabel.NONE),
                new CleaningBlock(CleaningBlockType.CODE, "if (a < b) {\n    return a;\n}", 0, NoiseLabel.NONE)
        ));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).contains("一、示例");
        assertThat(chunks.get(0)).contains("    return a;");
    }
}

