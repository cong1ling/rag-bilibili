package com.example.ragcsdn.service.article;

import com.example.ragcsdn.dto.response.ArticleResponse;
import com.example.ragcsdn.entity.Article;
import com.example.ragcsdn.mapper.ChunkMapper;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

@Component
public class ArticleResponseAssembler {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ChunkMapper chunkMapper;

    public ArticleResponseAssembler(ChunkMapper chunkMapper) {
        this.chunkMapper = chunkMapper;
    }

    public ArticleResponse toResponse(Article article) {
        ArticleResponse response = new ArticleResponse();
        response.setId(article.getId());
        response.setSourceId(article.getSourceId());
        response.setSourceUrl(article.getSourceUrl());
        response.setTitle(article.getTitle());
        response.setDescription(article.getDescription());
        if (article.getImportTime() != null) {
            response.setImportTime(article.getImportTime().format(FORMATTER));
        }
        response.setStatus(article.getStatus());
        response.setFailReason(article.getFailReason());
        response.setChunkCount(chunkMapper.countByArticleId(article.getId()));
        return response;
    }
}
