package io.github.awsopstoolkit.operation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class OperationRegistry {
    private final Map<String, OperationDefinition<?>> definitions;

    public OperationRegistry(List<OperationDefinition<?>> operations) {
        var map = new HashMap<String, OperationDefinition<?>>();
        for (var operation : operations) {
            if (operation.type() == null
                    || operation.type().isBlank()
                    || operation.version() == null
                    || operation.version().isBlank()) {
                throw new IllegalStateException("Operation type and version are required");
            }
            if (map.putIfAbsent(operation.type(), operation) != null) {
                throw new IllegalStateException("Duplicate operation type");
            }
        }
        definitions = Map.copyOf(map);
    }

    public OperationDefinition<?> require(String type) {
        var definition = definitions.get(type);
        if (definition == null) throw new IllegalArgumentException("Unknown operation type");
        return definition;
    }
}
