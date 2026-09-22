package io.github.awsopstoolkit.runtime;

import org.springframework.scheduling.annotation.Scheduled;

public final class RuntimeMaintenance {
    private final SqliteJournal journal;
    private final RuntimeProperties properties;

    public RuntimeMaintenance(SqliteJournal journal, RuntimeProperties properties) {
        this.journal = journal;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "PT24H", initialDelayString = "PT1M")
    public void expire() throws java.sql.SQLException {
        journal.expirePayloads(SqliteJournal.now() - properties.retention().toSeconds());
    }
}
