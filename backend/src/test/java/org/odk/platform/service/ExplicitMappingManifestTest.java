package org.odk.platform.service;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ExplicitMappingManifestTest {
    private static final UUID WORK = UUID.fromString("e6bddba9-e98b-49cb-8bed-dce91d400520");
    private static final UUID SOURCE = UUID.fromString("a8c07f51-b4c1-40a4-b31c-956d2ca5e048");
    private static final UUID TARGET = UUID.fromString("5a6f0bf6-795c-4d91-9861-16910542b4f1");
    private static final String HISTORICAL = "a".repeat(64), CURRENT = "b".repeat(64);
    private static final ExplicitMappingManifest.FieldRule RENAME = new ExplicitMappingManifest.FieldRule(
            "/data/former", "/data/renamed", ExplicitMappingManifest.ScalarType.TEXT);
    private static final ExplicitMappingManifest.FieldRule AGE = new ExplicitMappingManifest.FieldRule(
            "/data/age", "/data/age", ExplicitMappingManifest.ScalarType.INTEGER);

    private ExplicitMappingManifest manifest(List<ExplicitMappingManifest.FieldRule> rules) {
        return new ExplicitMappingManifest(WORK, SOURCE, TARGET, HISTORICAL, CURRENT, rules);
    }

    @Test void matchesAndroidCanonicalVectorAndBindsExactScope() {
        var one = manifest(List.of(RENAME, AGE));
        var two = manifest(List.of(AGE, RENAME));
        String expected = "DATA_COLLECTOR_EXPLICIT_MAPPING_V1\n" + WORK + "\n" + SOURCE + "\n" + TARGET +
                "\n" + HISTORICAL + "\n" + CURRENT +
                "\n2\n/data/age\t/data/age\tINTEGER\n/data/former\t/data/renamed\tTEXT\n";
        assertArrayEquals(expected.getBytes(StandardCharsets.US_ASCII), one.canonicalBytes());
        assertEquals(one.sha256(), two.sha256());
        assertTrue(one.matchesScope(WORK, SOURCE, TARGET, HISTORICAL, CURRENT));
        assertFalse(one.matchesScope(WORK, SOURCE, TARGET, CURRENT, HISTORICAL));
        assertFalse(one.matchesScope(UUID.randomUUID(), SOURCE, TARGET, HISTORICAL, CURRENT));
        assertNotEquals(one.sha256(), manifest(List.of(AGE)).sha256());
    }

    @Test void rejectsAmbiguityAndIdentityFields() {
        assertThrows(IllegalArgumentException.class, () -> manifest(List.of(RENAME, RENAME)));
        assertThrows(IllegalArgumentException.class, () -> manifest(List.of(RENAME,
                new ExplicitMappingManifest.FieldRule("/data/former", "/data/other",
                        ExplicitMappingManifest.ScalarType.TEXT))));
        assertThrows(IllegalArgumentException.class, () -> manifest(List.of(RENAME,
                new ExplicitMappingManifest.FieldRule("/data/other", "/data/renamed",
                        ExplicitMappingManifest.ScalarType.TEXT))));
        for (String unsafe : List.of("/data/meta/instanceID", "/data/deprecatedID",
                "/data/name[1]", "/data//name", "/data/name\n")) {
            assertThrows(IllegalArgumentException.class, () -> new ExplicitMappingManifest.FieldRule(
                    unsafe, "/data/renamed", ExplicitMappingManifest.ScalarType.TEXT));
        }
        assertThrows(IllegalArgumentException.class, () -> manifest(List.of()));
        assertThrows(UnsupportedOperationException.class, () -> manifest(List.of(RENAME)).rules().clear());
    }
}
