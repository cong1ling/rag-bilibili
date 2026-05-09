package com.example.ragcsdn.cleaning.eval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class SampleSetBuilder {

    public List<EvaluationArticleSample> buildManifest(List<EvaluationArticleSample> inputSamples) {
        List<EvaluationArticleSample> samples = new ArrayList<>(inputSamples);
        samples.sort(Comparator.comparing(EvaluationArticleSample::articleId));
        return samples;
    }
}
