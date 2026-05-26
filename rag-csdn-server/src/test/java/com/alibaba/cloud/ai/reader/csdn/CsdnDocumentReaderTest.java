package com.alibaba.cloud.ai.reader.csdn;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsdnDocumentReaderTest {

    @Test
    void shouldRetryTransientHttpStatuses() {
        CsdnDocumentReader reader = new CsdnDocumentReader(
                new CsdnResource("https://blog.csdn.net/test_author/article/details/147000001"));

        assertTrue(reader.isRetryableHttpStatus(521));
        assertTrue(reader.isRetryableHttpStatus(522));
        assertTrue(reader.isRetryableHttpStatus(503));
        assertTrue(reader.isRetryableHttpStatus(504));
    }

    @Test
    void shouldFormatHelpfulHttpStatusErrorMessage() {
        CsdnDocumentReader reader = new CsdnDocumentReader(
                new CsdnResource("https://blog.csdn.net/test_author/article/details/147000001"));

        String message = reader.buildHttpStatusErrorMessage(
                521,
                "https://blog.csdn.net/test_author/article/details/147000001",
                3);

        assertTrue(message.contains("HTTP 521"));
        assertTrue(message.contains("网络波动"));
        assertTrue(message.contains("3次"));
    }

    @Test
    void shouldRecognizeAccessGatePageInsteadOfTreatingItAsEmptyContent() {
        CsdnResource resource = new CsdnResource("https://blog.csdn.net/test_author/article/details/147000001");
        CsdnDocumentReader reader = new CsdnDocumentReader(resource);

        String html = """
                <html>
                  <head>
                    <title>访问校验</title>
                  </head>
                  <body>
                    <div class="passport-login-container">登录后您可以享受更多权益</div>
                    <div class="content-box">请完成访问校验后继续阅读</div>
                  </body>
                </html>
                """;

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> reader.parseDocuments(resource, html));

        assertTrue(error.getMessage().contains("登录或访问校验"));
    }

    @Test
    void shouldUseBrowserLikeUserAgentForCsdnRequests() {
        CsdnDocumentReader reader = new CsdnDocumentReader(
                new CsdnResource("https://blog.csdn.net/test_author/article/details/147000001"));

        assertTrue(reader.userAgent().startsWith("Mozilla/5.0"));
    }

    @Test
    void shouldExtractArticleContentFromHtml() {
        CsdnResource resource = new CsdnResource("https://blog.csdn.net/test_author/article/details/147000001");
        CsdnDocumentReader reader = new CsdnDocumentReader(resource);

        String html = """
                <html>
                  <head>
                    <meta property="og:title" content="测试文章"/>
                    <meta name="description" content="测试摘要"/>
                    <meta name="author" content="test_author"/>
                    <link rel="canonical" href="https://blog.csdn.net/test_author/article/details/147000001"/>
                  </head>
                  <body>
                    <main>
                      <div id="content_views">
                        <p>第一段内容</p>
                        <p>第二段内容</p>
                      </div>
                    </main>
                  </body>
                </html>
                """;

        List<Document> documents = reader.parseDocuments(resource, html);

        assertEquals(1, documents.size());
        assertTrue(documents.get(0).getText().contains("第一段内容"));
        assertTrue(documents.get(0).getText().contains("第二段内容"));
        assertEquals("测试文章", documents.get(0).getMetadata().get("title"));
    }

    @Test
    void shouldKeepTitleButExcludeAuthorAndDescriptionFromVectorText() {
        CsdnResource resource = new CsdnResource("https://blog.csdn.net/test_author/article/details/147000001");
        CsdnDocumentReader reader = new CsdnDocumentReader(resource);

        String html = """
                <html>
                  <head>
                    <meta property="og:title" content="测试文章"/>
                    <meta name="description" content="这是一段摘要"/>
                    <meta name="author" content="test_author"/>
                  </head>
                  <body>
                    <main>
                      <div id="content_views">
                        <p>这里是正文内容。</p>
                      </div>
                    </main>
                  </body>
                </html>
                """;

        List<Document> documents = reader.parseDocuments(resource, html);
        String text = documents.get(0).getText();

        assertTrue(text.contains("Article Title: 测试文章"));
        assertTrue(text.contains("这里是正文内容。"));
        assertTrue(!text.contains("Description:"));
        assertTrue(!text.contains("Author:"));
        assertTrue(!text.contains("这是一段摘要"));
        assertTrue(!text.contains("test_author"));
    }

    @Test
    void shouldRemoveStandaloneMetricNoiseFromContent() {
        CsdnResource resource = new CsdnResource("https://blog.csdn.net/test_author/article/details/147000001");
        CsdnDocumentReader reader = new CsdnDocumentReader(resource);

        String html = """
                <html>
                  <head>
                    <meta property="og:title" content="降噪测试"/>
                  </head>
                  <body>
                    <main>
                      <div id="content_views">
                        <p>点赞数 12</p>
                        <p>评论数 3</p>
                        <p>浏览量 1024</p>
                        <p>收藏 8</p>
                        <p>分享至微信</p>
                        <p>目录</p>
                        <p>这是保留的正文。</p>
                      </div>
                    </main>
                  </body>
                </html>
                """;

        List<Document> documents = reader.parseDocuments(resource, html);
        String text = documents.get(0).getText();

        assertTrue(text.contains("这是保留的正文。"));
        assertTrue(!text.contains("点赞数 12"));
        assertTrue(!text.contains("评论数 3"));
        assertTrue(!text.contains("浏览量 1024"));
        assertTrue(!text.contains("收藏 8"));
        assertTrue(!text.contains("分享至微信"));
        assertTrue(!text.contains("目录"));
    }

    @Test
    void shouldPreserveCodeIndentationInStructuredCleaning() {
        CsdnResource resource = new CsdnResource("https://blog.csdn.net/test_author/article/details/147000001");
        CsdnDocumentReader reader = new CsdnDocumentReader(resource);

        String html = """
                <html>
                  <body>
                    <main>
                      <div id="content_views">
                        <pre><code>if (a &lt; b) {\n    return a;\n}</code></pre>
                      </div>
                    </main>
                  </body>
                </html>
                """;

        List<Document> documents = reader.parseDocuments(resource, html);

        assertTrue(documents.get(0).getText().contains("    return a;"));
    }

    @Test
    void shouldKeepHeadingMarkersInOptimizedContent() {
        CsdnResource resource = new CsdnResource("https://blog.csdn.net/test_author/article/details/147000001");
        CsdnDocumentReader reader = new CsdnDocumentReader(resource);

        String html = """
                <html>
                  <head>
                    <meta property="og:title" content="结构测试"/>
                  </head>
                  <body>
                    <main>
                      <div id="content_views">
                        <h2>一、安装</h2>
                        <p>安装说明</p>
                        <h3>1.1 前置条件</h3>
                        <p>准备 JDK 17</p>
                      </div>
                    </main>
                  </body>
                </html>
                """;

        List<Document> documents = reader.parseDocuments(resource, html);
        String text = documents.get(0).getText();

        assertTrue(text.contains("## 一、安装"));
        assertTrue(text.contains("### 1.1 前置条件"));
        assertTrue(text.contains("安装说明"));
        assertTrue(text.contains("准备 JDK 17"));
    }

    @Test
    void parseDocuments_shouldKeepNoiseFilteringAndHeadingFormattingStable() {
        CsdnResource resource = new CsdnResource("https://blog.csdn.net/test_author/article/details/147000001");
        CsdnDocumentReader reader = new CsdnDocumentReader(resource);

        String html = """
                <html><body><main><div id="content_views">
                  <h2>安装</h2><p>目录</p><p>保留正文</p>
                </div></main></body></html>
                """;

        List<Document> documents = reader.parseDocuments(resource, html);

        assertTrue(documents.get(0).getText().contains("## 安装"));
        assertTrue(!documents.get(0).getText().contains("目录"));
    }

    @Test
    void shouldPreserveNestedOrderedListContentInArticleBody() {
        CsdnResource resource = new CsdnResource("https://blog.csdn.net/test_author/article/details/147000001");
        CsdnDocumentReader reader = new CsdnDocumentReader(resource);

        String html = """
                <html>
                  <head>
                    <meta property="og:title" content="题解测试"/>
                  </head>
                  <body>
                    <main>
                      <div id="content_views">
                        <h2>解题思路</h2>
                        <p>前置说明</p>
                        <ol>
                          <li>第一次观察重叠区间</li>
                          <li>第二次计算有效新增时间</li>
                          <li>第三次生成下一轮结果</li>
                          <li>第四次处理收尾逻辑</li>
                        </ol>
                        <p>最终总结</p>
                      </div>
                    </main>
                  </body>
                </html>
                """;

        List<Document> documents = reader.parseDocuments(resource, html);
        String text = documents.get(0).getText();

        assertTrue(text.contains("前置说明"));
        assertTrue(text.contains("第一次观察重叠区间"));
        assertTrue(text.contains("第二次计算有效新增时间"));
        assertTrue(text.contains("第三次生成下一轮结果"));
        assertTrue(text.contains("第四次处理收尾逻辑"));
        assertTrue(text.contains("最终总结"));
    }
}
