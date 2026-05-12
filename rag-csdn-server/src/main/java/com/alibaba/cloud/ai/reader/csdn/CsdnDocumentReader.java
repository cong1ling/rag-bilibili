package com.alibaba.cloud.ai.reader.csdn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;

import java.util.ArrayList;
import java.util.List;

public class CsdnDocumentReader implements DocumentReader {
    private static final Logger log = LoggerFactory.getLogger(CsdnDocumentReader.class);

    private final CsdnResource csdnResource;
    private final List<CsdnResource> csdnResourceList;
    private final CsdnHttpFetcher httpFetcher;
    private final CsdnDocumentAssembler documentAssembler;

    public CsdnDocumentReader(CsdnResource csdnResource) {
        this(csdnResource, null);
    }

    public CsdnDocumentReader(CsdnResource csdnResource, String cookieHeader) {
        this(csdnResource, null, new CsdnHttpFetcher(cookieHeader), new CsdnDocumentAssembler());
    }

    public CsdnDocumentReader(List<CsdnResource> csdnResourceList) {
        this(csdnResourceList, null);
    }

    public CsdnDocumentReader(List<CsdnResource> csdnResourceList, String cookieHeader) {
        this(null, csdnResourceList, new CsdnHttpFetcher(cookieHeader), new CsdnDocumentAssembler());
    }

    CsdnDocumentReader(
            CsdnResource csdnResource,
            List<CsdnResource> csdnResourceList,
            CsdnHttpFetcher httpFetcher,
            CsdnDocumentAssembler documentAssembler
    ) {
        this.csdnResource = csdnResource;
        this.csdnResourceList = csdnResourceList;
        this.httpFetcher = httpFetcher;
        this.documentAssembler = documentAssembler;
    }

    @Override
    public List<Document> get() {
        List<CsdnResource> resources = csdnResourceList == null ? List.of(csdnResource) : csdnResourceList;
        List<Document> documents = new ArrayList<>();
        for (CsdnResource resource : resources) {
            documents.addAll(readResource(resource));
        }
        return documents;
    }

    List<Document> parseDocuments(CsdnResource resource, String html) {
        return documentAssembler.parseDocuments(resource, html);
    }

    boolean isRetryableHttpStatus(int statusCode) {
        return httpFetcher.isRetryableHttpStatus(statusCode);
    }

    String buildHttpStatusErrorMessage(int statusCode, String url, int attempt) {
        return httpFetcher.buildHttpStatusErrorMessage(statusCode, url, attempt);
    }

    String userAgent() {
        return httpFetcher.userAgent();
    }

    private List<Document> readResource(CsdnResource resource) {
        try {
            return parseDocuments(resource, httpFetcher.fetch(resource.getArticleUrl()));
        } catch (Exception ex) {
            log.error("Failed to read CSDN article: {}", resource.getArticleUrl(), ex);
            throw new RuntimeException(firstNonBlank(ex.getMessage(), "Failed to read CSDN article: " + resource.getArticleUrl()), ex);
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
