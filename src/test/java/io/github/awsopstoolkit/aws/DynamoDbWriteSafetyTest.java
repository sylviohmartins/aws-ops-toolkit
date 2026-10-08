package io.github.awsopstoolkit.aws;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionCheck;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.Update;

class DynamoDbWriteSafetyTest {
    private static final Map<String, AttributeValue> KEY =
            Map.of("pk", AttributeValue.fromS("synthetic"));

    @Test
    void rejectsUnconditionalTransactionBeforeAuthorizationOrDispatch() {
        var dispatches = new AtomicInteger();
        var service =
                service(
                        dispatches,
                        (action, resource) -> fail("Authorization should not be reached"));

        var request =
                TransactWriteItemsRequest.builder()
                        .clientRequestToken("synthetic-transaction-token")
                        .transactItems(
                                TransactWriteItem.builder()
                                        .update(
                                                Update.builder()
                                                        .tableName("synthetic-table")
                                                        .key(KEY)
                                                        .updateExpression("SET #s=:next")
                                                        .expressionAttributeNames(
                                                                Map.of("#s", "status"))
                                                        .expressionAttributeValues(
                                                                Map.of(
                                                                        ":next",
                                                                        AttributeValue.fromS(
                                                                                "DONE")))
                                                        .build())
                                        .build())
                        .build();

        assertThrows(IllegalArgumentException.class, () -> service.transactWrite(request));
        assertEquals(0, dispatches.get());
    }

    @Test
    void rejectsTransactionItemContainingMultipleActionsBeforeAuthorizationOrDispatch() {
        var dispatches = new AtomicInteger();
        var service =
                service(
                        dispatches,
                        (action, resource) -> fail("Authorization should not be reached"));

        var conditionalUpdate =
                Update.builder()
                        .tableName("synthetic-update")
                        .key(KEY)
                        .conditionExpression("attribute_exists(pk)")
                        .updateExpression("SET #s=:next")
                        .expressionAttributeNames(Map.of("#s", "status"))
                        .expressionAttributeValues(
                                Map.of(":next", AttributeValue.fromS("DONE")))
                        .build();
        var conditionalDelete =
                Delete.builder()
                        .tableName("synthetic-delete")
                        .key(KEY)
                        .conditionExpression("attribute_exists(pk)")
                        .build();

        var request =
                TransactWriteItemsRequest.builder()
                        .clientRequestToken("synthetic-transaction-token")
                        .transactItems(
                                TransactWriteItem.builder()
                                        .update(conditionalUpdate)
                                        .delete(conditionalDelete)
                                        .build())
                        .build();

        assertThrows(IllegalArgumentException.class, () -> service.transactWrite(request));
        assertEquals(0, dispatches.get());
    }

    @Test
    void dispatchesFullyConditionalTransactionAndAuthorizesEveryAction() throws Exception {
        var dispatches = new AtomicInteger();
        var authorizations = new ArrayList<String>();
        var service =
                service(
                        dispatches,
                        (action, resource) -> authorizations.add(action + "@" + resource));

        var request =
                TransactWriteItemsRequest.builder()
                        .clientRequestToken("synthetic-transaction-token")
                        .transactItems(
                                TransactWriteItem.builder()
                                        .put(
                                                Put.builder()
                                                        .tableName("synthetic-put")
                                                        .item(KEY)
                                                        .conditionExpression(
                                                                "attribute_not_exists(pk)")
                                                        .build())
                                        .build(),
                                TransactWriteItem.builder()
                                        .update(
                                                Update.builder()
                                                        .tableName("synthetic-update")
                                                        .key(KEY)
                                                        .conditionExpression(
                                                                "attribute_exists(pk)")
                                                        .updateExpression("SET #s=:next")
                                                        .expressionAttributeNames(
                                                                Map.of("#s", "status"))
                                                        .expressionAttributeValues(
                                                                Map.of(
                                                                        ":next",
                                                                        AttributeValue.fromS(
                                                                                "DONE")))
                                                        .build())
                                        .build(),
                                TransactWriteItem.builder()
                                        .delete(
                                                Delete.builder()
                                                        .tableName("synthetic-delete")
                                                        .key(KEY)
                                                        .conditionExpression(
                                                                "attribute_exists(pk)")
                                                        .build())
                                        .build(),
                                TransactWriteItem.builder()
                                        .conditionCheck(
                                                ConditionCheck.builder()
                                                        .tableName("synthetic-check")
                                                        .key(KEY)
                                                        .conditionExpression(
                                                                "attribute_exists(pk)")
                                                        .build())
                                        .build())
                        .build();

        var response = service.transactWrite(request);

        assertNotNull(response);
        assertEquals(1, dispatches.get());
        assertEquals(
                List.of(
                        "dynamodb:PutItem@synthetic-put",
                        "dynamodb:UpdateItem@synthetic-update",
                        "dynamodb:DeleteItem@synthetic-delete",
                        "dynamodb:ConditionCheckItem@synthetic-check"),
                authorizations);
    }

    private DynamoDbService service(
            AtomicInteger dispatches, WriteAuthorization authorization) {
        var client =
                (DynamoDbClient)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {DynamoDbClient.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("transactWriteItems")) {
                                        dispatches.incrementAndGet();
                                        return TransactWriteItemsResponse.builder().build();
                                    }
                                    if (method.getName().equals("serviceName")) {
                                        return "DynamoDb";
                                    }
                                    if (method.getName().equals("close")) {
                                        return null;
                                    }
                                    throw new UnsupportedOperationException(method.getName());
                                });
        return new DynamoDbService(
                client, new AwsCallGate(1, 1_000_000), authorization, 1000);
    }
}
