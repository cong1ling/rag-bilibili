package com.example.ragcsdn.service.article;

import com.alibaba.cloud.ai.reader.csdn.CsdnArticleLink;
import com.alibaba.cloud.ai.reader.csdn.CsdnDiscoveryReader;
import com.example.ragcsdn.dto.request.ImportAuthorArticlesRequest;
import com.example.ragcsdn.dto.request.ImportRecommendedArticlesRequest;
import com.example.ragcsdn.util.CsdnAuthorUrlParser;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

@Service
public class ArticleLinkDiscoveryService {
    public static final String MODE_AUTHOR_PUBLIC = "AUTHOR_PUBLIC";
    public static final String MODE_HOME_RECOMMENDATIONS = "HOME_RECOMMENDATIONS";
    public static final String RECOMMENDATIONS_TARGET = "https://blog.csdn.net/";

    private final DiscoveryReaderFactory discoveryReaderFactory;

    public ArticleLinkDiscoveryService() {
        this(CsdnDiscoveryReader::new);
    }

    ArticleLinkDiscoveryService(DiscoveryReaderFactory discoveryReaderFactory) {
        this.discoveryReaderFactory = Objects.requireNonNull(discoveryReaderFactory, "discoveryReaderFactory");
    }

    public DiscoveryResult discoverAuthorArticles(String cookieHeader, ImportAuthorArticlesRequest request)
            throws IOException, InterruptedException {
        CsdnDiscoveryReader discoveryReader = discoveryReaderFactory.create(cookieHeader);
        List<CsdnArticleLink> links = discoveryReader.discoverAuthorArticles(
                request.getAuthorUrl(),
                request.getMaxArticles(),
                request.getMaxPages()
        );
        return new DiscoveryResult(
                MODE_AUTHOR_PUBLIC,
                CsdnAuthorUrlParser.normalizeAuthorUrl(request.getAuthorUrl()),
                links
        );
    }

    public DiscoveryResult discoverRecommendedArticles(String cookieHeader, ImportRecommendedArticlesRequest request)
            throws IOException, InterruptedException {
        CsdnDiscoveryReader discoveryReader = discoveryReaderFactory.create(cookieHeader);
        List<CsdnArticleLink> links = discoveryReader.discoverRecommendedArticles(request.getLimit());
        return new DiscoveryResult(MODE_HOME_RECOMMENDATIONS, RECOMMENDATIONS_TARGET, links);
    }

    @FunctionalInterface
    interface DiscoveryReaderFactory {
        CsdnDiscoveryReader create(String cookieHeader);
    }

    public record DiscoveryResult(String mode, String target, List<CsdnArticleLink> links) {
    }
}
