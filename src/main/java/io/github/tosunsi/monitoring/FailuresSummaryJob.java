package io.github.tosunsi.monitoring;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.QueryJobConfiguration;

/**
 * The code under test: a job writing to BigQuery, with the dataset as a parameter as in production.
 */
public final class FailuresSummaryJob {

    private final BigQuery bigQuery;

    public FailuresSummaryJob(BigQuery bigQuery) {
        this.bigQuery = bigQuery;
    }

    /**
     * Replaces the failures summary of {@code dataset} ({@code project.dataset}) by the failures count per feature.
     */
    public void run(String dataset) throws InterruptedException {
        var sql = """
                DELETE FROM `%1$s.job_failure_summary` WHERE TRUE;

                INSERT INTO `%1$s.job_failure_summary` (featureName, failuresCount)
                SELECT featureName, COUNT(*) FROM `%1$s.job_failure` GROUP BY featureName;
                """.formatted(dataset);

        bigQuery.query(QueryJobConfiguration.of(sql));
    }
}
