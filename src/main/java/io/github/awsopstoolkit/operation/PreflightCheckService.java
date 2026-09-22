package io.github.awsopstoolkit.operation;

import io.github.awsopstoolkit.checkpoint.FileCheckpointStore;
import io.github.awsopstoolkit.configuration.ToolkitProperties;
import java.io.IOException;
import org.springframework.stereotype.Service;

@Service
public final class PreflightCheckService {
    private final ToolkitProperties properties;
    private final FileCheckpointStore store;

    public PreflightCheckService(ToolkitProperties properties, FileCheckpointStore store) {
        this.properties = properties;
        this.store = store;
    }

    public void check(OperationMode mode, long total, long alreadyReserved) throws IOException {
        if (properties.environment() != ToolkitProperties.Environment.LOCAL
                || mode != OperationMode.DRY_RUN) {
            throw new IllegalArgumentException("Synthetic example requires LOCAL and DRY_RUN");
        }
        if (total < 1 || total > SyntheticOperationLimits.MAX_RECORDS)
            throw new IllegalArgumentException("Invalid record count");
        long required =
                Math.addExact(
                        properties.minimumFreeBytes(),
                        Math.addExact(estimate(total), alreadyReserved));
        if (store.freeBytes() < required)
            throw new IllegalStateException("Insufficient disk budget");
    }

    public long estimate(long records) {
        return records * SyntheticOperationLimits.ESTIMATED_BYTES_PER_RECORD
                + ((records + properties.pageSize() - 1) / properties.pageSize())
                        * SyntheticOperationLimits.ESTIMATED_BYTES_PER_PAGE;
    }

    public void checkDisk() throws IOException {
        if (store.freeBytes() < properties.minimumFreeBytes())
            throw new IOException("Disk reserve reached");
    }
}
