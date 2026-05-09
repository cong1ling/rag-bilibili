package com.example.ragcsdn.cleaning.eval;

import com.alibaba.cloud.ai.reader.csdn.CsdnArticleLink;
import com.alibaba.cloud.ai.reader.csdn.CsdnDiscoveryReader;
import com.example.ragcsdn.util.CsdnArticleUrlParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class SampleSetBuilder {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public List<EvaluationArticleSample> buildManifest(List<EvaluationArticleSample> inputSamples) {
        List<EvaluationArticleSample> samples = new ArrayList<>(inputSamples);
        samples.sort(Comparator.comparing(EvaluationArticleSample::articleId));
        return samples;
    }

    public List<EvaluationArticleSample> readManifest(Path manifestPath) throws IOException {
        if (!Files.exists(manifestPath)) {
            return List.of();
        }
        return objectMapper.readValue(manifestPath.toFile(), new TypeReference<>() {
        });
    }

    public List<EvaluationArticleSample> buildMixedManifest(
            String jdbcUrl,
            String username,
            String password,
            int targetSize,
            String cookieHeader) throws Exception {
        Map<String, EvaluationArticleSample> samples = new LinkedHashMap<>();
        addExistingDatabaseSamples(samples, jdbcUrl, username, password, targetSize, cookieHeader);
        if (samples.size() < targetSize) {
            addDiscoveredSamples(samples, targetSize, cookieHeader);
        }
        return buildManifest(new ArrayList<>(samples.values()));
    }

    private void addExistingDatabaseSamples(
            Map<String, EvaluationArticleSample> samples,
            String jdbcUrl,
            String username,
            String password,
            int targetSize,
            String cookieHeader) throws Exception {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT source_id, source_url, title FROM article WHERE source_url IS NOT NULL AND source_url <> '' ORDER BY id DESC LIMIT 500")) {
            while (resultSet.next() && samples.size() < targetSize) {
                String articleId = Objects.toString(resultSet.getString("source_id"), "");
                String sourceUrl = Objects.toString(resultSet.getString("source_url"), "");
                String title = Objects.toString(resultSet.getString("title"), "");
                EvaluationArticleSample sample = fetchSample(articleId, sourceUrl, title, "existing", cookieHeader);
                if (sample != null) {
                    samples.putIfAbsent(sample.articleId(), sample);
                }
            }
        }
    }

    private void addDiscoveredSamples(Map<String, EvaluationArticleSample> samples, int targetSize, String cookieHeader) throws Exception {
        CsdnDiscoveryReader discoveryReader = new CsdnDiscoveryReader(cookieHeader);
        List<CsdnArticleLink> discovered = discoveryReader.discoverRecommendedArticles(Math.max(targetSize * 2, 120));
        for (CsdnArticleLink link : discovered) {
            if (samples.size() >= targetSize) {
                return;
            }
            if (samples.containsKey(link.sourceId())) {
                continue;
            }
            EvaluationArticleSample sample = fetchSample(
                    link.sourceId(),
                    link.sourceUrl(),
                    link.title(),
                    "newly-fetched",
                    cookieHeader
            );
            if (sample != null) {
                samples.putIfAbsent(sample.articleId(), sample);
            }
        }
    }

    private EvaluationArticleSample fetchSample(
            String articleId,
            String sourceUrl,
            String fallbackTitle,
            String sourceType,
            String cookieHeader) {
        try {
            String normalizedUrl = CsdnArticleUrlParser.normalizeUrl(sourceUrl);
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(normalizedUrl))
                    .GET()
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("User-Agent", "Mozilla/5.0")
                    .timeout(java.time.Duration.ofSeconds(30));
            if (cookieHeader != null && !cookieHeader.isBlank()) {
                request.header("Cookie", cookieHeader);
            }

            HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }

            String html = response.body();
            if (!html.contains("content_views")) {
                return null;
            }

            String resolvedTitle = resolveTitle(html, fallbackTitle);
            String resolvedId = articleId == null || articleId.isBlank() ? CsdnArticleUrlParser.parseId(normalizedUrl) : articleId;
            return new EvaluationArticleSample(resolvedId, normalizedUrl, resolvedTitle, html, sourceType);
        } catch (Exception ex) {
            return null;
        }
    }

    private String resolveTitle(String html, String fallbackTitle) {
        org.jsoup.nodes.Document page = Jsoup.parse(html);
        String title = page.selectFirst("meta[property=og:title]") != null
                ? page.selectFirst("meta[property=og:title]").attr("content")
                : fallbackTitle;
        if (title == null || title.isBlank()) {
            title = page.title();
        }
        return title == null ? "" : title.trim();
    }
}
