package com.example.ragcsdn.cleaning.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SampleSetBuilderTest {

    @Test
    void buildManifest_keepsStableOrderingAndSourceLabels() {
        SampleSetBuilder builder = new SampleSetBuilder();

        List<EvaluationArticleSample> samples = builder.buildManifest(List.of(
                new EvaluationArticleSample("a-2", "https://b", "标题B", "html-b", "existing"),
                new EvaluationArticleSample("a-1", "https://a", "标题A", "html-a", "newly-fetched")
        ));

        assertThat(samples).extracting(EvaluationArticleSample::articleId).containsExactly("a-1", "a-2");
        assertThat(samples).extracting(EvaluationArticleSample::sourceType).containsExactly("newly-fetched", "existing");
    }
}
