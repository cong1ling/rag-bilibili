package com.alibaba.cloud.ai.reader.csdn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public class CsdnHttpFetcher {
    private static final Logger log = LoggerFactory.getLogger(CsdnHttpFetcher.class);

    private final HttpClient httpClient;
    private final String cookieHeader;
    private final CsdnFetchPolicy fetchPolicy;

    public CsdnHttpFetcher(String cookieHeader) {
        this(cookieHeader, CsdnFetchPolicy.defaultPolicy());
    }

    public CsdnHttpFetcher(String cookieHeader, CsdnFetchPolicy fetchPolicy) {
        this(
                HttpClient.newBuilder().connectTimeout(fetchPolicy.connectTimeout()).build(),
                cookieHeader,
                fetchPolicy
        );
    }

    CsdnHttpFetcher(HttpClient httpClient, String cookieHeader, CsdnFetchPolicy fetchPolicy) {
        this.httpClient = httpClient;
        this.cookieHeader = cookieHeader;
        this.fetchPolicy = fetchPolicy;
    }

    public String fetch(String url) throws IOException, InterruptedException {
        IOException lastException = null;

        for (int attempt = 1; attempt <= fetchPolicy.maxFetchAttempts(); attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(
                        buildRequest(url),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return response.body();
                }

                String message = buildHttpStatusErrorMessage(response.statusCode(), url, attempt);
                if (!isRetryableHttpStatus(response.statusCode()) || attempt == fetchPolicy.maxFetchAttempts()) {
                    throw new IOException(message);
                }

                lastException = new IOException(message);
                log.warn("CSDN article fetch returned retryable status {} on attempt {}/{}: {}",
                        response.statusCode(), attempt, fetchPolicy.maxFetchAttempts(), url);
            } catch (IOException ex) {
                lastException = ex;
                if (attempt == fetchPolicy.maxFetchAttempts() || !isRetryableTransportError(ex)) {
                    throw ex;
                }
                log.warn("CSDN article fetch failed on attempt {}/{}: {}",
                        attempt, fetchPolicy.maxFetchAttempts(), ex.getMessage());
            }

            sleepBeforeRetry(attempt);
        }

        if (lastException != null) {
            throw lastException;
        }
        throw new IOException("Failed to fetch CSDN article: " + url);
    }

    boolean isRetryableHttpStatus(int statusCode) {
        return fetchPolicy.retryableStatusCodes().contains(statusCode);
    }

    String buildHttpStatusErrorMessage(int statusCode, String url, int attempt) {
        if (statusCode == 521 || statusCode == 522 || statusCode == 523 || statusCode == 524) {
            return "HTTP " + statusCode + " for url: " + url
                    + "。CSDN 当前网络波动或边缘节点暂时不可用，已尝试第" + attempt + "次抓取";
        }
        return "HTTP " + statusCode + " for url: " + url;
    }

    String userAgent() {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36";
    }

    private HttpRequest buildRequest(String url) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(fetchPolicy.requestTimeout())
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("User-Agent", userAgent());
        if (cookieHeader != null && !cookieHeader.isBlank()) {
            request.header("Cookie", cookieHeader);
        }
        return request.build();
    }

    private boolean isRetryableTransportError(IOException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase();
        return normalized.contains("http 408")
                || normalized.contains("http 429")
                || normalized.contains("http 500")
                || normalized.contains("http 502")
                || normalized.contains("http 503")
                || normalized.contains("http 504")
                || normalized.contains("http 521")
                || normalized.contains("http 522")
                || normalized.contains("http 523")
                || normalized.contains("http 524")
                || normalized.contains("timed out")
                || normalized.contains("connection reset")
                || normalized.contains("connection refused");
    }

    private void sleepBeforeRetry(int attempt) throws InterruptedException {
        try {
            Thread.sleep(fetchPolicy.baseRetryDelayMillis() * attempt);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw ex;
        }
    }
}
