package io.wildsmp.anticheat.backend.license;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LicenseStoreTest {

    @Test
    void mintCreatesAPendingRecordWithAFreshCode(@TempDir Path dir) {
        try (LicenseStore store = new LicenseStore(dir.resolve("test.db"))) {
            LicenseStore.ServerRecord record = store.mint("wildsmp", "1.21.4");

            assertTrue(record.code().startsWith("ANTICHEAT-"));
            assertEquals(LicenseStore.State.PENDING, record.state());
            assertEquals(0L, record.seq());
            assertEquals("wildsmp", record.serverName());
        }
    }

    @Test
    void findReturnsWhatWasMinted(@TempDir Path dir) {
        try (LicenseStore store = new LicenseStore(dir.resolve("test.db"))) {
            LicenseStore.ServerRecord minted = store.mint("duels", "1.21.4");

            Optional<LicenseStore.ServerRecord> found = store.find(minted.code());

            assertTrue(found.isPresent());
            assertEquals(minted.code(), found.get().code());
        }
    }

    @Test
    void findIsEmptyForAnUnknownCode(@TempDir Path dir) {
        try (LicenseStore store = new LicenseStore(dir.resolve("test.db"))) {
            assertTrue(store.find("ANTICHEAT-0000-0000-0000").isEmpty());
        }
    }

    @Test
    void updateStateBumpsSeqAndPersists(@TempDir Path dir) {
        try (LicenseStore store = new LicenseStore(dir.resolve("test.db"))) {
            LicenseStore.ServerRecord minted = store.mint("ffa", "1.21.4");

            store.updateState(minted.code(), LicenseStore.State.APPROVED);

            LicenseStore.ServerRecord updated = store.find(minted.code()).orElseThrow();
            assertEquals(LicenseStore.State.APPROVED, updated.state());
            assertEquals(1L, updated.seq());
        }
    }

    @Test
    void firstBindWinsSecondBindWithDifferentInstanceFails(@TempDir Path dir) {
        try (LicenseStore store = new LicenseStore(dir.resolve("test.db"))) {
            LicenseStore.ServerRecord minted = store.mint("wildsmp", "1.21.4");

            assertTrue(store.bindInstance(minted.code(), "instance-a", "10.0.0.1"));
            assertFalse(store.bindInstance(minted.code(), "instance-b", "10.0.0.2"));

            assertEquals("instance-a", store.find(minted.code()).orElseThrow().instanceId());
        }
    }

    @Test
    void reBindingTheSameInstanceSucceeds(@TempDir Path dir) {
        try (LicenseStore store = new LicenseStore(dir.resolve("test.db"))) {
            LicenseStore.ServerRecord minted = store.mint("wildsmp", "1.21.4");
            store.bindInstance(minted.code(), "instance-a", "10.0.0.1");

            assertTrue(store.bindInstance(minted.code(), "instance-a", "10.0.0.1"));
        }
    }

    @Test
    void appendAuditAndAuditForRoundTrip(@TempDir Path dir) {
        try (LicenseStore store = new LicenseStore(dir.resolve("test.db"))) {
            // mint() itself already appends a "minted" audit event - this test only adds one more.
            LicenseStore.ServerRecord minted = store.mint("wildsmp", "1.21.4");

            store.appendAudit(minted.code(), "accepted");

            List<String> events = store.auditFor(minted.code());
            assertEquals(List.of("minted", "accepted"), events);
        }
    }

    @Test
    void listAllReturnsEveryMintedServer(@TempDir Path dir) {
        try (LicenseStore store = new LicenseStore(dir.resolve("test.db"))) {
            store.mint("wildsmp", "1.21.4");
            store.mint("duels", "1.21.4");

            assertEquals(2, store.listAll().size());
        }
    }

    @Test
    void stateSurvivesReopeningTheStoreAgainstTheSameFile(@TempDir Path dir) {
        Path dbPath = dir.resolve("test.db");
        try (LicenseStore store = new LicenseStore(dbPath)) {
            LicenseStore.ServerRecord minted = store.mint("wildsmp", "1.21.4");

            try (LicenseStore reopened = new LicenseStore(dbPath)) {
                assertTrue(reopened.find(minted.code()).isPresent());
            }
        }
    }
}
