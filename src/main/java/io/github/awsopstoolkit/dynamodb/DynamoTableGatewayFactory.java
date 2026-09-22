package io.github.awsopstoolkit.dynamodb;

import io.github.awsopstoolkit.configuration.DynamoProperties;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;

@Component
@ConditionalOnBean(DynamoDbEnhancedClient.class)
public final class DynamoTableGatewayFactory {
    private final DynamoDbEnhancedClient enhancedClient;
    private final DynamoProperties properties;

    public DynamoTableGatewayFactory(
            DynamoDbEnhancedClient enhancedClient, DynamoProperties properties) {
        this.enhancedClient = Objects.requireNonNull(enhancedClient);
        this.properties = Objects.requireNonNull(properties);
    }

    public <T> DynamoTableGateway<T> create(DynamoTableDescriptor<T> descriptor) {
        Objects.requireNonNull(descriptor);
        String physicalName = properties.requireTable(descriptor.logicalName());
        return new DynamoTableGateway<>(
                descriptor, enhancedClient.table(physicalName, descriptor.schema()));
    }
}
