package com.example.ragcsdn.cleaning.eval;

import java.util.List;

public record CleaningRunArtifact(
        String articleId,
        String sourceUrl,
        String title,
        String sourceType,
        String cleanedText,
        List<String> chunks
) {
}
