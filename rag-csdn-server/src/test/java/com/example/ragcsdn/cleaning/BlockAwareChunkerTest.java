package com.example.ragcsdn.cleaning;

import com.example.ragcsdn.cleaning.model.CleaningBlock;
import com.example.ragcsdn.cleaning.model.CleaningBlockType;
import com.example.ragcsdn.cleaning.model.NoiseLabel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BlockAwareChunkerTest {

    @Test
    void chunk_preservesCodeBlockAsSingleUnitWhenSmallEnough() {
        BlockAwareChunker chunker = new BlockAwareChunker(120);

        List<String> chunks = chunker.chunk(List.of(
                new CleaningBlock(CleaningBlockType.HEADING, "一、示例", 2, NoiseLabel.NONE),
                new CleaningBlock(CleaningBlockType.CODE, "if (a < b) {\n    return a;\n}", 0, NoiseLabel.NONE)
        ));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).contains("一、示例");
        assertThat(chunks.get(0)).contains("    return a;");
    }
}
