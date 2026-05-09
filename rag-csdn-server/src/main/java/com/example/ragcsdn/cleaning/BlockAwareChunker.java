package com.example.ragcsdn.cleaning;

import com.example.ragcsdn.cleaning.model.CleaningBlock;

import java.util.ArrayList;
import java.util.List;

public class BlockAwareChunker {

    private final int maxChars;

    public BlockAwareChunker(int maxChars) {
        this.maxChars = maxChars;
    }

    public List<String> chunk(List<CleaningBlock> blocks) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (CleaningBlock block : blocks) {
            String blockText = block.content();
            int extraLength = current.isEmpty() ? blockText.length() : blockText.length() + System.lineSeparator().length();
            if (!current.isEmpty() && current.length() + extraLength > maxChars) {
                chunks.add(current.toString());
                current = new StringBuilder();
            }
            if (!current.isEmpty()) {
                current.append(System.lineSeparator());
            }
            current.append(blockText);
        }

        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }
        return chunks;
    }
}
