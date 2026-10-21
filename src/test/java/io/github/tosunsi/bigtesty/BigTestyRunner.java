package io.github.tosunsi.bigtesty;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.OutputFrame.OutputType;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.utility.MountableFile;

/**
 * Runs the {@code bigtesty infra} commands, and returns their JSON output.
 *
 * <p>Two ways, chosen with the {@code bigtesty.runner} system property: {@code cli}, the {@code bigtesty} command
 * installed on the machine, or {@code docker}, the {@code groupbees/bigtesty} image run with Testcontainers.
 */
public sealed interface BigTestyRunner {

    String create(Path config, String project);

    String destroy(String runId, String project);

    static BigTestyRunner fromSystemProperties() {
        return switch (System.getProperty("bigtesty.runner", "cli")) {
            case "cli" -> new Cli();
            case "docker" -> new Docker(System.getProperty("bigtesty.image", "groupbees/bigtesty:" + pinnedVersion()));
            case String other -> throw new IllegalArgumentException("Unknown bigtesty.runner: " + other);
        };
    }

    /**
     * The version of {@code requirements.txt}: the version of the CLI in the CI, updated by Dependabot.
     */
    static String pinnedVersion() {
        try {
            var matcher = Pattern.compile("(?m)^bigtesty==(\\S+)").matcher(Files.readString(Path.of("requirements.txt")));
            if (!matcher.find()) {
                throw new IllegalStateException("No bigtesty==<version> line in requirements.txt");
            }
            return matcher.group(1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The {@code bigtesty} command: stdout only contains the JSON, the logs go to stderr.
     */
    record Cli() implements BigTestyRunner {

        @Override
        public String create(Path config, String project) {
            return run("bigtesty", "infra", "create", "--config", config.toString(), "--project", project, "-o", "json");
        }

        @Override
        public String destroy(String runId, String project) {
            return run("bigtesty", "infra", "destroy", "--run-id", runId, "--project", project, "-o", "json");
        }

        private static String run(String... command) {
            try {
                var process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
                var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (process.waitFor() != 0) {
                    throw new IllegalStateException(String.join(" ", command) + " failed, see its logs above");
                }
                return output;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * The image as a one-shot container: it runs the command and exits, the JSON is read from its logs.
     */
    record Docker(String image) implements BigTestyRunner {

        private static final String APP_DIR = "/usr/local/src/app";

        @Override
        public String create(Path config, String project) {
            var configDir = config.toAbsolutePath().getParent().toString();
            return run(
                    List.of("infra", "create", "--config", "project/" + config.getFileName(), "--project", project,
                            "-o", "json"),
                    configDir);
        }

        @Override
        public String destroy(String runId, String project) {
            return run(List.of("infra", "destroy", "--run-id", runId, "--project", project, "-o", "json"), null);
        }

        private String run(List<String> command, String configDir) {
            try (var container = new GenericContainer<>(image)) {
                if (configDir != null) {
                    container.withFileSystemBind(configDir, APP_DIR + "/project", BindMode.READ_ONLY);
                }
                withCredentials(container);
                container.withCommand(command.toArray(String[]::new))
                        .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(3)));

                container.start();   // waits for the end of the command, fails on a non-zero exit code
                return container.getLogs(OutputType.STDOUT);
            }
        }

        /**
         * The credentials file of the CI (Workload Identity Federation), or the local gcloud config (ADC).
         */
        private static void withCredentials(GenericContainer<?> container) {
            var credentialsFile = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
            if (credentialsFile != null) {
                // Copied, not mounted: readable by the user of the image whatever the permissions of the file.
                container.withCopyFileToContainer(MountableFile.forHostPath(credentialsFile, 0644), "/tmp/credentials.json")
                        .withEnv("GOOGLE_APPLICATION_CREDENTIALS", "/tmp/credentials.json");
            } else {
                var gcloudConfig = Path.of(System.getProperty("user.home"), ".config", "gcloud").toString();
                container.withFileSystemBind(gcloudConfig, APP_DIR + "/.config/gcloud", BindMode.READ_ONLY);
            }
        }
    }
}
