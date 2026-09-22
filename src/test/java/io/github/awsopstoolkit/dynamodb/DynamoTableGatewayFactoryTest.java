package io.github.awsopstoolkit.dynamodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.awsopstoolkit.configuration.DynamoProperties;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

class DynamoTableGatewayFactoryTest {
    @Test
    void resolvesPhysicalNameOnlyFromConfiguration() {
        var client = mock(DynamoDbEnhancedClient.class);
        @SuppressWarnings("unchecked")
        TableSchema<Row> schema = mock(TableSchema.class);
        @SuppressWarnings("unchecked")
        DynamoDbTable<Row> table = mock(DynamoDbTable.class);
        when(client.table("dev-payments-v2", schema)).thenReturn(table);
        when(table.tableName()).thenReturn("dev-payments-v2");

        var descriptor = new DynamoTableDescriptor<>("payments", schema, "id", Set.of());
        var factory =
                new DynamoTableGatewayFactory(
                        client, new DynamoProperties(100, Map.of("payments", "dev-payments-v2")));

        var gateway = factory.create(descriptor);

        assertEquals("dev-payments-v2", gateway.physicalTableName());
        verify(client).table("dev-payments-v2", schema);
    }

    private record Row(String id) {}
}
