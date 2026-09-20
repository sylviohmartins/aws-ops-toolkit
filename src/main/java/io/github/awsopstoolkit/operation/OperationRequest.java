package io.github.awsopstoolkit.operation;

import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

public record OperationRequest(@NotNull OperationMode mode, @NotNull JsonNode parameters) {}
