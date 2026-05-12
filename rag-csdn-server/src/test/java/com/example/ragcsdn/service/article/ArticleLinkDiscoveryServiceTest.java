package com.example.ragcsdn.service.article;

import com.alibaba.cloud.ai.reader.csdn.CsdnArticleLink;
import com.alibaba.cloud.ai.reader.csdn.CsdnDiscoveryReader;
import com.example.ragcsdn.dto.request.ImportAuthorArticlesRequest;
import com.example.ragcsdn.dto.request.ImportRecommendedArticlesRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArticleLinkDiscoveryServiceTest {

    @Test
    void discoverAuthorArticles_shouldNormalizeTargetAndReturnMode() throws Exception {
        CsdnDiscoveryReader reader = mock(CsdnDiscoveryReader.class);
        AtomicReference<String> capturedCookie = new AtomicReference<>();
        ArticleLinkDiscoveryService service = new ArticleLinkDiscoveryService(cookieHeader -> {
            capturedCookie.set(cookieHeader);
            return reader;
        });
        ImportAuthorArticlesRequest request = new ImportAuthorArticlesRequest();
        request.setAuthorUrl("https://blog.csdn.net/test_author/article/list/2");
        request.setMaxArticles(5);
        request.setMaxPages(3);
        List<CsdnArticleLink> links = List.of(new CsdnArticleLink("147000001", "https://a", "标题A", "test_author"));
        when(reader.discoverAuthorArticles(request.getAuthorUrl(), 5, 3)).thenReturn(links);

        ArticleLinkDiscoveryService.DiscoveryResult result = service.discoverAuthorArticles("cookie", request);

        assertThat(capturedCookie.get()).isEqualTo("cookie");
        assertThat(result.mode()).isEqualTo("AUTHOR_PUBLIC");
        assertThat(result.target()).isEqualTo("https://blog.csdn.net/test_author");
        assertThat(result.links()).containsExactlyElementsOf(links);
    }

    @Test
    void discoverRecommendedArticles_shouldKeepHomeTargetAndReturnLinks() throws Exception {
        CsdnDiscoveryReader reader = mock(CsdnDiscoveryReader.class);
        ArticleLinkDiscoveryService service = new ArticleLinkDiscoveryService(cookieHeader -> reader);
        ImportRecommendedArticlesRequest request = new ImportRecommendedArticlesRequest();
        request.setLimit(3);
        List<CsdnArticleLink> links = List.of(new CsdnArticleLink("147000001", "https://a", "标题A", "test_author"));
        when(reader.discoverRecommendedArticles(3)).thenReturn(links);

        ArticleLinkDiscoveryService.DiscoveryResult result = service.discoverRecommendedArticles("cookie", request);

        assertThat(result.mode()).isEqualTo("HOME_RECOMMENDATIONS");
        assertThat(result.target()).isEqualTo("https://blog.csdn.net/");
        assertThat(result.links()).containsExactlyElementsOf(links);
    }
}
