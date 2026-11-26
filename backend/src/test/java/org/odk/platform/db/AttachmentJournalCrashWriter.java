package org.odk.platform.db;

import org.odk.platform.service.SubmissionAttachmentJournal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Test-only child JVM: the parent kills it without running any shutdown or
 * transaction callbacks. Uses the production journal and the real PG database. */
public final class AttachmentJournalCrashWriter {
    public static void main(String[] args) {
        var source = new DriverManagerDataSource(
                System.getenv("U08_CRASH_JDBC_URL"), System.getenv("U08_CRASH_DB_USER"),
                System.getenv("U08_CRASH_DB_PASSWORD"));
        var manager = new DataSourceTransactionManager(source);
        var journal = new SubmissionAttachmentJournal(new JdbcTemplate(source), manager);
        Path root = Path.of(args[0]);
        ReflectionTestUtils.setField(journal, "submissionsRoot", root.toString());
        ReflectionTestUtils.setField(journal, "enabled", true);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            try {
                var p = journal.begin(UUID.fromString(args[1]));
                Files.write(p.target(), new byte[]{11, 22, 33});
                journal.forcePayload(p.target());
                Files.createFile(root.resolve("writer-ready"));
                Thread.sleep(120_000);
                throw new IllegalStateException("Parent did not terminate crash writer");
            } catch (Exception e) { throw new IllegalStateException(e); }
        });
    }
}
