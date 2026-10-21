# BigTesty Java example

Java code writing to BigQuery, tested with JUnit 5 on the [BigTesty](https://github.com/tosun-si/bigtesty) ephemeral
infra: the datasets and tables of `bigtesty/bigtesty.yaml` are created before the tests of a class, on the real
BigQuery engine, and deleted after them.

```text
bigtesty/bigtesty.yaml                    # the datasets and tables of the tests
requirements.txt                          # the BigTesty version, updated by Dependabot
src/main/java/.../FailuresSummaryJob.java # the code under test, the dataset as a parameter
src/test/java/io/github/tosunsi/
├── bigtesty/
│   ├── BigTestyExtension.java            # JUnit 5 extension: infra created before all, destroyed after all
│   ├── BigTestyInfra.java                # the datasets and tables of the run, load and query helpers
│   └── BigTestyRunner.java               # bigtesty infra create / destroy, with the CLI or the Docker image
└── monitoring/
    ├── FailuresSummaryJobTest.java       # one infra for the class, given / when in @BeforeAll
    └── FailuresSummaryJobScenariosTest.java  # parameterized scenarios, each one from empty tables
```

## The test

```java
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
        new FailuresSummaryJob(infra.client()).run(infra.dataset("monitoring"));   // my-project.monitoring_abcxyz_0
    }

    @Test
    void thenOneRowPerFeature() throws InterruptedException {
        var rows = infra.query("SELECT featureName, failuresCount FROM `%s` ORDER BY 1"
                .formatted(infra.table("monitoring.job_failure_summary")));

        assertEquals(List.of(
                Map.of("featureName", "featureOM", "failuresCount", 1L),
                Map.of("featureName", "featurePSG", "failuresCount", 2L)), rows);
    }
}
```

The extension calls [`bigtesty infra create`](https://tosun-si.github.io/bigtesty/running-tests/other-languages/)
before all the tests and `bigtesty infra destroy` after them. Each run has its own datasets: the tests can run in
parallel, on several machines, without sharing data. If the tests are killed before the end, the tables expire after 6
hours and `bigtesty gc` deletes the datasets left.

## Run the tests

They need Application Default Credentials and the `roles/bigquery.user` role on the test project:

```bash
gcloud auth application-default login
export GOOGLE_CLOUD_PROJECT=<gcp_project_id>
```

With the BigTesty CLI, the default:

```bash
uv tool install bigtesty      # or: pip install -r requirements.txt
mvn test
```

Or with the `groupbees/bigtesty` Docker image, run with [Testcontainers](https://java.testcontainers.org/): no Python
needed, only Docker. The tag is the version of `requirements.txt`, the local gcloud config (or the
`GOOGLE_APPLICATION_CREDENTIALS` file) gives the credentials to the container:

```bash
mvn test -Dbigtesty.runner=docker
```

## CI

The [workflow](.github/workflows/tests.yml) runs the tests with the CLI and with the Docker image, authenticated with
Workload Identity Federation. Dependabot opens a PR at each BigTesty release: the tests run on the new version before
the merge.
