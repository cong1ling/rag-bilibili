package com.example.ragcsdn.cleaning.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DataCleaningEvaluationCliLiveTest {

    @Test
    @EnabledIfSystemProperty(named = "cleaning.live", matches = "true")
    void runsRealEvaluationAgainstAvailableLocalSources() throws Exception {
        Path outputDir = Path.of("target", "cleaning-eval-live");
        DataCleaningEvaluationCli.run(null, outputDir);

        assertThat(Files.exists(outputDir.resolve("cleaning-comparison-summary.md"))).isTrue();
        assertThat(Files.exists(outputDir.resolve("cleaning-per-article.md"))).isTrue();
    }
}
