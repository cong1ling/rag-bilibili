package com.example.ragcsdn.service.impl;

import com.alibaba.cloud.ai.reader.csdn.CsdnArticleLink;
import com.alibaba.cloud.ai.reader.csdn.CsdnDocumentReader;
import com.alibaba.cloud.ai.reader.csdn.CsdnResource;
import com.alibaba.cloud.ai.vectorstore.dashvector.DashVectorStore;
import com.example.ragcsdn.dto.request.ImportArticleRequest;
import com.example.ragcsdn.dto.request.ImportAuthorArticlesRequest;
import com.example.ragcsdn.dto.request.ImportRecommendedArticlesRequest;
import com.example.ragcsdn.dto.request.RebuildArticleRequest;
import com.example.ragcsdn.dto.response.ArticleResponse;
import com.example.ragcsdn.dto.response.BatchImportResponse;
import com.example.ragcsdn.entity.Article;
import com.example.ragcsdn.entity.Chunk;
import com.example.ragcsdn.entity.VectorMapping;
import com.example.ragcsdn.enums.ArticleStatus;
import com.example.ragcsdn.exception.BusinessException;
import com.example.ragcsdn.exception.ErrorCode;
import com.example.ragcsdn.mapper.*;
import com.example.ragcsdn.service.ArticleService;
import com.example.ragcsdn.service.UserService;
import com.example.ragcsdn.service.article.ArticleImportCommandService;
import com.example.ragcsdn.service.article.ArticleImportDecisionService;
import com.example.ragcsdn.service.article.ArticleImportFailureHandler;
import com.example.ragcsdn.service.article.ArticleLinkDiscoveryService;
import com.example.ragcsdn.service.article.ArticleResponseAssembler;
import com.example.ragcsdn.service.article.BatchImportResponseAssembler;
import com.example.ragcsdn.util.ChunkDocumentSplitter;
import com.example.ragcsdn.util.CsdnArticleUrlParser;
import com.example.ragcsdn.util.VectorIDGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class ArticleServiceImpl implements ArticleService {
    private static final Logger log = LoggerFactory.getLogger(ArticleServiceImpl.class);

    @Autowired
    private ArticleMapper articleMapper;

    @Autowired
    private ChunkMapper chunkMapper;

    @Autowired
    private VectorMappingMapper vectorMappingMapper;

    @Autowired
    private SessionMapper sessionMapper;

    @Autowired
    private MessageMapper messageMapper;

    @Autowired
    private ChunkDocumentSplitter chunkDocumentSplitter;

    @Autowired
    private DashVectorStore dashVectorStore;

    @Autowired
    private UserService userService;

    @Autowired
    private ArticleResponseAssembler articleResponseAssembler;

    @Autowired
    private BatchImportResponseAssembler batchImportResponseAssembler;

    @Autowired
    private ArticleImportFailureHandler articleImportFailureHandler;

    @Autowired
    private ArticleLinkDiscoveryService articleLinkDiscoveryService;

    @Autowired
    private ArticleImportDecisionService articleImportDecisionService;

    @Autowired
    private ArticleImportCommandService articleImportCommandService;

    @Override
    @Transactional
    public ArticleResponse importArticle(ImportArticleRequest request, Long userId) {
        return importSingleArticle(request.getArticleUrl(), userId);
    }

    @Override
    public BatchImportResponse importAuthorArticles(ImportAuthorArticlesRequest request, Long userId) {
        try {
            String csdnSessionCookie = userService.getCsdnSessionCookie(userId);
            ArticleLinkDiscoveryService.DiscoveryResult discovery =
                    articleLinkDiscoveryService.discoverAuthorArticles(csdnSessionCookie, request);

            if (discovery.links().isEmpty()) {
                throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "未找到该作者当前公开可导入的文章");
            }
            return batchImportArticles(discovery.mode(), discovery.target(), discovery.links(), userId);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("批量导入作者文章失败: userId={}, authorUrl={}", userId, request.getAuthorUrl(), ex);
            throw new BusinessException(ErrorCode.VIDEO_IMPORT_FAILED.getCode(), "作者文章列表抓取失败，请稍后重试");
        }
    }

    @Override
    public BatchImportResponse importRecommendedArticles(ImportRecommendedArticlesRequest request, Long userId) {
        try {
            String csdnSessionCookie = userService.getCsdnSessionCookie(userId);
            ArticleLinkDiscoveryService.DiscoveryResult discovery =
                    articleLinkDiscoveryService.discoverRecommendedArticles(csdnSessionCookie, request);
            if (discovery.links().isEmpty()) {
                throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "当前未发现可导入的公开推荐文章");
            }
            return batchImportArticles(discovery.mode(), discovery.target(), discovery.links(), userId);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("批量导入推荐文章失败: userId={}", userId, ex);
            throw new BusinessException(ErrorCode.VIDEO_IMPORT_FAILED.getCode(), "首页推荐文章抓取失败，请稍后重试");
        }
    }

    private ArticleResponse importSingleArticle(String articleUrl, Long userId) {
        CsdnResource resource = new CsdnResource(articleUrl);
        String sourceId = resource.getArticleId();
        String normalizedArticleUrl = resource.getArticleUrl();
        Article existingArticle = articleMapper.selectByUserIdAndSourceId(userId, sourceId);
        ArticleImportDecisionService.ImportDecision decision =
                articleImportDecisionService.decideExistingArticle(existingArticle);

        return switch (decision.action()) {
            case CREATE_NEW -> articleImportCommandService.submitNewImport(
                    userId,
                    sourceId,
                    normalizedArticleUrl,
                    () -> executeImport(null, userId, normalizedArticleUrl, sourceId, false)
            );
            case RETRY_FAILED -> articleImportCommandService.retryFailedImport(
                    decision.article(),
                    normalizedArticleUrl,
                    () -> executeImport(decision.article().getId(), userId, normalizedArticleUrl, sourceId, true)
            );
            case REJECT_DUPLICATE -> throw new BusinessException(ErrorCode.VIDEO_ALREADY_EXISTS);
        };
    }

    private BatchImportResponse batchImportArticles(String mode, String target, List<CsdnArticleLink> links, Long userId) {
        BatchImportResponse response = batchImportResponseAssembler.newResponse(mode, target, links.size());

        for (CsdnArticleLink link : links) {
            try {
                ArticleResponse imported = importSingleArticle(link.sourceUrl(), userId);
                batchImportResponseAssembler.addSubmitted(response, link, imported);
            } catch (BusinessException ex) {
                if (articleImportDecisionService.isDuplicateArticle(ex)) {
                    batchImportResponseAssembler.addDuplicate(response, link, ex.getMessage());
                } else {
                    batchImportResponseAssembler.addFailure(response, link, ex.getMessage());
                }
            } catch (Exception ex) {
                batchImportResponseAssembler.addFailure(response, link, Objects.requireNonNullElse(ex.getMessage(), "批量导入失败"));
            }
        }
        return response;
    }

    @Override
    public ArticleResponse rebuildArticle(Long articleId, RebuildArticleRequest request, Long userId) {
        Article article = articleMapper.selectById(articleId);
        if (article == null || !article.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.VIDEO_NOT_FOUND);
        }

        if (!CsdnArticleUrlParser.isValid(article.getSourceUrl())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "该记录缺少有效的CSDN文章地址，请重新导入");
        }
        return articleImportCommandService.submitRebuild(
                article,
                () -> executeImport(articleId, userId, article.getSourceUrl(), article.getSourceId(), true)
        );
    }

    /**
     * 异步执行实际的导入流程。
     * 在独立线程中运行，不占用 HTTP 请求线程。
     */
    private void executeImport(Long articleId, Long userId, String articleUrl, String sourceId,
                               boolean rebuildExistingIndex) {
        try {
            // 1. 使用 CSDN DocumentReader 读取文章内容
            String csdnSessionCookie = userService.getCsdnSessionCookie(userId);
            CsdnDocumentReader reader = new CsdnDocumentReader(new CsdnResource(articleUrl), csdnSessionCookie);
            List<Document> documents = reader.get();

            Article targetArticle = resolveTargetArticle(articleId, userId, sourceId);
            if (documents.isEmpty()) {
                markArticleFailed(targetArticle.getId(), "文章正文为空或无法提取有效内容");
                return;
            }

            Document document = documents.get(0);
            String articleTitle = (String) document.getMetadata().get("title");
            String articleDescription = (String) document.getMetadata().get("description");
            String canonicalUrl = (String) document.getMetadata().getOrDefault("sourceUrl", articleUrl);

            // 2. 文本切分
            List<Document> splitDocuments = chunkDocumentSplitter.split(documents);
            List<Document> indexedDocuments = new ArrayList<>(splitDocuments.size());

            // 3. 生成向量ID并准备数据
            List<Chunk> chunks = new ArrayList<>();
            int totalChunks = splitDocuments.size();

            for (int i = 0; i < splitDocuments.size(); i++) {
                Document doc = splitDocuments.get(i);
                String vectorId = VectorIDGenerator.generate(userId, sourceId, i);

                Document indexedDocument = Document.builder()
                        .id(vectorId)
                        .text(doc.getText())
                        .metadata(new HashMap<>(doc.getMetadata()))
                        .metadata("title", articleTitle)
                        .metadata("userId", userId)
                        .metadata("sourceId", sourceId)
                        .metadata("sourceUrl", canonicalUrl)
                        .metadata("chunkIndex", i)
                        .metadata("totalChunks", totalChunks)
                        .build();
                indexedDocuments.add(indexedDocument);

                Chunk chunk = new Chunk();
                chunk.setArticleId(targetArticle.getId());
                chunk.setUserId(userId);
                chunk.setSourceId(sourceId);
                chunk.setTitle(articleTitle);
                chunk.setChunkIndex(i);
                chunk.setTotalChunks(totalChunks);
                chunk.setChunkText(indexedDocument.getText());
                chunk.setCreateTime(LocalDateTime.now());
                chunks.add(chunk);
            }

            // 4. 重建场景先清理旧索引，再写入新数据
            if (rebuildExistingIndex) {
                cleanupExistingIndex(targetArticle.getId());
            }

            // 5. 批量插入分片
            if (!chunks.isEmpty()) {
                chunkMapper.batchInsert(chunks);
            }

            // 6. 写入 DashVector
            dashVectorStore.add(indexedDocuments);

            // 7. 创建向量映射
            List<VectorMapping> mappings = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                Chunk chunk = chunks.get(i);
                String vectorId = VectorIDGenerator.generate(userId, sourceId, i);

                VectorMapping mapping = new VectorMapping();
                mapping.setUserId(userId);
                mapping.setArticleId(articleId);
                mapping.setChunkId(chunk.getId());
                mapping.setVectorId(vectorId);
                mapping.setCreateTime(LocalDateTime.now());
                mappings.add(mapping);
            }

            if (!mappings.isEmpty()) {
                vectorMappingMapper.batchInsert(mappings);
            }

            // 8. 更新文章状态为成功
            Article article = articleMapper.selectById(targetArticle.getId());
            article.setTitle(articleTitle);
            article.setSourceUrl(canonicalUrl);
            article.setDescription(articleDescription);
            article.setStatus(ArticleStatus.SUCCESS.getCode());
            article.setFailReason(null);
            articleMapper.update(article);

            log.info("文章{}成功: userId={}, sourceId={}, chunks={}",
                    rebuildExistingIndex ? "重建" : "导入", userId, sourceId, totalChunks);

        } catch (Exception e) {
            log.error("文章{}失败: userId={}, sourceId={}",
                    rebuildExistingIndex ? "重建" : "导入", userId, sourceId, e);
            if (articleId != null) {
                markArticleFailed(articleId, e.getMessage());
                return;
            }
            Article targetArticle = articleMapper.selectByUserIdAndSourceId(userId, sourceId);
            if (targetArticle != null) {
                markArticleFailed(targetArticle.getId(), e.getMessage());
            }
        }
    }

    private Article resolveTargetArticle(Long articleId, Long userId, String sourceId) {
        Article article = articleId == null
                ? articleMapper.selectByUserIdAndSourceId(userId, sourceId)
                : articleMapper.selectById(articleId);
        if (article == null) {
            throw new BusinessException(ErrorCode.VIDEO_NOT_FOUND);
        }
        return article;
    }

    private void cleanupExistingIndex(Long articleId) {
        List<String> vectorIds = vectorMappingMapper.selectVectorIdsByArticleId(articleId);
        if (!vectorIds.isEmpty()) {
            dashVectorStore.delete(vectorIds);
        }
        vectorMappingMapper.deleteByArticleId(articleId);
        chunkMapper.deleteByArticleId(articleId);
    }

    private void markArticleFailed(Long articleId, String reason) {
        articleImportFailureHandler.markFailed(articleId, reason);
    }

    @Override
    public List<ArticleResponse> listArticles(Long userId) {
        List<Article> articles = articleMapper.selectByUserId(userId);
        return articles.stream()
                .map(articleResponseAssembler::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public ArticleResponse getArticle(Long articleId, Long userId) {
        Article article = articleMapper.selectById(articleId);
        if (article == null || !article.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.VIDEO_NOT_FOUND);
        }
        return articleResponseAssembler.toResponse(article);
    }

    @Override
    @Transactional
    public void deleteArticle(Long articleId, Long userId) {
        // 1. 验证文章是否存在且属于当前用户
        Article article = articleMapper.selectById(articleId);
        if (article == null || !article.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.VIDEO_NOT_FOUND);
        }

        try {
            // 2. 查询向量ID列表
            List<String> vectorIds = vectorMappingMapper.selectVectorIdsByArticleId(articleId);

            // 3. 从 DashVector 删除向量（基于ID删除）
            if (!vectorIds.isEmpty()) {
                dashVectorStore.delete(vectorIds);
            }

            // 4. 查询关联的会话ID列表
            List<Long> sessionIds = sessionMapper.selectIdsByArticleId(articleId);

            // 5. 删除会话关联的消息
            if (!sessionIds.isEmpty()) {
                messageMapper.deleteBySessionIds(sessionIds);
            }

            // 6. 删除会话
            sessionMapper.deleteByArticleId(articleId);

            // 7. 删除向量映射
            vectorMappingMapper.deleteByArticleId(articleId);

            // 8. 删除分片
            chunkMapper.deleteByArticleId(articleId);

            // 9. 删除文章记录
            articleMapper.deleteById(articleId);

            log.info("内容删除成功: userId={}, articleId={}, sourceId={}", userId, articleId, article.getSourceId());

        } catch (Exception e) {
            log.error("文章删除失败: userId={}, articleId={}", userId, articleId, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR);
        }
    }

}
