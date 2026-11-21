package org.odk.platform.openrosa;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityListSnapshotLedgerCoordinatorTest {
    private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID FORM = UUID.fromString("00000000-0000-0000-0000-000000000303");
    private static final String ID1 = "d6b8a5e0-79d8-46e7-aab9-5bf1a57162ad";
    private static final String ID2 = "34f2ce6b-34e3-49e0-9db8-5c194fca56da";
    private static final byte[] OLD = ("name,label,__version\n" + ID1 + ",Original,1\n"
            + ID2 + ",Second,1\n").getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEXT = ("name,label,__version\n" + ID1 + ",Current,2\n"
            + ID2 + ",Second,2\n").getBytes(StandardCharsets.UTF_8);
    private static final byte[] REMOVAL = ("name,label,__version\n" + ID1 + ",Current,2\n")
            .getBytes(StandardCharsets.UTF_8);
    private static final EntityListSnapshotLedgerCoordinator.Key KEY =
            new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, "households.csv");
    private static final Map<String, Object> TYPE = Map.of("odkMediaType", "entityList");

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
    }

    /**
     * Test-only, process-local fake, NOT a production durability implementation.
     * All CAS operations compare both the previous bytes and descriptor.
     */
    private static final class FakeStore implements EntityListSnapshotLedgerCoordinator.Store {
        private EntityListSnapshotLedgerCoordinator.Head head;
        private int appendCount;

        private FakeStore(EntityListSnapshotLedgerCoordinator.Head head) {
            this.head = head;
        }

        @Override
        public synchronized Optional<EntityListSnapshotLedgerCoordinator.Head> currentHead(
                EntityListSnapshotLedgerCoordinator.Key key) {
            return Optional.of(new EntityListSnapshotLedgerCoordinator.Head(
                    head.evidence(), head.bytes()));
        }

        @Override
        public synchronized boolean compareAndAppend(
                EntityListSnapshotLedgerCoordinator.Key key,
                EntityListSnapshotLedgerCoordinator.Head expected,
                EntityListSnapshotLedgerCoordinator.Head proposed) {
            if (!head.evidence().equals(expected.evidence())
                    || !java.util.Arrays.equals(head.bytes(), expected.bytes())) {
                return false;
            }
            head = new EntityListSnapshotLedgerCoordinator.Head(
                    proposed.evidence(), proposed.bytes());
            appendCount++;
            return true;
        }
    }

    private static FakeStore seeded() throws Exception {
        var snapshot = EntityListSnapshotEvidence.fromVerifiedBytes(
                PROJECT, FORM, "households.csv", 1, TYPE, OLD, hash(OLD), 2L).orElseThrow();
        return new FakeStore(new EntityListSnapshotLedgerCoordinator.Head(snapshot, OLD));
    }

    @Test
    void consecutiveRevisionIsAssignedFromHeadAndAppendedOnce() throws Exception {
        var store = seeded();
        var outcome = EntityListSnapshotLedgerCoordinator.propose(
                store, KEY, TYPE, NEXT, hash(NEXT), 2L);
        assertEquals(EntityListSnapshotLedgerCoordinator.Outcome.APPENDED, outcome.outcome());
        assertEquals(2L, outcome.proposedRevision());
        assertEquals(1, store.appendCount);
        assertEquals(2L, store.head.evidence().revision());
        assertEquals(EntityListSnapshotLedgerCoordinator.Outcome.UNCHANGED,
                EntityListSnapshotLedgerCoordinator.propose(
                        store, KEY, TYPE, NEXT, hash(NEXT), 2L).outcome());
        assertEquals(1, store.appendCount);
    }

    @Test
    void removalsAlwaysRequireApprovalAndDoNotReachAppendPort() throws Exception {
        var store = seeded();
        var result = EntityListSnapshotLedgerCoordinator.propose(
                store, KEY, TYPE, REMOVAL, hash(REMOVAL), 1L);
        assertEquals(EntityListSnapshotLedgerCoordinator.Outcome.REMOVAL_APPROVAL_REQUIRED,
                result.outcome());
        assertEquals(java.util.Set.of(UUID.fromString(ID2)), result.missingIds());
        assertEquals(0, store.appendCount);
    }

    @Test
    void badHashWrongCountAndUnseededStoreCannotAppend() throws Exception {
        var store = seeded();
        assertEquals(EntityListSnapshotLedgerCoordinator.Outcome.INVALID_OR_NOT_INITIALIZED,
                EntityListSnapshotLedgerCoordinator.propose(
                        store, KEY, TYPE, NEXT, hash(OLD), 2L).outcome());
        assertEquals(EntityListSnapshotLedgerCoordinator.Outcome.INVALID_OR_NOT_INITIALIZED,
                EntityListSnapshotLedgerCoordinator.propose(
                        store, KEY, TYPE, NEXT, hash(NEXT), 1L).outcome());
        EntityListSnapshotLedgerCoordinator.Store absent = new EntityListSnapshotLedgerCoordinator.Store() {
            @Override
            public Optional<EntityListSnapshotLedgerCoordinator.Head> currentHead(
                    EntityListSnapshotLedgerCoordinator.Key key) {
                return Optional.empty();
            }

            @Override
            public boolean compareAndAppend(EntityListSnapshotLedgerCoordinator.Key key,
                    EntityListSnapshotLedgerCoordinator.Head expected,
                    EntityListSnapshotLedgerCoordinator.Head proposed) {
                throw new AssertionError("uninitialized ledger must not be written");
            }
        };
        assertEquals(EntityListSnapshotLedgerCoordinator.Outcome.INVALID_OR_NOT_INITIALIZED,
                EntityListSnapshotLedgerCoordinator.propose(
                        absent, KEY, TYPE, NEXT, hash(NEXT), 2L).outcome());
        assertEquals(0, store.appendCount);
    }

    @Test
    void concurrentUpdatesCannotAppendTwiceToSameHead() throws Exception {
        var store = seeded();
        int workers = 6;
        var pool = Executors.newFixedThreadPool(workers);
        var start = new CountDownLatch(1);
        var completed = new CountDownLatch(workers);
        var appended = new AtomicInteger();
        try {
            for (int i = 0; i < workers; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        var status = EntityListSnapshotLedgerCoordinator.propose(
                                store, KEY, TYPE, NEXT, hash(NEXT), 2L).outcome();
                        if (status == EntityListSnapshotLedgerCoordinator.Outcome.APPENDED) {
                            appended.incrementAndGet();
                        } else if (status != EntityListSnapshotLedgerCoordinator.Outcome.CONFLICT
                                && status != EntityListSnapshotLedgerCoordinator.Outcome.UNCHANGED) {
                            throw new AssertionError("unexpected status: " + status);
                        }
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    } finally {
                        completed.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(completed.await(10, TimeUnit.SECONDS));
            assertEquals(1, appended.get());
            assertEquals(1, store.appendCount);
            assertEquals(2L, store.head.evidence().revision());
        } finally {
            pool.shutdownNow();
        }
    }
}
