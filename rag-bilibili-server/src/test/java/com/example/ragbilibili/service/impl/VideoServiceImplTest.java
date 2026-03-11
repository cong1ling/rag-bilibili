package com.example.ragbilibili.service.impl;

import com.alibaba.cloud.ai.reader.bilibili.BilibiliDocumentReader;
import com.alibaba.cloud.ai.vectorstore.dashvector.DashVectorStore;
import com.example.ragbilibili.dto.request.ImportVideoRequest;
import com.example.ragbilibili.dto.response.VideoResponse;
import com.example.ragbilibili.entity.Chunk;
import com.example.ragbilibili.entity.VectorMapping;
import com.example.ragbilibili.entity.Video;
import com.example.ragbilibili.enums.VideoStatus;
import com.example.ragbilibili.mapper.ChunkMapper;
import com.example.ragbilibili.mapper.MessageMapper;
import com.example.ragbilibili.mapper.SessionMapper;
import com.example.ragbilibili.mapper.VectorMappingMapper;
import com.example.ragbilibili.mapper.VideoMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoServiceImplTest {

    @Mock
    private VideoMapper videoMapper;

    @Mock
    private ChunkMapper chunkMapper;

    @Mock
    private VectorMappingMapper vectorMappingMapper;

    @Mock
    private SessionMapper sessionMapper;

    @Mock
    private MessageMapper messageMapper;

    @Mock
    private TokenTextSplitter tokenTextSplitter;

    @Mock
    private DashVectorStore dashVectorStore;

    @InjectMocks
    private VideoServiceImpl videoService;

    @Test
    void importVideoShouldInsertVideoAfterFetchingTitle() {
        ImportVideoRequest request = new ImportVideoRequest();
        request.setBvidOrUrl("BV1KMwgeKECx");
        request.setSessdata("sessdata");
        request.setBiliJct("biliJct");
        request.setBuvid3("buvid3");

        when(videoMapper.selectByUserIdAndBvid(1L, "BV1KMwgeKECx")).thenReturn(null);

        Document sourceDocument = Document.builder()
                .text("视频原文")
                .metadata(new HashMap<>())
                .metadata("title", "测试标题")
                .metadata("description", "测试描述")
                .build();

        Document splitDocument = Document.builder()
                .text("切分片段")
                .metadata(new HashMap<>())
                .build();

        List<Document> documents = List.of(sourceDocument);
        List<Document> splitDocuments = List.of(splitDocument);

        when(tokenTextSplitter.apply(documents)).thenReturn(splitDocuments);

        doAnswer(invocation -> {
            Video video = invocation.getArgument(0);
            video.setId(100L);
            return 1;
        }).when(videoMapper).insert(any(Video.class));

        doAnswer(invocation -> {
            List<Chunk> chunks = invocation.getArgument(0);
            long id = 200L;
            for (Chunk chunk : chunks) {
                chunk.setId(id++);
            }
            return chunks.size();
        }).when(chunkMapper).batchInsert(any());

        when(vectorMappingMapper.batchInsert(any())).thenAnswer(invocation -> {
            List<VectorMapping> mappings = invocation.getArgument(0);
            return mappings.size();
        });
        when(videoMapper.update(any(Video.class))).thenReturn(1);
        when(chunkMapper.countByVideoId(100L)).thenReturn(1);
        doNothing().when(dashVectorStore).add(any());

        try (MockedConstruction<BilibiliDocumentReader> ignored = mockConstruction(
                BilibiliDocumentReader.class,
                (mock, context) -> when(mock.get()).thenReturn(documents))) {

            VideoResponse response = videoService.importVideo(request, 1L);

            ArgumentCaptor<Video> videoCaptor = ArgumentCaptor.forClass(Video.class);
            verify(videoMapper).insert(videoCaptor.capture());
            Video insertedVideo = videoCaptor.getValue();

            assertEquals("BV1KMwgeKECx", insertedVideo.getBvid());
            assertEquals("测试标题", insertedVideo.getTitle());
            assertEquals("测试描述", insertedVideo.getDescription());
            assertEquals(VideoStatus.SUCCESS.getCode(), response.getStatus());
            assertEquals("测试标题", response.getTitle());
            assertNotNull(response.getId());
            verify(dashVectorStore, times(1)).add(any());
        }
    }
}
