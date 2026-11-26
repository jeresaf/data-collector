package org.odk.platform.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class ManagedMultipartSpoolTest {
    @TempDir Path temp;

    @Test
    void exclusiveOwnerRetainsActiveUploadAndRestartClearsAbandonedRequest() throws Exception {
        Path root = temp.resolve("spool");
        Path upload;
        try (var first = ManagedMultipartSpool.Spool.open(root)) {
            upload = Files.write(first.requests.resolve("upload-test.tmp"), new byte[]{1,2,3});
            assertThrows(java.nio.channels.OverlappingFileLockException.class,
                    () -> ManagedMultipartSpool.Spool.open(root));
            assertArrayEquals(new byte[]{1,2,3}, Files.readAllBytes(upload));
        }
        try (var second = ManagedMultipartSpool.Spool.open(root)) {
            assertFalse(Files.exists(upload));
            assertEquals(root.resolve("requests"), second.requests);
        }
    }

    @Test
    void refusesUnownedNonemptyDirectoryAndRetainsUnexpectedEntries() throws Exception {
        Path root = Files.createDirectory(temp.resolve("unowned"));
        Path unrelated = Files.write(root.resolve("important"), new byte[]{9});
        assertThrows(IOException.class, () -> ManagedMultipartSpool.Spool.open(root));
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(unrelated));
        Path owned = temp.resolve("owned");
        try (var first = ManagedMultipartSpool.Spool.open(owned)) {
            Files.write(first.requests.resolve("payload"), new byte[]{4});
            Files.createDirectory(first.requests.resolve("unexpected"));
        }
        assertThrows(IOException.class, () -> ManagedMultipartSpool.Spool.open(owned));
        assertArrayEquals(new byte[]{4}, Files.readAllBytes(owned.resolve("requests/payload")));
    }
}
