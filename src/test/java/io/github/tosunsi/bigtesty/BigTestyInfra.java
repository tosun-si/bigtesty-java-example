package io.github.tosunsi.bigtesty;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.bigquery.FieldList;
import com.google.cloud.bigquery.FieldValue;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.FormatOptions;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.bigquery.WriteChannelConfiguration;

/**
 * The ephemeral infra created by {@code bigtesty infra create}: its datasets and tables, and the BigQuery client to
 * load the test data, run the code under test and query the result.
 */
public final class BigTestyInfra {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The JSON output of {@code bigtesty infra create}.
     *
     * @param datasets the configured dataset ({@code monitoring}) → the dataset of the run
     *                 ({@code my-project.monitoring_abcxyz_0})
     * @param tables   the configured table ({@code monitoring.job_failure}) → the table of the run
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Ids(
            @JsonProperty("run_id") String runId,
            String project,
            Map<String, String> datasets,
            Map<String, String> tables) {}

    private final Ids ids;
    private final BigQuery client;

    private BigTestyInfra(Ids ids) {
        this.ids = ids;
        this.client = BigQueryOptions.newBuilder().setProjectId(ids.project()).build().getService();
    }

    static BigTestyInfra fromJson(String json) {
        try {
            return new BigTestyInfra(MAPPER.readValue(json, Ids.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unexpected output of bigtesty infra create: " + json, e);
        }
    }

    public String runId() {
        return ids.runId();
    }

    public String project() {
        return ids.project();
    }

    /**
     * The BigQuery client of the project of the infra, for the code under test.
     */
    public BigQuery client() {
        return client;
    }

    /**
     * The dataset of the run, as {@code project.dataset}: what the code under test receives.
     */
    public String dataset(String configuredDataset) {
        return require(ids.datasets(), configuredDataset);
    }

    /**
     * The table of the run, as {@code project.dataset.table}.
     */
    public String table(String configuredTable) {
        return require(ids.tables(), configuredTable);
    }

    /**
     * Loads the rows with a load job: unlike streaming inserts, they are immediately available for DML statements.
     */
    public void load(String configuredTable, List<Map<String, Object>> rows)
            throws InterruptedException {
        var tableId = tableId(configuredTable);
        var config = WriteChannelConfiguration.newBuilder(tableId).setFormatOptions(FormatOptions.json()).build();
        var writer = client.writer(config);

        try (writer) {
            writer.write(ByteBuffer.wrap(newlineDelimitedJson(rows).getBytes(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        var job = writer.getJob().waitFor();
        if (job.getStatus().getError() != null) {
            throw new IllegalStateException("Load of " + configuredTable + " failed: " + job.getStatus().getExecutionErrors());
        }
    }

    /**
     * The rows of a query, with the INT64, FLOAT64 and BOOL values typed and the others as strings.
     */
    public List<Map<String, Object>> query(String sql) throws InterruptedException {
        var result = client.query(QueryJobConfiguration.of(sql));
        var fields = result.getSchema().getFields();

        return StreamSupport.stream(result.iterateAll().spliterator(), false)
                .map(row -> toMap(fields, row))
                .toList();
    }

    /**
     * Empties all the tables, to start a test from empty tables on an infra shared by several tests.
     */
    public void reset() throws InterruptedException {
        var script = ids.tables().values().stream()
                .map(table -> "TRUNCATE TABLE `" + table + "`;")
                .collect(Collectors.joining("\n"));
        client.query(QueryJobConfiguration.of(script));
    }

    private TableId tableId(String configuredTable) {
        var parts = table(configuredTable).split("\\.");
        return TableId.of(parts[0], parts[1], parts[2]);
    }

    private static String newlineDelimitedJson(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> {
            try {
                return MAPPER.writeValueAsString(row);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException(e);
            }
        }).collect(Collectors.joining("\n"));
    }

    private static Map<String, Object> toMap(FieldList fields, FieldValueList row) {
        var map = new LinkedHashMap<String, Object>();
        for (var field : fields) {
            map.put(field.getName(), value(field.getType().getStandardType().name(), row.get(field.getName())));
        }
        return map;
    }

    private static Object value(String type, FieldValue value) {
        if (value.isNull()) {
            return null;
        }
        return switch (type) {
            case "INT64" -> value.getLongValue();
            case "FLOAT64" -> value.getDoubleValue();
            case "BOOL" -> value.getBooleanValue();
            default -> value.getStringValue();
        };
    }

    private static String require(Map<String, String> ids, String key) {
        var id = ids.get(key);
        if (id == null) {
            throw new IllegalArgumentException(key + " is not in bigtesty.yaml, configured: " + ids.keySet());
        }
        return id;
    }
}
