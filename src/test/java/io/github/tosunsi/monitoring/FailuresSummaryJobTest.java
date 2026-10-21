package io.github.tosunsi.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.github.tosunsi.bigtesty.BigTestyExtension;
import io.github.tosunsi.bigtesty.BigTestyInfra;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * One infra for all the tests of the class: created before the first one, deleted after the last one. The given and
 * the when run once in {@code @BeforeAll}, each test checks the result.
 */
class FailuresSummaryJobTest {

    @RegisterExtension
    static final BigTestyExtension bigtesty = new BigTestyExtension(Path.of("bigtesty/bigtesty.yaml"));

    static BigTestyInfra infra;

    @BeforeAll
    static void givenFailuresWhenBuildTheSummary() throws InterruptedException {
        infra = bigtesty.infra();

        // GIVEN
        infra.load("monitoring.job_failure", List.of(
                Map.of("featureName", "featurePSG", "jobName", "job1", "dwhCreationDate", "2026-01-10 10:00:00"),
                Map.of("featureName", "featurePSG", "jobName", "job2", "dwhCreationDate", "2026-01-11 10:00:00"),
                Map.of("featureName", "featureOM", "jobName", "job3", "dwhCreationDate", "2026-01-12 10:00:00")));

        // WHEN
        new FailuresSummaryJob(infra.client()).run(infra.dataset("monitoring"));
    }

    @Test
    void thenOneRowPerFeature() throws InterruptedException {
        var rows = infra.query("SELECT featureName, failuresCount FROM `%s` ORDER BY 1"
                .formatted(infra.table("monitoring.job_failure_summary")));

        assertEquals(List.of(
                Map.of("featureName", "featureOM", "failuresCount", 1L),
                Map.of("featureName", "featurePSG", "failuresCount", 2L)), rows);
    }

    @Test
    void thenTheTotalMatchesTheFailures() throws InterruptedException {
        var rows = infra.query("SELECT (SELECT SUM(failuresCount) FROM `%s`) AS summary, (SELECT COUNT(*) FROM `%s`) AS failures"
                .formatted(infra.table("monitoring.job_failure_summary"), infra.table("monitoring.job_failure")));

        assertEquals(rows.getFirst().get("failures"), rows.getFirst().get("summary"));
    }
}
