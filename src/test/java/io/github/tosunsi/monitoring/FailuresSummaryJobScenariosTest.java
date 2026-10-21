package io.github.tosunsi.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import io.github.tosunsi.bigtesty.BigTestyExtension;
import io.github.tosunsi.bigtesty.BigTestyInfra;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Several scenarios as a parameterized test, on one infra for the class: each scenario starts from empty tables.
 */
class FailuresSummaryJobScenariosTest {

    @RegisterExtension
    static final BigTestyExtension bigtesty = new BigTestyExtension(Path.of("bigtesty/bigtesty.yaml"));

    static Stream<Arguments> scenarios() {
        return Stream.of(
                // --- Case 1: several failures of the same feature ---
                Arguments.of(
                        "failures_of_one_feature",
                        List.of(failure("featurePSG", "job1"), failure("featurePSG", "job2")),
                        List.of(Map.of("featureName", "featurePSG", "failuresCount", 2L))),
                // --- Case 2: one row per feature ---
                Arguments.of(
                        "failures_of_two_features",
                        List.of(failure("featurePSG", "job1"), failure("featureOM", "job2")),
                        List.of(
                                Map.of("featureName", "featureOM", "failuresCount", 1L),
                                Map.of("featureName", "featurePSG", "failuresCount", 1L))),
                // --- Case 3: no failure, empty summary ---
                Arguments.of("no_failure", List.of(), List.of()));
    }

    BigTestyInfra infra;

    @BeforeEach
    void startFromEmptyTables() throws InterruptedException {
        infra = bigtesty.infra();
        infra.reset();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void givenFailuresWhenBuildTheSummaryThenCountPerFeature(
            String scenario, List<Map<String, Object>> failures, List<Map<String, Object>> expectedSummary)
            throws InterruptedException {
        // GIVEN
        if (!failures.isEmpty()) {
            infra.load("monitoring.job_failure", failures);
        }

        // WHEN
        new FailuresSummaryJob(infra.client()).run(infra.dataset("monitoring"));

        // THEN
        var summary = infra.query("SELECT featureName, failuresCount FROM `%s` ORDER BY 1"
                .formatted(infra.table("monitoring.job_failure_summary")));
        assertEquals(expectedSummary, summary);
    }

    private static Map<String, Object> failure(String featureName, String jobName) {
        return Map.of("featureName", featureName, "jobName", jobName, "dwhCreationDate", "2026-01-10 10:00:00");
    }
}
