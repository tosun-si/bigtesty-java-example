package io.github.tosunsi.bigtesty;

import java.nio.file.Path;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Creates the BigTesty ephemeral infra before all the tests of a class, and destroys it after them.
 *
 * <pre>{@code
 * @RegisterExtension
 * static final BigTestyExtension bigtesty = new BigTestyExtension(Path.of("bigtesty/bigtesty.yaml"));
 * }</pre>
 *
 * <p>The infra is created before the {@code @BeforeAll} methods of the class, which can load the test data. The GCP
 * project is given by the {@code GOOGLE_CLOUD_PROJECT} env var.
 */
public final class BigTestyExtension implements BeforeAllCallback, AfterAllCallback {

    private final Path config;
    private final BigTestyRunner runner = BigTestyRunner.fromSystemProperties();
    private BigTestyInfra infra;

    public BigTestyExtension(Path config) {
        this.config = config;
    }

    public BigTestyInfra infra() {
        if (infra == null) {
            throw new IllegalStateException("The infra is created before all the tests: register the extension on a static field");
        }
        return infra;
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        infra = BigTestyInfra.fromJson(runner.create(config, project()));
    }

    @Override
    public void afterAll(ExtensionContext context) {
        if (infra != null) {
            // If the tests are killed before, the tables expire and `bigtesty gc` deletes the datasets left.
            runner.destroy(infra.runId(), infra.project());
            infra = null;
        }
    }

    private static String project() {
        var project = System.getenv("GOOGLE_CLOUD_PROJECT");
        if (project == null || project.isBlank()) {
            throw new IllegalStateException("Set the GCP project of the tests in the GOOGLE_CLOUD_PROJECT env var");
        }
        return project;
    }
}
