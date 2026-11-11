package com.uxplima.blysis.adapter.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.uxplima.blysis.domain.JobResult;
import com.uxplima.blysis.domain.Outcome;
import com.uxplima.uxmlib.storage.sql.Database;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlJournalTest {

    @TempDir
    Path folder;

    private @Nullable Database database;

    private SqlJournal open(Clock clock) {
        Database opened =
                Database.builder().sqlite(folder.resolve("journal.db")).build();
        database = opened;
        return SqlJournal.open(opened, clock);
    }

    private void closeDatabase() {
        Database open = database;
        if (open != null) {
            open.close();
        }
    }

    @AfterEach
    void close() {
        closeDatabase();
    }

    @Test
    @DisplayName("a started job is known, and still known after the file is opened again")
    void survivesARestart() {
        SqlJournal journal = open(Clock.systemUTC());
        journal.started("j1");
        closeDatabase();

        SqlJournal again = open(Clock.systemUTC());

        assertThat(again.knows("j1")).isTrue();
        assertThat(again.knows("j2")).isFalse();
        assertThat(again.unfinished()).containsExactly("j1");
    }

    @Test
    @DisplayName("an outcome is reported until the site acknowledges it")
    void keepsAnOutcomeUntilAcknowledged() {
        SqlJournal journal = open(Clock.systemUTC());
        journal.started("j1");
        journal.finished(new JobResult("j1", Outcome.FAILED, "Unknown command"));

        assertThat(journal.unreported()).containsExactly(new JobResult("j1", Outcome.FAILED, "Unknown command"));
        journal.acknowledged(List.of("j1"));

        assertThat(journal.unreported()).isEmpty();
        assertThat(journal.unfinished()).isEmpty();
        assertThat(journal.knows("j1")).isTrue();
    }

    @Test
    @DisplayName("an acknowledged job is forgotten only after a week")
    void sweepsOnlyOldRows() {
        Instant start = Instant.parse("2026-10-18T12:00:00Z");
        SqlJournal journal = open(Clock.fixed(start, ZoneOffset.UTC));
        journal.started("j1");
        journal.finished(new JobResult("j1", Outcome.DONE, null));
        journal.acknowledged(List.of("j1"));
        assertThat(journal.sweep()).isZero();
        closeDatabase();

        SqlJournal later = open(Clock.fixed(start.plus(Duration.ofDays(8)), ZoneOffset.UTC));

        assertThat(later.sweep()).isEqualTo(1);
        assertThat(later.knows("j1")).isFalse();
    }

    @Test
    @DisplayName("a deferred job is not known, starts afresh when offered again, and is then known")
    void aDeferredJobMayStartAgain() {
        SqlJournal journal = open(Clock.systemUTC());
        journal.started("j1");
        journal.finished(new JobResult("j1", Outcome.DEFERRED, "the player left"));
        journal.acknowledged(List.of("j1"));

        assertThat(journal.knows("j1")).isFalse();
        journal.started("j1");

        assertThat(journal.knows("j1")).isTrue();
        assertThat(journal.unfinished()).containsExactly("j1");
        journal.finished(new JobResult("j1", Outcome.DONE, null));
        assertThat(journal.unreported()).containsExactly(new JobResult("j1", Outcome.DONE, null));
    }

    @Test
    @DisplayName("a job that ran is never started afresh")
    void aRunJobIsNeverOverwritten() {
        SqlJournal journal = open(Clock.systemUTC());
        journal.started("j1");
        journal.finished(new JobResult("j1", Outcome.DONE, null));

        journal.started("j1");

        assertThat(journal.unfinished()).isEmpty();
        assertThat(journal.unreported()).containsExactly(new JobResult("j1", Outcome.DONE, null));
    }

    @Test
    @DisplayName("an outcome the site keeps refusing goes behind the ones it has not refused")
    void aRefusedOutcomeGoesToTheBack() {
        Instant start = Instant.parse("2026-10-18T12:00:00Z");
        SqlJournal journal = open(Clock.fixed(start, ZoneOffset.UTC));
        journal.started("stuck");
        journal.finished(new JobResult("stuck", Outcome.DONE, null));
        closeDatabase();
        SqlJournal later = open(Clock.fixed(start.plusSeconds(60), ZoneOffset.UTC));
        later.started("fresh");
        later.finished(new JobResult("fresh", Outcome.DONE, null));
        assertThat(later.unreported()).extracting(JobResult::jobId).containsExactly("stuck", "fresh");

        later.unaccepted(List.of("stuck"));

        assertThat(later.unreported()).extracting(JobResult::jobId).containsExactly("fresh", "stuck");
    }
}
