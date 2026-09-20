package io.github.awsopstoolkit.operation;

/** One typed incident rule; infrastructure owns scheduling, persistence and admission. */
public interface OperationDefinition<I> {
    String type();

    /** Increment when selection, transformation or report semantics change. */
    String version();

    Class<I> inputType();

    long total(I input);

    void execute(I input, OperationContext context) throws Exception;
}
