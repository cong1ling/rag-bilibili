package com.example.ragcsdn.cleaning.eval;

public record EvaluationArticleSample(
        String articleId,
        String sourceUrl,
        String title,
        String rawHtml,
        String sourceType
) {
}
