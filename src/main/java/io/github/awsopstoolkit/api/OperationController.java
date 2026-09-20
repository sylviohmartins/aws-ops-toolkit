package io.github.awsopstoolkit.api;

import io.github.awsopstoolkit.checkpoint.FileCheckpointStore;
import io.github.awsopstoolkit.operation.*;
import jakarta.validation.Valid;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/operations")
public final class OperationController {
    private final OperationExecutor executor;
    private final FileCheckpointStore store;

    public OperationController(OperationExecutor executor, FileCheckpointStore store) {
        this.executor = executor;
        this.store = store;
    }

    @PostMapping("/{type}")
    public ResponseEntity<OperationSnapshot> start(
            @PathVariable String type, @Valid @RequestBody OperationRequest request)
            throws IOException {
        var snapshot = executor.start(type, request);
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/operations/" + snapshot.operationId()))
                .body(snapshot);
    }

    @GetMapping("/{id}")
    public OperationSnapshot status(@PathVariable UUID id) throws IOException {
        return executor.status(id);
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<OperationSnapshot> pause(@PathVariable UUID id) throws IOException {
        return ResponseEntity.accepted().body(executor.requestStop(id, false));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<OperationSnapshot> cancel(@PathVariable UUID id) throws IOException {
        return ResponseEntity.accepted().body(executor.requestStop(id, true));
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<OperationSnapshot> resume(@PathVariable UUID id) throws IOException {
        return ResponseEntity.accepted().body(executor.resume(id));
    }

    @GetMapping(value = "/{id}/report", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<StreamingResponseBody> report(@PathVariable UUID id) throws IOException {
        var snapshot = executor.status(id);
        return ResponseEntity.ok()
                .contentType(
                        org.springframework.http.MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header("Content-Disposition", "attachment; filename=report-" + id + ".csv")
                .body(output -> store.report(snapshot, output));
    }
}
