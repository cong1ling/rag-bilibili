package com.example.ragbilibili.config;

import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeConnectionProperties;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeEmbeddingProperties;
import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DashScopeEmbeddingConfigTest {

    private final DashScopeEmbeddingConfig config = new DashScopeEmbeddingConfig();

    @Test
    void shouldBuildDashScopeEmbeddingModelWithExplicitOptions() {
        DashScopeConnectionProperties connectionProperties = new DashScopeConnectionProperties();
        connectionProperties.setApiKey("test-api-key");

        DashScopeEmbeddingOptions options = new DashScopeEmbeddingOptions();
        options.setModel("text-embedding-v4");
        options.setDimensions(1024);

        DashScopeEmbeddingProperties embeddingProperties = new DashScopeEmbeddingProperties();
        embeddingProperties.setMetadataMode(MetadataMode.EMBED);
        embeddingProperties.setOptions(options);

        EmbeddingModel embeddingModel = config.dashscopeEmbeddingModel(connectionProperties, embeddingProperties);

        DashScopeEmbeddingModel dashScopeEmbeddingModel = assertInstanceOf(DashScopeEmbeddingModel.class, embeddingModel);
        Object defaultOptionsObject = ReflectionTestUtils.getField(dashScopeEmbeddingModel, "defaultOptions");
        assertNotNull(defaultOptionsObject);

        DashScopeEmbeddingOptions defaultOptions = (DashScopeEmbeddingOptions) defaultOptionsObject;
        assertEquals("text-embedding-v4", defaultOptions.getModel());
        assertEquals(1024, defaultOptions.getDimensions());
        assertNotNull(defaultOptions.getTextType());
    }

    @Test
    void shouldFallbackToDefaultOptionsWhenPropertiesOptionsMissing() {
        DashScopeConnectionProperties connectionProperties = new DashScopeConnectionProperties();
        connectionProperties.setApiKey("test-api-key");

        DashScopeEmbeddingProperties embeddingProperties = new DashScopeEmbeddingProperties();
        embeddingProperties.setOptions(null);

        EmbeddingModel embeddingModel = config.dashscopeEmbeddingModel(connectionProperties, embeddingProperties);

        DashScopeEmbeddingModel dashScopeEmbeddingModel = assertInstanceOf(DashScopeEmbeddingModel.class, embeddingModel);
        Object defaultOptionsObject = ReflectionTestUtils.getField(dashScopeEmbeddingModel, "defaultOptions");
        assertNotNull(defaultOptionsObject);

        DashScopeEmbeddingOptions defaultOptions = (DashScopeEmbeddingOptions) defaultOptionsObject;
        assertNotNull(defaultOptions.getModel());
        assertNotNull(defaultOptions.getTextType());
    }
    @Test
    void embedDocumentShouldCarryNonNullOptionsIntoRequest() {
        DashScopeConnectionProperties connectionProperties = new DashScopeConnectionProperties();
        connectionProperties.setApiKey("test-api-key");

        DashScopeEmbeddingOptions options = new DashScopeEmbeddingOptions();
        options.setModel("text-embedding-v4");
        options.setDimensions(1024);

        DashScopeEmbeddingProperties embeddingProperties = new DashScopeEmbeddingProperties();
        embeddingProperties.setOptions(options);

        EmbeddingModel embeddingModel = config.dashscopeEmbeddingModel(connectionProperties, embeddingProperties);
        DashScopeEmbeddingOptions configuredOptions = (DashScopeEmbeddingOptions) ReflectionTestUtils.getField(embeddingModel, "defaultOptions");

        class CapturingDashScopeEmbeddingModel extends DashScopeEmbeddingModel {
            private EmbeddingRequest capturedRequest;

            CapturingDashScopeEmbeddingModel() {
                super(DashScopeApi.builder().apiKey("test-api-key").build(), MetadataMode.EMBED, configuredOptions);
            }

            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                this.capturedRequest = request;
                return new EmbeddingResponse(java.util.List.of(new Embedding(new float[]{0.1f, 0.2f}, 0)));
            }
        }

        CapturingDashScopeEmbeddingModel model = new CapturingDashScopeEmbeddingModel();
        model.embed(org.springframework.ai.document.Document.builder()
                .text("测试文本")
                .metadata(new java.util.HashMap<>())
                .build());

        assertNotNull(model.capturedRequest);
        assertNotNull(model.capturedRequest.getOptions());
        assertEquals("text-embedding-v4", model.capturedRequest.getOptions().getModel());
    }

}
