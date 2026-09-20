package io.github.awsopstoolkit.aws;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

class DynamoDbCheckpointTest {
    private DynamoDbService service(Function<ScanRequest, ScanResponse> handler) {
        var client =
                (DynamoDbClient)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {DynamoDbClient.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("scan"))
                                        return handler.apply((ScanRequest) args[0]);
                                    throw new UnsupportedOperationException(method.getName());
                                });
        return new DynamoDbService(
                client,
                new AwsCallGate(1, 1000000),
                (action, resource) -> {
                    throw new SecurityException("Writes denied");
                },
                100);
    }

    @Test
    void emptyFilteredPageWithCursorDoesNotEndScan() throws Exception {
        var key = Map.of("pk", AttributeValue.fromS("cursor"));
        var calls = new AtomicInteger();
        var checkpoints = new AtomicInteger();
        var service =
                service(
                        request -> {
                            int index = calls.getAndIncrement();
                            if (index == 0)
                                return ScanResponse.builder()
                                        .count(0)
                                        .scannedCount(1)
                                        .lastEvaluatedKey(key)
                                        .build();
                            assertEquals(key, request.exclusiveStartKey());
                            return ScanResponse.builder().count(0).scannedCount(0).build();
                        });
        service.parallelScan(
                ScanRequest.builder().tableName("synthetic-table").build(),
                1,
                1,
                10,
                Map.of(),
                () -> false,
                (segment, page) -> {},
                checkpoint -> checkpoints.incrementAndGet());
        assertEquals(2, calls.get());
        assertEquals(2, checkpoints.get());
    }

    @Test
    void failedPageIsNeverCheckpointed() {
        var checkpoints = new AtomicInteger();
        var service = service(request -> ScanResponse.builder().count(1).scannedCount(1).build());
        assertThrows(
                IllegalStateException.class,
                () ->
                        service.parallelScan(
                                ScanRequest.builder().tableName("synthetic-table").build(),
                                1,
                                1,
                                10,
                                Map.of(),
                                () -> false,
                                (segment, page) -> {
                                    throw new IllegalStateException("Simulated sink failure");
                                },
                                checkpoint -> checkpoints.incrementAndGet()));
        assertEquals(0, checkpoints.get());
    }
}
