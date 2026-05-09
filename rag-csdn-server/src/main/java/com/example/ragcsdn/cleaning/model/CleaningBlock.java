package com.example.ragcsdn.cleaning.model;

public record CleaningBlock(
        CleaningBlockType type,
        String content,
        int level,
        NoiseLabel noiseLabel
) {
}
