package io.github.awsopstoolkit.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.awsopstoolkit.aws.AwsCallGate;
import io.github.awsopstoolkit.aws.DynamoDbService;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.sts.StsClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Integration/performance laboratory that traverses the real AWS SDK -> HTTP -> LocalStack ->
 * DynamoDB-emulation path. Results are LOCALSTACK_DYNAMODB and never AWS throughput claims.
 */
@EnabledIfSystemProperty(named = "toolkit.localstack.performance", matches = "true")
class LocalStackDynamoPerformanceTest {
    private static final URI ENDPOINT =
            URI.create(System.getProperty("toolkit.localstack.endpoint", "http://127.0.0.1:4566"));
    private static final String ACCOUNT = "123456789012";
    private static final Region REGION = Region.US_EAST_1;
    private static final int BASELINE_RECORDS =
            Integer.getInteger("toolkit.localstack.baseline-records", 10_000);
    private static final int PRIMARY_RECORDS =
            Integer.getInteger("toolkit.localstack.primary-records", BASELINE_RECORDS);
    private static final boolean SCALE_ONLY = Boolean.getBoolean("toolkit.localstack.scale-only");
    private static final boolean DEEP_BENCHMARK =
            Boolean.parseBoolean(System.getProperty("toolkit.localstack.deep-benchmark", "true"));
    private static final boolean PARALLEL_ONLY =
            Boolean.parseBoolean(System.getProperty("toolkit.localstack.parallel-only", "false"));
    private static final boolean PROJECTION_ONLY =
            Boolean.parseBoolean(System.getProperty("toolkit.localstack.projection-only", "false"));
    private static final boolean WRITE_COMPATIBILITY =
            Boolean.parseBoolean(
                    System.getProperty("toolkit.localstack.write-compatibility", "false"));
    private static final boolean RUN_SWEEPS =
            Boolean.parseBoolean(System.getProperty("toolkit.localstack.run-sweeps", "true"));
    private static final boolean SEGMENT_PROFILE_ONLY =
            Boolean.parseBoolean(
                    System.getProperty("toolkit.localstack.segment-profile-only", "false"));
    private static final List<Integer> SEGMENT_PROFILE_VALUES =
            Arrays.stream(
                            System.getProperty(
                                            "toolkit.localstack.segment-profile-values",
                                            "128,256,512")
                                    .split(","))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .map(Integer::parseInt)
                    .toList();
    private static final int GET_SAMPLES =
            Integer.getInteger("toolkit.localstack.get-samples", 100);
    private static final int PAGE_SIZE = Integer.getInteger("toolkit.localstack.page-size", 5000);
    private static final int SCAN_WORKERS =
            Integer.getInteger("toolkit.localstack.scan-workers", 8);
    private static final int SCAN_SEGMENTS =
            Integer.getInteger("toolkit.localstack.scan-segments", 16);
    private static final int HTTP_CONNECTIONS =
            Integer.getInteger("toolkit.localstack.http-connections", 32);
    private static final int HTTP_SOCKET_TIMEOUT_SECONDS =
            Integer.getInteger("toolkit.localstack.http-socket-timeout-seconds", 8);
    private static final int API_ATTEMPT_TIMEOUT_SECONDS =
            Integer.getInteger("toolkit.localstack.api-attempt-timeout-seconds", 10);
    private static final int API_TIMEOUT_SECONDS =
            Integer.getInteger("toolkit.localstack.api-timeout-seconds", 12);
    private static final int RETRY_MAX_ATTEMPTS =
            Integer.getInteger("toolkit.localstack.retry-max-attempts", 1);
    private static final String LAB_MODE =
            System.getProperty("toolkit.localstack.mode", "PERSISTENT");
    private static final long BENCHMARK_MIN_FREE_BYTES =
            Long.getLong(
                    "toolkit.localstack.min-free-bytes",
                    "MEMORY".equalsIgnoreCase(LAB_MODE) ? 0L : 10L * 1024 * 1024 * 1024);
    private static final int STORAGE_CHECK_EVERY_PAGES =
            Integer.getInteger("toolkit.localstack.storage-check-every-pages", 100);
    private static final Path OUTPUT_DIR =
            Path.of(
                    System.getProperty(
                            "toolkit.localstack.output-dir", "target/localstack-performance"));
    private static final Path SEED_MANIFEST =
            Path.of(
                    System.getProperty(
                            "toolkit.localstack.seed-manifest",
                            ".aws-ops-toolkit/localstack-performance/seed-manifest.json"));
    private static final String ITEM_SHAPE = "enterprise-incident-v1";
    private static final int PROJECTION_CHECKPOINT_SCHEMA_VERSION = 1;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SCALE_PROJECTION =
            "pk,sk,#s,#v,transactionId,paymentId,accountId,document";
    private static final List<TableProfile> PROFILES =
            List.of(
                    new TableProfile("lab-perf-v3-01-rich-uniform", 512, false, 1000),
                    new TableProfile("lab-perf-v3-02-rich-skewed", 512, true, 1000),
                    new TableProfile("lab-perf-v3-03-medium-uniform", 4096, false, 1000),
                    new TableProfile("lab-perf-v3-04-medium-skewed", 4096, true, 1000),
                    new TableProfile("lab-perf-v3-05-large-uniform", 16384, false, 1000),
                    new TableProfile("lab-perf-v3-06-low-selectivity", 2048, false, 500),
                    new TableProfile("lab-perf-v3-07-high-cardinality", 2048, false, 10_000),
                    new TableProfile("lab-perf-v3-08-hot-key", 2048, true, 100));

    @Test
    @Timeout(value = 48, unit = TimeUnit.HOURS)
    void exercisesEightPersistedDynamoTablesThroughLocalStack() throws Exception {
        validateConfiguration();
        Map<String, Object> seedManifest = readSeedManifest();
        Files.createDirectories(OUTPUT_DIR);
        try (DynamoDbClient dynamo = client(HTTP_CONNECTIONS);
                StsClient sts = stsClient()) {
            var identity = sts.getCallerIdentity();
            assertEquals(ACCOUNT, identity.account());
            assertTrue(identity.arn().endsWith(":root"));

            var tableResults = new ArrayList<Map<String, Object>>();
            Map<String, Object> primary;
            if (SEGMENT_PROFILE_ONLY) {
                TableProfile profile = PROFILES.getFirst();
                requirePreparedDataset(dynamo, profile, PRIMARY_RECORDS);
                primary = segmentProfileBenchmark(dynamo, profile);
            } else if (SCALE_ONLY) {
                TableProfile profile = PROFILES.getFirst();
                requirePreparedDataset(dynamo, profile, PRIMARY_RECORDS);
                primary = benchmarkScaleTable(dynamo, profile, PRIMARY_RECORDS);
            } else {
                for (TableProfile profile : PROFILES) {
                    requirePreparedDataset(dynamo, profile, BASELINE_RECORDS);
                    tableResults.add(benchmarkTable(dynamo, profile, BASELINE_RECORDS));
                }
                primary = tableResults.getFirst();
                if (PRIMARY_RECORDS > BASELINE_RECORDS) {
                    TableProfile profile = PROFILES.getFirst();
                    requirePreparedDataset(dynamo, profile, PRIMARY_RECORDS);
                    primary = benchmarkScaleTable(dynamo, profile, PRIMARY_RECORDS);
                }
            }

            var report = new LinkedHashMap<String, Object>();
            report.put("schemaVersion", 1);
            report.put("scope", "LOCALSTACK_DYNAMODB");
            report.put("mode", LAB_MODE);
            report.put("generatedAt", Instant.now().toString());
            report.put("endpoint", ENDPOINT.toString());
            report.put("account", identity.account());
            report.put("region", REGION.id());
            report.put("baselineRecordsPerTable", BASELINE_RECORDS);
            report.put("primaryRecords", PRIMARY_RECORDS);
            report.put("scaleOnly", SCALE_ONLY);
            report.put("deepBenchmark", DEEP_BENCHMARK);
            report.put("parallelOnly", PARALLEL_ONLY);
            report.put("projectionOnly", PROJECTION_ONLY);
            report.put("writeCompatibility", WRITE_COMPATIBILITY);
            report.put("readOnly", !WRITE_COMPATIBILITY);
            report.put("runSweeps", RUN_SWEEPS);
            report.put("segmentProfileOnly", SEGMENT_PROFILE_ONLY);
            report.put("segmentProfileValues", SEGMENT_PROFILE_VALUES);
            report.put("getSamples", GET_SAMPLES);
            report.put("datasetPreparedExternally", true);
            report.put("itemShape", ITEM_SHAPE);
            report.put("seedManifest", seedManifest);
            report.put("pageSize", PAGE_SIZE);
            report.put("scanSegments", SCAN_SEGMENTS);
            report.put("scanWorkers", SCAN_WORKERS);
            report.put("httpConnections", HTTP_CONNECTIONS);
            report.put("httpSocketTimeoutSeconds", HTTP_SOCKET_TIMEOUT_SECONDS);
            report.put("apiAttemptTimeoutSeconds", API_ATTEMPT_TIMEOUT_SECONDS);
            report.put("apiTimeoutSeconds", API_TIMEOUT_SECONDS);
            report.put("retryMaxAttempts", RETRY_MAX_ATTEMPTS);
            report.put("benchmarkMinFreeBytes", BENCHMARK_MIN_FREE_BYTES);
            report.put("storageCheckEveryPages", STORAGE_CHECK_EVERY_PAGES);
            report.put("tables", tableResults);
            report.put("primaryScale", primary);
            report.put(
                    "limitations",
                    List.of(
                            "LocalStack performance is not DynamoDB AWS performance.",
                            "RCU/WCU, physical partitions, adaptive capacity and AWS network are not reproduced.",
                            "Eight benchmark schemas are synthetic because eight production table schemas are not present in this repository."));

            Path output = OUTPUT_DIR.resolve("localstack-dynamodb.json");
            JsonMapper.builder()
                    .build()
                    .writerWithDefaultPrettyPrinter()
                    .writeValue(output, report);
            assertTrue(Files.size(output) > 0);
        }
    }

    private static void validateConfiguration() {
        assertTrue(BASELINE_RECORDS >= 1);
        assertTrue(PRIMARY_RECORDS >= BASELINE_RECORDS);
        assertTrue(GET_SAMPLES >= 1 && GET_SAMPLES <= 10_000);
        assertTrue(PAGE_SIZE >= 1 && PAGE_SIZE <= 10_000);
        assertTrue(SCAN_WORKERS >= 1 && SCAN_WORKERS <= 256);
        assertTrue(SCAN_SEGMENTS >= 1);
        assertTrue(SEGMENT_PROFILE_VALUES.stream().allMatch(value -> value >= 1 && value <= 1024));
        assertTrue(HTTP_CONNECTIONS >= SCAN_WORKERS);
        assertTrue(HTTP_SOCKET_TIMEOUT_SECONDS > 0);
        assertTrue(API_ATTEMPT_TIMEOUT_SECONDS > HTTP_SOCKET_TIMEOUT_SECONDS);
        assertTrue(API_TIMEOUT_SECONDS >= API_ATTEMPT_TIMEOUT_SECONDS);
        assertTrue(RETRY_MAX_ATTEMPTS >= 1 && RETRY_MAX_ATTEMPTS <= 10);
        assertTrue(BENCHMARK_MIN_FREE_BYTES >= 0);
        assertTrue(STORAGE_CHECK_EVERY_PAGES >= 1);
        String host = ENDPOINT.getHost();
        assertTrue(
                "http".equals(ENDPOINT.getScheme())
                        && ("127.0.0.1".equals(host)
                                || "localhost".equals(host)
                                || "::1".equals(host)),
                "LocalStack performance lab refuses non-loopback endpoints");
    }

    private static DynamoDbClient client(int maxConnections) {
        return DynamoDbClient.builder()
                .endpointOverride(ENDPOINT)
                .region(REGION)
                .credentialsProvider(credentials())
                .httpClientBuilder(
                        Apache5HttpClient.builder()
                                .maxConnections(maxConnections)
                                .connectionTimeout(Duration.ofSeconds(2))
                                .connectionAcquisitionTimeout(Duration.ofSeconds(3))
                                .socketTimeout(Duration.ofSeconds(HTTP_SOCKET_TIMEOUT_SECONDS)))
                .overrideConfiguration(
                        ClientOverrideConfiguration.builder()
                                .apiCallAttemptTimeout(
                                        Duration.ofSeconds(API_ATTEMPT_TIMEOUT_SECONDS))
                                .apiCallTimeout(Duration.ofSeconds(API_TIMEOUT_SECONDS))
                                .retryStrategy(
                                        StandardRetryStrategy.builder()
                                                .maxAttempts(RETRY_MAX_ATTEMPTS)
                                                .build())
                                .build())
                .build();
    }

    private static StsClient stsClient() {
        return StsClient.builder()
                .endpointOverride(ENDPOINT)
                .region(REGION)
                .credentialsProvider(credentials())
                .httpClientBuilder(Apache5HttpClient.builder().maxConnections(4))
                .build();
    }

    private static StaticCredentialsProvider credentials() {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCOUNT, "test"));
    }

    private static void requirePreparedDataset(
            DynamoDbClient dynamo, TableProfile profile, int records) {
        long last = records - 1L;
        try {
            var first =
                    dynamo.getItem(
                            b ->
                                    b.tableName(profile.name())
                                            .key(key(profile, 0))
                                            .projectionExpression("pk,sk"));
            var tail =
                    dynamo.getItem(
                            b ->
                                    b.tableName(profile.name())
                                            .key(key(profile, last))
                                            .projectionExpression("pk,sk"));
            if (!first.hasItem() || !tail.hasItem()) {
                throw new IllegalStateException(
                        "Prepared dataset does not contain the expected range 0.."
                                + last
                                + " for "
                                + profile.name());
            }
        } catch (ResourceNotFoundException failure) {
            throw new IllegalStateException(
                    "Performance dataset is not prepared. Run the Docker seed service first: "
                            + profile.name(),
                    failure);
        }
    }

    private static String tenant(TableProfile profile, long sequence) {
        long bucket;
        if (profile.skewed() && sequence % 10 < 8) bucket = 0;
        else bucket = Math.floorMod(sequence, profile.tenants());
        return String.format(Locale.ROOT, "tenant-%05d", bucket);
    }

    private static Map<String, Object> benchmarkTable(
            DynamoDbClient dynamo, TableProfile profile, int records) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        result.put("table", profile.name());
        result.put("recordsTarget", records);
        result.put("payloadBytes", profile.payloadBytes());
        result.put("skewed", profile.skewed());
        result.put("tenants", profile.tenants());
        result.put("datasetPreparedExternally", true);
        result.put("getItem", getBenchmark(dynamo, profile, records));
        result.put("batchGet", batchGetBenchmark(dynamo, profile, records));
        result.put("query", queryBenchmark(dynamo, profile));
        result.put("partiql", partiqlBenchmark(dynamo, profile));
        result.put("scan", scan(dynamo, profile.name(), null, null));
        result.put(
                "scanFilterOnePercent",
                scan(
                        dynamo,
                        profile.name(),
                        "#s = :match",
                        new Expression(Map.of("#s", "status"), Map.of(":match", s("MATCH")))));
        result.put(
                "scanFilterRareNested",
                scan(
                        dynamo,
                        profile.name(),
                        "#incident.#correlationId = :needle",
                        new Expression(
                                Map.of(
                                        "#incident", "incident",
                                        "#correlationId", "correlationId"),
                                Map.of(":needle", s("incident-needle")))));
        result.put(
                "scanProjection",
                scan(
                        dynamo,
                        profile.name(),
                        null,
                        null,
                        "pk,sk,#s,transactionId,paymentId,accountId,document"));
        result.put(
                "parallelScan", parallelScan(dynamo, profile.name(), SCAN_SEGMENTS, SCAN_WORKERS));
        return result;
    }

    private static Map<String, Object> benchmarkScaleTable(
            DynamoDbClient dynamo, TableProfile profile, int records) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        result.put("table", profile.name());
        result.put("recordsTarget", records);
        result.put("datasetPreparedExternally", true);
        result.put("datasetValidation", "seed-manifest-plus-first-last-get-item");
        if (PROJECTION_ONLY) {
            result.put(
                    "parallelScanProjection",
                    parallelScanProjection(dynamo, profile.name(), SCAN_SEGMENTS, SCAN_WORKERS));
            return result;
        }
        if (!PARALLEL_ONLY) {
            result.put("scan", scan(dynamo, profile.name(), null, null));
        }
        result.put(
                "parallelScan", parallelScan(dynamo, profile.name(), SCAN_SEGMENTS, SCAN_WORKERS));
        if (DEEP_BENCHMARK) {
            result.put(
                    "scanFilterOnePercent",
                    scan(
                            dynamo,
                            profile.name(),
                            "#s = :match",
                            new Expression(Map.of("#s", "status"), Map.of(":match", s("MATCH")))));
            result.put(
                    "scanFilterRareNested",
                    scan(
                            dynamo,
                            profile.name(),
                            "#incident.#correlationId = :needle",
                            new Expression(
                                    Map.of(
                                            "#incident", "incident",
                                            "#correlationId", "correlationId"),
                                    Map.of(":needle", s("incident-needle")))));
            result.put(
                    "scanProjection",
                    scan(
                            dynamo,
                            profile.name(),
                            null,
                            null,
                            "pk,sk,#s,transactionId,paymentId,accountId,document"));
            if (RUN_SWEEPS) {
                result.put("segmentSweep", segmentSweep(dynamo, profile.name()));
                result.put("pageSizeSweep", pageSizeSweep(dynamo, profile.name()));
            }
            result.put("checkpointResume", checkpointResumeBenchmark(dynamo, profile.name()));
            if (WRITE_COMPATIBILITY) {
                result.put(
                        "conditionalWriteConflict",
                        conditionalWriteConflictBenchmark(dynamo, profile));
            }
        }
        return result;
    }

    private static Map<String, Object> segmentProfileBenchmark(
            DynamoDbClient dynamo, TableProfile profile) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        result.put("table", profile.name());
        result.put("recordsTarget", PRIMARY_RECORDS);
        result.put("datasetPreparedExternally", true);
        result.put("profiledSegment", 0);
        result.put("pageSize", PAGE_SIZE);
        var profiles = new ArrayList<Map<String, Object>>();
        for (int segments : SEGMENT_PROFILE_VALUES) {
            System.out.printf(
                    Locale.ROOT,
                    "SEGMENT_PROFILE start segment=0 totalSegments=%d pageSize=%d%n",
                    segments,
                    PAGE_SIZE);
            profiles.add(singleSegmentProjectionProfile(dynamo, profile.name(), segments));
        }
        result.put("singleSegmentProfiles", profiles);
        return result;
    }

    private static Map<String, Object> singleSegmentProjectionProfile(
            DynamoDbClient dynamo, String table, int totalSegments) throws Exception {
        int profiledSegment = 0;
        var service =
                new DynamoDbService(dynamo, new AwsCallGate(1, 1_000_000), (a, r) -> {}, PAGE_SIZE);
        var resume = new HashMap<Integer, DynamoDbService.SegmentCheckpoint>();
        for (int segment = 1; segment < totalSegments; segment++) {
            resume.put(
                    segment,
                    new DynamoDbService.SegmentCheckpoint(segment, totalSegments, Map.of(), true));
        }
        var bytes = new AtomicLong();
        long started = System.nanoTime();
        DynamoDbService.ScanSummary summary =
                service.parallelScan(
                        ScanRequest.builder()
                                .tableName(table)
                                .limit(PAGE_SIZE)
                                .projectionExpression(SCALE_PROJECTION)
                                .expressionAttributeNames(Map.of("#s", "status", "#v", "version"))
                                .build(),
                        totalSegments,
                        1,
                        Long.MAX_VALUE,
                        resume,
                        () -> false,
                        (segment, page) -> {
                            if (segment != profiledSegment) {
                                throw new IllegalStateException(
                                        "Unexpected profiled segment " + segment);
                            }
                            for (var value : page.items()) {
                                bytes.addAndGet(estimatedBytes(value));
                            }
                        },
                        checkpoint -> {});
        double seconds = elapsedSeconds(started);
        var result =
                metric(
                        summary.examined(),
                        summary.returned(),
                        summary.pages(),
                        seconds,
                        bytes.get());
        result.put("profiledSegment", profiledSegment);
        result.put("totalSegments", totalSegments);
        result.put("workers", 1);
        result.put("pageSize", PAGE_SIZE);
        result.put(
                "averageItemsPerPage",
                summary.pages() == 0 ? 0.0 : (double) summary.examined() / summary.pages());
        result.put("averageSecondsPerPage", summary.pages() == 0 ? 0.0 : seconds / summary.pages());
        result.put("projection", "pk,sk,status,version,transactionId,paymentId,accountId,document");
        System.out.printf(
                Locale.ROOT,
                "SEGMENT_PROFILE done segment=%d totalSegments=%d examined=%d pages=%d elapsedSeconds=%.3f itemsPerSecond=%.3f%n",
                profiledSegment,
                totalSegments,
                summary.examined(),
                summary.pages(),
                seconds,
                summary.examined() / seconds);
        return result;
    }

    private static Map<String, Object> getBenchmark(
            DynamoDbClient dynamo, TableProfile profile, int records) {
        int calls = Math.min(GET_SAMPLES, records);
        long started = System.nanoTime();
        long found = 0;
        for (int i = 0; i < calls; i++) {
            long sequence = (long) i * records / calls;
            var response =
                    dynamo.getItem(
                            b ->
                                    b.tableName(profile.name())
                                            .key(key(profile, sequence))
                                            .consistentRead(false));
            if (response.hasItem()) found++;
        }
        double seconds = elapsedSeconds(started);
        return metric(calls, found, calls, seconds, 0);
    }

    private static Map<String, Object> batchGetBenchmark(
            DynamoDbClient dynamo, TableProfile profile, int records) {
        int count = Math.min(100, records);
        var keys = new ArrayList<Map<String, AttributeValue>>();
        for (int i = 0; i < count; i++) {
            long sequence = (long) i * records / count;
            keys.add(key(profile, sequence));
        }
        long started = System.nanoTime();
        var response =
                dynamo.batchGetItem(
                        b ->
                                b.requestItems(
                                        Map.of(
                                                profile.name(),
                                                KeysAndAttributes.builder().keys(keys).build())));
        double seconds = elapsedSeconds(started);
        int returned = response.responses().getOrDefault(profile.name(), List.of()).size();
        return metric(count, returned, 1, seconds, 0);
    }

    private static Map<String, Object> queryBenchmark(DynamoDbClient dynamo, TableProfile profile) {
        long started = System.nanoTime();
        long returned = 0;
        long pages = 0;
        Map<String, AttributeValue> cursor = Map.of();
        do {
            Map<String, AttributeValue> startKey = cursor;
            QueryResponse response =
                    dynamo.query(
                            b ->
                                    b.tableName(profile.name())
                                            .keyConditionExpression("#pk = :pk")
                                            .expressionAttributeNames(Map.of("#pk", "pk"))
                                            .expressionAttributeValues(
                                                    Map.of(":pk", s("tenant-00000")))
                                            .exclusiveStartKey(startKey.isEmpty() ? null : startKey)
                                            .limit(PAGE_SIZE));
            returned += response.count();
            pages++;
            cursor = response.lastEvaluatedKey();
        } while (!cursor.isEmpty());
        double seconds = elapsedSeconds(started);
        return metric(returned, returned, pages, seconds, 0);
    }

    private static Map<String, Object> partiqlBenchmark(
            DynamoDbClient dynamo, TableProfile profile) {
        long started = System.nanoTime();
        long returned = 0;
        long pages = 0;
        String nextToken = null;
        do {
            ExecuteStatementResponse response =
                    dynamo.executeStatement(
                            ExecuteStatementRequest.builder()
                                    .statement(
                                            "SELECT pk, sk, status, version FROM \""
                                                    + profile.name()
                                                    + "\" WHERE pk=?")
                                    .parameters(s("tenant-00000"))
                                    .nextToken(nextToken)
                                    .limit(PAGE_SIZE)
                                    .build());
            returned += response.items().size();
            pages++;
            nextToken = response.nextToken();
        } while (nextToken != null && !nextToken.isBlank());
        double seconds = elapsedSeconds(started);
        return metric(returned, returned, pages, seconds, 0);
    }

    private static Map<String, Object> scan(
            DynamoDbClient dynamo, String table, String filter, Expression expression) {
        return scan(dynamo, table, filter, expression, null);
    }

    private static Map<String, Object> scan(
            DynamoDbClient dynamo,
            String table,
            String filter,
            Expression expression,
            String projection) {
        long started = System.nanoTime();
        long scanned = 0;
        long returned = 0;
        long pages = 0;
        long bytes = 0;
        Map<String, AttributeValue> cursor = Map.of();
        do {
            ScanRequest.Builder request =
                    ScanRequest.builder()
                            .tableName(table)
                            .limit(PAGE_SIZE)
                            .exclusiveStartKey(cursor.isEmpty() ? null : cursor);
            if (filter != null) {
                request.filterExpression(filter)
                        .expressionAttributeNames(expression.names())
                        .expressionAttributeValues(expression.values());
            }
            if (projection != null) {
                var names =
                        expression == null
                                ? new HashMap<String, String>()
                                : new HashMap<>(expression.names());
                names.put("#s", "status");
                request.projectionExpression(projection).expressionAttributeNames(names);
            }
            ScanResponse response = dynamo.scan(request.build());
            scanned += response.scannedCount();
            returned += response.count();
            pages++;
            for (var value : response.items()) bytes += estimatedBytes(value);
            cursor = response.lastEvaluatedKey();
        } while (!cursor.isEmpty());
        double seconds = elapsedSeconds(started);
        return metric(scanned, returned, pages, seconds, bytes);
    }

    private static Map<String, Object> parallelScan(
            DynamoDbClient dynamo, String table, int segments, int workers) throws Exception {
        var service =
                new DynamoDbService(
                        dynamo,
                        new AwsCallGate(Math.max(workers, 1), 1_000_000),
                        (a, r) -> {},
                        PAGE_SIZE);
        var bytes = new AtomicLong();
        var checkpoints = new ConcurrentHashMap<Integer, DynamoDbService.SegmentCheckpoint>();
        long started = System.nanoTime();
        DynamoDbService.ScanSummary summary =
                service.parallelScan(
                        ScanRequest.builder().tableName(table).limit(PAGE_SIZE).build(),
                        segments,
                        workers,
                        Long.MAX_VALUE,
                        Map.of(),
                        () -> false,
                        (segment, page) -> {
                            for (var value : page.items()) bytes.addAndGet(estimatedBytes(value));
                        },
                        checkpoint -> checkpoints.put(checkpoint.segment(), checkpoint));
        double seconds = elapsedSeconds(started);
        var result =
                metric(
                        summary.examined(),
                        summary.returned(),
                        summary.pages(),
                        seconds,
                        bytes.get());
        result.put("segments", segments);
        result.put("workers", workers);
        result.put("completedSegments", summary.completedSegments());
        return result;
    }

    private static Map<String, Object> parallelScanProjection(
            DynamoDbClient dynamo, String table, int segments, int workers) throws Exception {
        var service =
                new DynamoDbService(
                        dynamo,
                        new AwsCallGate(Math.max(workers, 1), 1_000_000),
                        (a, r) -> {},
                        PAGE_SIZE);
        Path checkpointRoot = projectionCheckpointRoot(table, segments);
        Files.createDirectories(checkpointRoot);
        ensureProjectionCheckpointIdentity(checkpointRoot, table, segments);
        var progress = new ConcurrentHashMap<Integer, ProjectionProgress>();
        Map<Integer, DynamoDbService.SegmentCheckpoint> resume =
                loadProjectionCheckpoints(checkpointRoot, table, segments, progress);
        ProjectionRunState priorRunState = loadProjectionRunState(checkpointRoot);
        var attemptPages = new AtomicLong();
        var attemptExamined = new AtomicLong();
        var storageCancelled = new AtomicBoolean();
        var lastUsableBytes = new AtomicLong(Long.MAX_VALUE);
        long started = System.nanoTime();
        DynamoDbService.ScanSummary summary;
        try {
            summary =
                    service.parallelScan(
                            ScanRequest.builder()
                                    .tableName(table)
                                    .limit(PAGE_SIZE)
                                    .projectionExpression(SCALE_PROJECTION)
                                    .expressionAttributeNames(
                                            Map.of("#s", "status", "#v", "version"))
                                    .build(),
                            segments,
                            workers,
                            Long.MAX_VALUE,
                            resume,
                            storageCancelled::get,
                            (segment, page) -> {
                                ProjectionProgress segmentProgress =
                                        progress.computeIfAbsent(
                                                segment, ignored -> new ProjectionProgress());
                                segmentProgress.add(page);
                                long pages = attemptPages.incrementAndGet();
                                long examined = attemptExamined.addAndGet(page.scannedCount());
                                if (pages % STORAGE_CHECK_EVERY_PAGES == 0) {
                                    long usableBytes =
                                            BENCHMARK_MIN_FREE_BYTES > 0
                                                    ? Files.getFileStore(OUTPUT_DIR)
                                                            .getUsableSpace()
                                                    : Long.MAX_VALUE;
                                    lastUsableBytes.set(usableBytes);
                                    boolean lowDisk =
                                            shouldCancelForLowDisk(
                                                    usableBytes, BENCHMARK_MIN_FREE_BYTES);
                                    System.out.printf(
                                            Locale.ROOT,
                                            "PROJECTION_SCAN_PROGRESS attemptPages=%d attemptExamined=%d resumedSegments=%d usableGiB=%.2f%n",
                                            pages,
                                            examined,
                                            resume.size(),
                                            usableBytes == Long.MAX_VALUE
                                                    ? -1.0
                                                    : usableBytes / (1024.0 * 1024 * 1024));
                                    if (lowDisk) {
                                        storageCancelled.set(true);
                                        System.err.printf(
                                                Locale.ROOT,
                                                "PROJECTION_SCAN_LOW_DISK_CANCEL usableGiB=%.2f reserveGiB=%.2f%n",
                                                usableBytes / (1024.0 * 1024 * 1024),
                                                BENCHMARK_MIN_FREE_BYTES / (1024.0 * 1024 * 1024));
                                    }
                                    saveProjectionRunStateUnchecked(
                                            checkpointRoot,
                                            new ProjectionRunState(
                                                    priorRunState.attempts() + 1,
                                                    priorRunState.accumulatedElapsedSeconds()
                                                            + elapsedSeconds(started)));
                                }
                            },
                            checkpoint -> {
                                ProjectionProgress segmentProgress =
                                        progress.computeIfAbsent(
                                                checkpoint.segment(),
                                                ignored -> new ProjectionProgress());
                                saveProjectionCheckpoint(
                                        checkpointRoot, table, checkpoint, segmentProgress);
                            });
        } catch (Exception failure) {
            double attemptSeconds = elapsedSeconds(started);
            saveProjectionRunState(
                    checkpointRoot,
                    new ProjectionRunState(
                            priorRunState.attempts() + 1,
                            priorRunState.accumulatedElapsedSeconds() + attemptSeconds));
            if (storageCancelled.get() && failure instanceof CancellationException) {
                throw new IllegalStateException(
                        String.format(
                                Locale.ROOT,
                                "Projection scan stopped because usable disk space fell below the configured reserve: usableGiB=%.2f reserveGiB=%.2f",
                                lastUsableBytes.get() / (1024.0 * 1024 * 1024),
                                BENCHMARK_MIN_FREE_BYTES / (1024.0 * 1024 * 1024)),
                        failure);
            }
            throw failure;
        }
        double attemptSeconds = elapsedSeconds(started);
        ProjectionRunState finalRunState =
                new ProjectionRunState(
                        priorRunState.attempts() + 1,
                        priorRunState.accumulatedElapsedSeconds() + attemptSeconds);
        saveProjectionRunState(checkpointRoot, finalRunState);

        long pages = progress.values().stream().mapToLong(ProjectionProgress::pages).sum();
        long examined = progress.values().stream().mapToLong(ProjectionProgress::examined).sum();
        long returned = progress.values().stream().mapToLong(ProjectionProgress::returned).sum();
        long bytes = progress.values().stream().mapToLong(ProjectionProgress::bytes).sum();
        long completedSegments =
                progress.values().stream().filter(ProjectionProgress::completed).count();
        if (completedSegments != segments || examined != PRIMARY_RECORDS) {
            throw new IllegalStateException(
                    "Projection scan completed with unexpected totals: completedSegments="
                            + completedSegments
                            + "/"
                            + segments
                            + ", examined="
                            + examined
                            + ", expected="
                            + PRIMARY_RECORDS);
        }

        boolean elapsedHistoryComplete = resume.isEmpty() || priorRunState.attempts() > 0;
        Map<String, Object> result;
        if (elapsedHistoryComplete) {
            result =
                    metric(
                            examined,
                            returned,
                            pages,
                            finalRunState.accumulatedElapsedSeconds(),
                            bytes);
        } else {
            result = new LinkedHashMap<>();
            result.put("scanned", examined);
            result.put("returned", returned);
            result.put("requests", pages);
            result.put("elapsedSeconds", null);
            result.put("scannedPerSecond", null);
            result.put("returnedPerSecond", null);
            result.put("bytesRead", bytes);
            result.put("megabytesPerSecond", null);
        }
        result.put("segments", segments);
        result.put("workers", workers);
        result.put("completedSegments", completedSegments);
        result.put("projection", "pk,sk,status,version,transactionId,paymentId,accountId,document");
        result.put("checkpointDir", checkpointRoot.toString());
        result.put("checkpointConfigVersion", PROJECTION_CHECKPOINT_SCHEMA_VERSION);
        result.put("benchmarkMinFreeBytes", BENCHMARK_MIN_FREE_BYTES);
        result.put("storageCheckEveryPages", STORAGE_CHECK_EVERY_PAGES);
        result.put("resumedSegments", resume.size());
        long resumedCompletedSegments =
                resume.values().stream()
                        .filter(DynamoDbService.SegmentCheckpoint::completed)
                        .count();
        result.put("resumedCompletedSegments", resumedCompletedSegments);
        result.put("checkpointMode", resume.isEmpty() ? "FRESH" : "RESUMED");
        result.put(
                "elapsedSemantics",
                elapsedHistoryComplete
                        ? "ACCUMULATED_ACROSS_CHECKPOINT_ATTEMPTS"
                        : "UNAVAILABLE_BEFORE_CURRENT_ATTEMPT_LEGACY_CHECKPOINTS");
        result.put("elapsedHistoryComplete", elapsedHistoryComplete);
        result.put("attempts", finalRunState.attempts());
        result.put("lastAttemptElapsedSeconds", attemptSeconds);
        result.put("lastAttemptPages", summary.pages());
        result.put("lastAttemptExamined", summary.examined());
        result.put("lastAttemptExaminedPerSecond", summary.examined() / attemptSeconds);
        result.put("lastAttemptPagesPerSecond", summary.pages() / attemptSeconds);
        return result;
    }

    private static Path projectionCheckpointRoot(String table, int segments) {
        return OUTPUT_DIR
                .resolve("projection-checkpoints")
                .resolve(
                        table
                                + "-"
                                + PRIMARY_RECORDS
                                + "-segments-"
                                + segments
                                + "-page-"
                                + PAGE_SIZE
                                + "-cfg-v"
                                + PROJECTION_CHECKPOINT_SCHEMA_VERSION);
    }

    static boolean shouldCancelForLowDisk(long usableBytes, long reserveBytes) {
        if (usableBytes < 0 || reserveBytes < 0) {
            throw new IllegalArgumentException("Disk space values cannot be negative");
        }
        return reserveBytes > 0 && usableBytes < reserveBytes;
    }

    static void ensureProjectionCheckpointIdentity(Path root, String table, int segments)
            throws Exception {
        Path path = root.resolve("checkpoint-config.json");
        ProjectionCheckpointIdentity expected =
                new ProjectionCheckpointIdentity(
                        PROJECTION_CHECKPOINT_SCHEMA_VERSION,
                        table,
                        PRIMARY_RECORDS,
                        PAGE_SIZE,
                        segments,
                        ITEM_SHAPE,
                        SCALE_PROJECTION);
        if (Files.exists(path)) {
            ProjectionCheckpointIdentity saved =
                    JSON.readValue(path.toFile(), ProjectionCheckpointIdentity.class);
            if (!expected.equals(saved)) {
                throw new IllegalStateException(
                        "Projection checkpoint identity mismatch: "
                                + path
                                + ". Use a fresh checkpoint directory.");
            }
            return;
        }

        boolean hasLegacyState;
        try (var entries = Files.list(root)) {
            hasLegacyState =
                    entries.anyMatch(
                            entry -> {
                                String name = entry.getFileName().toString();
                                return name.startsWith("segment-") || name.equals("run-state.json");
                            });
        }
        if (hasLegacyState) {
            throw new IllegalStateException(
                    "Projection checkpoint directory contains legacy state without "
                            + "checkpoint-config.json: "
                            + root
                            + ". Run with FreshProjection to avoid mixing incompatible "
                            + "checkpoint semantics.");
        }
        writeJsonAtomically(path, expected);
    }

    private static Map<Integer, DynamoDbService.SegmentCheckpoint> loadProjectionCheckpoints(
            Path root,
            String table,
            int segments,
            ConcurrentHashMap<Integer, ProjectionProgress> progress)
            throws Exception {
        var resume = new HashMap<Integer, DynamoDbService.SegmentCheckpoint>();
        for (int segment = 0; segment < segments; segment++) {
            Path path = root.resolve(String.format(Locale.ROOT, "segment-%04d.json", segment));
            if (!Files.exists(path)) continue;
            ProjectionCheckpointFile saved;
            try {
                saved = JSON.readValue(path.toFile(), ProjectionCheckpointFile.class);
            } catch (Exception failure) {
                quarantineCorruptProjectionState(path, failure);
                continue;
            }
            if (!table.equals(saved.table())
                    || saved.recordsTarget() != PRIMARY_RECORDS
                    || saved.pageSize() != PAGE_SIZE
                    || saved.segment() != segment
                    || saved.totalSegments() != segments) {
                throw new IllegalStateException(
                        "Projection checkpoint configuration mismatch: " + path);
            }
            Map<String, AttributeValue> cursor = decodeCheckpointKey(saved.nextStartKey());
            resume.put(
                    segment,
                    new DynamoDbService.SegmentCheckpoint(
                            segment, segments, cursor, saved.completed()));
            progress.put(
                    segment,
                    new ProjectionProgress(
                            saved.pages(),
                            saved.examined(),
                            saved.returned(),
                            saved.bytes(),
                            saved.completed()));
        }
        return Map.copyOf(resume);
    }

    private static void saveProjectionCheckpoint(
            Path root,
            String table,
            DynamoDbService.SegmentCheckpoint checkpoint,
            ProjectionProgress progress)
            throws Exception {
        ProjectionCheckpointFile saved =
                progress.snapshot(
                        table,
                        PRIMARY_RECORDS,
                        PAGE_SIZE,
                        checkpoint.segment(),
                        checkpoint.totalSegments(),
                        encodeCheckpointKey(checkpoint.nextStartKey()),
                        checkpoint.completed());
        Path path =
                root.resolve(String.format(Locale.ROOT, "segment-%04d.json", checkpoint.segment()));
        writeJsonAtomically(path, saved);
    }

    private static ProjectionRunState loadProjectionRunState(Path root) throws Exception {
        Path path = root.resolve("run-state.json");
        if (!Files.exists(path)) return new ProjectionRunState(0, 0);
        try {
            return JSON.readValue(path.toFile(), ProjectionRunState.class);
        } catch (Exception failure) {
            quarantineCorruptProjectionState(path, failure);
            return new ProjectionRunState(0, 0);
        }
    }

    private static void quarantineCorruptProjectionState(Path path, Exception failure)
            throws Exception {
        Path quarantine =
                path.resolveSibling(
                        path.getFileName()
                                + ".corrupt-"
                                + Long.toUnsignedString(System.currentTimeMillis()));
        Files.move(path, quarantine, StandardCopyOption.REPLACE_EXISTING);
        System.err.printf(
                Locale.ROOT,
                "PROJECTION_STATE_QUARANTINED source=%s quarantine=%s reason=%s%n",
                path,
                quarantine,
                failure.getMessage());
    }

    private static void saveProjectionRunState(Path root, ProjectionRunState state)
            throws Exception {
        writeJsonAtomically(root.resolve("run-state.json"), state);
    }

    private static synchronized void saveProjectionRunStateUnchecked(
            Path root, ProjectionRunState state) {
        try {
            saveProjectionRunState(root, state);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not persist projection run state", failure);
        }
    }

    private static Map<String, String> encodeCheckpointKey(
            Map<String, AttributeValue> checkpointKey) {
        if (checkpointKey.isEmpty()) return Map.of();
        var encoded = new TreeMap<String, String>();
        checkpointKey.forEach(
                (name, value) -> {
                    if (value.s() == null) {
                        throw new IllegalStateException(
                                "Projection benchmark expects string checkpoint keys: " + name);
                    }
                    encoded.put(name, value.s());
                });
        return Map.copyOf(encoded);
    }

    private static Map<String, AttributeValue> decodeCheckpointKey(
            Map<String, String> checkpointKey) {
        if (checkpointKey == null || checkpointKey.isEmpty()) return Map.of();
        var decoded = new HashMap<String, AttributeValue>();
        checkpointKey.forEach((name, value) -> decoded.put(name, s(value)));
        return Map.copyOf(decoded);
    }

    private static void writeJsonAtomically(Path path, Object value) throws Exception {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        JSON.writeValue(temp.toFile(), value);
        try {
            Files.move(
                    temp,
                    path,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static Map<String, Object> checkpointResumeBenchmark(
            DynamoDbClient dynamo, String table) throws Exception {
        int segments = Math.min(8, SCAN_SEGMENTS);
        int workers = Math.min(segments, SCAN_WORKERS);
        var service =
                new DynamoDbService(
                        dynamo,
                        new AwsCallGate(Math.max(workers, 1), 1_000_000),
                        (action, resource) -> {},
                        PAGE_SIZE);
        var checkpoints = new ConcurrentHashMap<Integer, DynamoDbService.SegmentCheckpoint>();
        long started = System.nanoTime();
        DynamoDbService.ScanSummary first =
                service.parallelScan(
                        ScanRequest.builder().tableName(table).limit(PAGE_SIZE).build(),
                        segments,
                        workers,
                        1,
                        Map.of(),
                        () -> false,
                        (segment, page) -> {},
                        checkpoint -> checkpoints.put(checkpoint.segment(), checkpoint));
        Map<Integer, DynamoDbService.SegmentCheckpoint> resume = Map.copyOf(checkpoints);
        DynamoDbService.ScanSummary second =
                service.parallelScan(
                        ScanRequest.builder().tableName(table).limit(PAGE_SIZE).build(),
                        segments,
                        workers,
                        Long.MAX_VALUE,
                        resume,
                        () -> false,
                        (segment, page) -> {},
                        checkpoint -> checkpoints.put(checkpoint.segment(), checkpoint));
        var result = new LinkedHashMap<String, Object>();
        result.put("segments", segments);
        result.put("workers", workers);
        result.put("firstPassPages", first.pages());
        result.put("firstPassExamined", first.examined());
        result.put("resumeCheckpoints", resume.size());
        result.put("resumedPages", second.pages());
        result.put("resumedExamined", second.examined());
        result.put("totalExamined", first.examined() + second.examined());
        result.put("completedSegments", second.completedSegments() + first.completedSegments());
        result.put("elapsedSeconds", elapsedSeconds(started));
        return result;
    }

    private static Map<String, Object> conditionalWriteConflictBenchmark(
            DynamoDbClient dynamo, TableProfile profile) throws Exception {
        Map<String, AttributeValue> itemKey = key(profile, 0);
        GetItemResponse before =
                dynamo.getItem(b -> b.tableName(profile.name()).key(itemKey).consistentRead(true));
        long expectedVersion = Long.parseLong(before.item().get("version").n());
        var service =
                new DynamoDbService(
                        dynamo, new AwsCallGate(1, 1_000_000), (action, resource) -> {}, PAGE_SIZE);
        UpdateItemRequest request =
                UpdateItemRequest.builder()
                        .tableName(profile.name())
                        .key(itemKey)
                        .updateExpression("SET #v = #v + :one")
                        .conditionExpression("#v = :expected")
                        .expressionAttributeNames(Map.of("#v", "version"))
                        .expressionAttributeValues(
                                Map.of(":one", n(1), ":expected", n(expectedVersion)))
                        .returnValues(ReturnValue.ALL_NEW)
                        .build();
        long started = System.nanoTime();
        UpdateItemResponse success = service.conditionalUpdate(request);
        boolean conflictObserved = false;
        try {
            service.conditionalUpdate(request);
        } catch (ConditionalCheckFailedException expected) {
            conflictObserved = true;
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("expectedVersion", expectedVersion);
        result.put("updatedVersion", Long.parseLong(success.attributes().get("version").n()));
        result.put("conflictObserved", conflictObserved);
        result.put("elapsedSeconds", elapsedSeconds(started));
        if (!conflictObserved)
            throw new IllegalStateException("Conditional conflict was not observed");
        return result;
    }

    private static List<Map<String, Object>> segmentSweep(DynamoDbClient dynamo, String table) {
        var results = new ArrayList<Map<String, Object>>();
        for (int segments : new int[] {1, 2, 4, 8, 16, 32, 64, 128}) {
            int workers = Math.min(segments, SCAN_WORKERS);
            System.out.printf(
                    Locale.ROOT, "SEGMENT_SWEEP start segments=%d workers=%d%n", segments, workers);
            try {
                var result = parallelScan(dynamo, table, segments, workers);
                result.put("status", "OK");
                results.add(result);
            } catch (Exception failure) {
                results.add(failureMetric("segments", segments, failure));
            }
        }
        return List.copyOf(results);
    }

    private static List<Map<String, Object>> pageSizeSweep(DynamoDbClient dynamo, String table) {
        var results = new ArrayList<Map<String, Object>>();
        for (int pageSize : new int[] {100, 500, 1000, 5000, 10000}) {
            System.out.printf(Locale.ROOT, "PAGE_SIZE_SWEEP start pageSize=%d%n", pageSize);
            try {
                var result = scanWithPageSize(dynamo, table, pageSize);
                result.put("status", "OK");
                results.add(result);
            } catch (RuntimeException failure) {
                results.add(failureMetric("pageSize", pageSize, failure));
            }
        }
        return List.copyOf(results);
    }

    private static Map<String, Object> scanWithPageSize(
            DynamoDbClient dynamo, String table, int pageSize) {
        long started = System.nanoTime();
        long scanned = 0;
        long returned = 0;
        long pages = 0;
        Map<String, AttributeValue> cursor = Map.of();
        do {
            Map<String, AttributeValue> startKey = cursor;
            ScanResponse response =
                    dynamo.scan(
                            b ->
                                    b.tableName(table)
                                            .limit(pageSize)
                                            .exclusiveStartKey(
                                                    startKey.isEmpty() ? null : startKey));
            scanned += response.scannedCount();
            returned += response.count();
            pages++;
            cursor = response.lastEvaluatedKey();
        } while (!cursor.isEmpty());
        var result = metric(scanned, returned, pages, elapsedSeconds(started), 0);
        result.put("pageSize", pageSize);
        return result;
    }

    private static Map<String, Object> failureMetric(
            String dimension, int value, Throwable failure) {
        var result = new LinkedHashMap<String, Object>();
        result.put(dimension, value);
        result.put("status", "ERROR");
        result.put("errorType", failure.getClass().getSimpleName());
        String message = failure.getMessage();
        result.put(
                "errorMessage",
                message == null ? "" : message.substring(0, Math.min(message.length(), 300)));
        return result;
    }

    private static Map<String, AttributeValue> key(TableProfile profile, long sequence) {
        return Map.of(
                "pk", s(tenant(profile, sequence)),
                "sk", s(String.format(Locale.ROOT, "record-%012d", sequence)));
    }

    private static Map<String, Object> metric(
            long scanned, long returned, long requests, double seconds, long bytes) {
        var result = new LinkedHashMap<String, Object>();
        result.put("scanned", scanned);
        result.put("returned", returned);
        result.put("requests", requests);
        result.put("elapsedSeconds", seconds);
        result.put("scannedPerSecond", scanned / seconds);
        result.put("returnedPerSecond", returned / seconds);
        result.put("bytesRead", bytes);
        result.put("megabytesPerSecond", bytes / 1_048_576.0 / seconds);
        return result;
    }

    private static long estimatedBytes(Map<String, AttributeValue> item) {
        long bytes = 0;
        for (var entry : item.entrySet()) {
            bytes += entry.getKey().getBytes(StandardCharsets.UTF_8).length;
            bytes += estimatedValueBytes(entry.getValue());
        }
        return bytes;
    }

    private static long estimatedValueBytes(AttributeValue value) {
        if (value.s() != null) return value.s().getBytes(StandardCharsets.UTF_8).length;
        if (value.n() != null) return value.n().getBytes(StandardCharsets.UTF_8).length;
        if (value.b() != null) return value.b().asByteArray().length;
        if (Boolean.TRUE.equals(value.bool())
                || Boolean.FALSE.equals(value.bool())
                || Boolean.TRUE.equals(value.nul())) {
            return 1;
        }
        long bytes = 0;
        if (value.hasSs()) {
            for (String element : value.ss())
                bytes += element.getBytes(StandardCharsets.UTF_8).length;
        }
        if (value.hasNs()) {
            for (String element : value.ns())
                bytes += element.getBytes(StandardCharsets.UTF_8).length;
        }
        if (value.hasBs()) {
            for (var element : value.bs()) bytes += element.asByteArray().length;
        }
        if (value.hasL()) {
            bytes += 3;
            for (AttributeValue element : value.l()) bytes += 1 + estimatedValueBytes(element);
        }
        if (value.hasM()) {
            bytes += 3;
            for (var entry : value.m().entrySet()) {
                bytes += 1 + entry.getKey().getBytes(StandardCharsets.UTF_8).length;
                bytes += estimatedValueBytes(entry.getValue());
            }
        }
        return bytes;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readSeedManifest() throws Exception {
        if (!Files.exists(SEED_MANIFEST)) {
            throw new IllegalStateException("Missing Docker seed manifest: " + SEED_MANIFEST);
        }
        Map<String, Object> manifest =
                JsonMapper.builder().build().readValue(SEED_MANIFEST.toFile(), Map.class);
        Number baseline = (Number) manifest.get("baselineRecordsPerTable");
        Number primary = (Number) manifest.get("primaryRecords");
        Object mode = manifest.get("mode");
        Object itemShape = manifest.get("itemShape");
        if (baseline == null
                || primary == null
                || mode == null
                || itemShape == null
                || !ITEM_SHAPE.equals(itemShape.toString())
                || !LAB_MODE.equals(mode.toString())
                || baseline.intValue() != BASELINE_RECORDS
                || primary.intValue() != PRIMARY_RECORDS) {
            throw new IllegalStateException(
                    "Prepared dataset targets differ from benchmark configuration: "
                            + SEED_MANIFEST);
        }
        return Map.copyOf(manifest);
    }

    private static double elapsedSeconds(long started) {
        return Math.max(0.000_001, (System.nanoTime() - started) / 1_000_000_000.0);
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue n(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }

    private static final class ProjectionProgress {
        private long pages;
        private long examined;
        private long returned;
        private long bytes;
        private boolean completed;

        private ProjectionProgress() {}

        private ProjectionProgress(
                long pages, long examined, long returned, long bytes, boolean completed) {
            this.pages = pages;
            this.examined = examined;
            this.returned = returned;
            this.bytes = bytes;
            this.completed = completed;
        }

        private synchronized void add(ScanResponse page) {
            pages++;
            examined += page.scannedCount();
            returned += page.count();
            for (var value : page.items()) bytes += estimatedBytes(value);
        }

        private synchronized ProjectionCheckpointFile snapshot(
                String table,
                int recordsTarget,
                int pageSize,
                int segment,
                int totalSegments,
                Map<String, String> nextStartKey,
                boolean completed) {
            this.completed = completed;
            return new ProjectionCheckpointFile(
                    table,
                    recordsTarget,
                    pageSize,
                    segment,
                    totalSegments,
                    nextStartKey,
                    completed,
                    pages,
                    examined,
                    returned,
                    bytes);
        }

        private synchronized long pages() {
            return pages;
        }

        private synchronized long examined() {
            return examined;
        }

        private synchronized long returned() {
            return returned;
        }

        private synchronized long bytes() {
            return bytes;
        }

        private synchronized boolean completed() {
            return completed;
        }
    }

    private record ProjectionCheckpointIdentity(
            int schemaVersion,
            String table,
            int recordsTarget,
            int pageSize,
            int totalSegments,
            String itemShape,
            String projection) {}

    private record ProjectionCheckpointFile(
            String table,
            int recordsTarget,
            int pageSize,
            int segment,
            int totalSegments,
            Map<String, String> nextStartKey,
            boolean completed,
            long pages,
            long examined,
            long returned,
            long bytes) {}

    private record ProjectionRunState(int attempts, double accumulatedElapsedSeconds) {}

    private record TableProfile(String name, int payloadBytes, boolean skewed, int tenants) {}

    private record Expression(Map<String, String> names, Map<String, AttributeValue> values) {}
}
