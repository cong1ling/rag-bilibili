package com.example.ragcsdn.service.chat;

import com.example.ragcsdn.service.impl.QueryComplexityAnalyzer;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
public class QueryUnderstandingService {

    private final QueryComplexityAnalyzer queryComplexityAnalyzer;

    public QueryUnderstandingService(QueryComplexityAnalyzer queryComplexityAnalyzer) {
        this.queryComplexityAnalyzer = queryComplexityAnalyzer;
    }

    public String normalizeRewrittenQuery(String originalQuery, String rewritten) {
        if (rewritten == null || rewritten.isBlank()) {
            return originalQuery;
        }
        String normalized = rewritten.trim();
        normalized = normalized.replaceFirst("^(改写后的查询|Rewrite[d]? Query|Query)\\s*[:：]\\s*", "");
        normalized = normalized.replaceAll("^[\"“”'`]+|[\"“”'`]+$", "").trim();
        return normalized.isBlank() ? originalQuery : normalized;
    }

    public List<String> normalizeDecomposedQueries(String raw, String fallbackQuery, int maxDecomposedQueries) {
        if (raw == null || raw.isBlank()) {
            return List.of(fallbackQuery);
        }

        List<String> subQueries = raw.lines()
                .map(String::trim)
                .map(line -> line.replaceFirst("^[-*\\d.、)）\\s]+", ""))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .distinct()
                .limit(maxDecomposedQueries)
                .collect(Collectors.toList());

        return subQueries.isEmpty() ? List.of(fallbackQuery) : subQueries;
    }

    public QueryComplexityAnalyzer.QueryIntentHint normalizeQueryIntent(
            String raw,
            String fallbackQuery,
            boolean conversationDependent) {
        if (raw == null || raw.isBlank()) {
            return inferQueryIntentHeuristically(fallbackQuery, conversationDependent);
        }

        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("AMBIGUOUS")) {
            return QueryComplexityAnalyzer.QueryIntentHint.AMBIGUOUS;
        }
        if (normalized.contains("BROAD")) {
            return QueryComplexityAnalyzer.QueryIntentHint.BROAD;
        }
        if (normalized.contains("DIRECT")) {
            return QueryComplexityAnalyzer.QueryIntentHint.DIRECT;
        }
        return inferQueryIntentHeuristically(fallbackQuery, conversationDependent);
    }

    public QueryComplexityAnalyzer.QueryIntentHint inferQueryIntentHeuristically(String query, boolean conversationDependent) {
        String normalized = normalizeForMatch(query);
        if (normalized.isBlank()) {
            return QueryComplexityAnalyzer.QueryIntentHint.DIRECT;
        }

        boolean ambiguousCue = normalized.matches(".*(它|这个|那个|这部分|这里|那里|上面|前面|后面|其|该|this|that|it|they).*");
        boolean shortQuery = normalized.length() <= 18;
        if (ambiguousCue && shortQuery) {
            return QueryComplexityAnalyzer.QueryIntentHint.AMBIGUOUS;
        }
        if (queryComplexityAnalyzer == null) {
            return QueryComplexityAnalyzer.QueryIntentHint.DIRECT;
        }
        return queryComplexityAnalyzer.analyze(query, conversationDependent).suggestedIntent();
    }

    public String buildSummaryPrompt(String memorySummary) {
        if (memorySummary == null || memorySummary.isBlank()) {
            return "";
        }
        return ChatPromptTemplates.HISTORY_SUMMARY_TEMPLATE.formatted(memorySummary);
    }

    private String normalizeForMatch(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
