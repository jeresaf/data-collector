package org.odk.platform.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Local POSIX storage journal. The writer and recovery acquire the same
 * database transaction-scoped bucket lock, even across JVMs. The database
 * namespace isolates installations; only our own durable marker names are
 * eligible. Legacy/unmarked files are NEVER scavenged.
 */
@Service
public class SubmissionAttachmentJournal {
    private static final Logger log = LoggerFactory.getLogger(SubmissionAttachmentJournal.class);
    private static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate recovery;

    @Value("${odk.storage.submissions-root:uploads/submissions}")
    private String submissionsRoot;
    @Value("${odk.attachment-journal.enabled:false}")
    private boolean enabled;
    @Value("${odk.attachment-journal.recovery-enabled:false}")
    private boolean recoveryEnabled;

    public SubmissionAttachmentJournal(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        recovery = new TransactionTemplate(manager);
        recovery.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        recovery.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        recovery.setTimeout(10);
    }

    public boolean enabled() { return enabled; }

    public record Pending(Path target, Path marker) {}

    public Pending begin(UUID submission) throws IOException {
        if (!enabled || !TransactionSynchronizationManager.isActualTransactionActive() ||
                !TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Managed attachment requires an active transaction");
        lock(submission);
        UUID namespace = namespace();
        Path root = root();
        Path folder = checkedDirectory(root.resolve(submission.toString()));
        Path journal = journalDirectory(root, namespace);
        UUID file = UUID.randomUUID();
        Pending pending = paths(root, journal, namespace, submission, file);
        if (Files.exists(pending.target(), NOFOLLOW)) throw new IOException("Attachment identity collision");
        Files.createFile(pending.marker());
        force(pending.marker());
        force(journal);
        // Register before opening a payload, so any later error is covered.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                try {
                    if (status == STATUS_ROLLED_BACK) removeAbandoned(pending);
                    else if (status == STATUS_COMMITTED) removeMarker(pending);
                    // UNKNOWN deliberately retains the marker for later reconciliation.
                } catch (IOException ex) {
                    log.warn("Attachment journal completion requires reconciliation");
                }
            }
        });
        Files.createFile(pending.target());
        return pending;
    }

    public void forcePayload(Path target) throws IOException {
        force(target);
        force(target.getParent());
    }

    private UUID namespace() {
        return java.util.Objects.requireNonNull(jdbc.queryForObject(
                "SELECT namespace FROM attachment_journal_identity WHERE id=1", UUID.class));
    }

    private void lock(UUID submission) {
        int bucket = submission.hashCode() & 31;
        Integer locked = jdbc.queryForObject(
                "SELECT bucket FROM attachment_journal_locks WHERE bucket=? FOR UPDATE", Integer.class, bucket);
        if (!Integer.valueOf(bucket).equals(locked)) throw new IllegalStateException("Attachment coordination unavailable");
    }

    private Path root() throws IOException {
        return checkedDirectory(Path.of(submissionsRoot).toAbsolutePath().normalize());
    }

    private static Path checkedDirectory(Path dir) throws IOException {
        // Persist directory entries from the nearest existing parent. Refuse
        // redirected/symlink paths before opening any journal/payload.
        if (!Files.exists(dir, NOFOLLOW)) {
            if (dir.getParent() == null) throw new IOException("Missing storage parent");
            checkedDirectory(dir.getParent());
            try { Files.createDirectory(dir); }
            catch (FileAlreadyExistsException race) { /* verify below */ }
            force(dir.getParent());
        }
        if (!Files.isDirectory(dir, NOFOLLOW) || !dir.toRealPath().equals(dir))
            throw new IOException("Unsafe attachment journal directory");
        return dir;
    }

    private static Path journalDirectory(Path root, UUID namespace) throws IOException {
        return checkedDirectory(checkedDirectory(root.resolve(".attachment-journal")).resolve(namespace.toString()));
    }

    private static Pending paths(Path root, Path journal, UUID namespace, UUID submission, UUID file) {
        return new Pending(root.resolve(submission.toString()).resolve("u08-" + namespace + "-" + file + ".media"),
                journal.resolve(submission + "_" + file + ".pending"));
    }

    private static void force(Path path) throws IOException {
        // Linux/POSIX deployment requirement: fsync file and parent directory.
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, NOFOLLOW)) {
            channel.force(true);
        }
    }

    private static void checkRegularOrMissing(Path path) throws IOException {
        if (Files.exists(path, NOFOLLOW) && !Files.isRegularFile(path, NOFOLLOW))
            throw new IOException("Unsafe journal entry");
    }

    private static void removeMarker(Pending pending) throws IOException {
        checkRegularOrMissing(pending.marker());
        Files.deleteIfExists(pending.marker());
        force(pending.marker().getParent());
    }

    private static void removeAbandoned(Pending pending) throws IOException {
        Path directory = pending.target().getParent();
        if (Files.exists(directory, NOFOLLOW)) {
            if (!Files.isDirectory(directory, NOFOLLOW) || !directory.toRealPath().equals(directory))
                throw new IOException("Unsafe attachment directory");
            checkRegularOrMissing(pending.target());
            Files.deleteIfExists(pending.target());
            force(directory);
        }
        removeMarker(pending);
    }

    @Scheduled(fixedDelayString = "${odk.attachment-journal.recovery-delay-ms:60000}")
    public void scheduledRecovery() {
        if (!enabled || !recoveryEnabled) return;
        try { recoverOnce(); }
        catch (IOException | RuntimeException ex) { log.warn("Attachment journal recovery unavailable"); }
    }

    /** Bounded pass, explicit operator/test call; no destructive action on uncertain DB state. */
    public int recoverOnce() throws IOException {
        if (!enabled) throw new IllegalStateException("Attachment journal disabled");
        UUID namespace = namespace();
        Path root = root();
        Path journal = journalDirectory(root, namespace);
        int reconciled = 0, visited = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(journal, "*.pending")) {
            for (Path marker : entries) {
                if (++visited > 256) break;
                String name = marker.getFileName().toString();
                if (name.length() != 81 || name.charAt(36) != '_' || !name.endsWith(".pending")) continue;
                UUID submission, file;
                try {
                    submission = UUID.fromString(name.substring(0, 36));
                    file = UUID.fromString(name.substring(37, 73));
                } catch (IllegalArgumentException malformed) { continue; }
                Pending pending = paths(root, journal, namespace, submission, file);
                if (!pending.marker().equals(marker)) continue;
                try {
                    if (Boolean.TRUE.equals(recovery.execute(status -> {
                        lock(submission);
                        try { return reconcile(pending, submission); }
                        catch (IOException ex) { throw new UncheckedIOException(ex); }
                    }))) reconciled++;
                } catch (RuntimeException uncertain) {
                    // A lock timeout, unavailable DB, malformed/missing committed
                    // payload or filesystem failure retains the marker.
                    log.warn("Attachment journal entry retained for reconciliation");
                }
            }
        }
        return reconciled;
    }

    private boolean reconcile(Pending pending, UUID submission) throws IOException {
        if (!Files.exists(pending.marker(), NOFOLLOW)) return false;
        if (!Files.isRegularFile(pending.marker(), NOFOLLOW) || Files.size(pending.marker()) != 0)
            throw new IOException("Invalid journal marker");
        record Stored(UUID submission, String driver, Long bytes, String hash) {}
        var rows = jdbc.query("""
                SELECT submission_id,storage_driver,file_size,content_hash
                FROM submission_attachments WHERE storage_path=?
                """, (rs, i) -> new Stored(rs.getObject("submission_id", UUID.class),
                    rs.getString("storage_driver"), rs.getObject("file_size", Long.class),
                    rs.getString("content_hash")), pending.target().toString());
        if (rows.isEmpty()) {
            removeAbandoned(pending);
        } else {
            if (rows.size() != 1) throw new IOException("Ambiguous attachment metadata");
            Stored row = rows.get(0);
            Path target = pending.target();
            if (!submission.equals(row.submission()) || !"local".equals(row.driver()) ||
                    row.bytes() == null || row.hash() == null ||
                    !Files.isRegularFile(target, NOFOLLOW) ||
                    !target.getParent().toRealPath().equals(target.getParent()) ||
                    Files.size(target) != row.bytes() || !row.hash().equals(hash(target)))
                throw new IOException("Committed attachment requires operator review");
            removeMarker(pending);
        }
        return true;
    }

    private static String hash(Path target) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(target, NOFOLLOW)) {
                byte[] bytes = new byte[8192];
                int n;
                while ((n = input.read(bytes)) != -1) digest.update(bytes, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
