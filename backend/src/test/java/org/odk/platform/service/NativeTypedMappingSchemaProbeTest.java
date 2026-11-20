package org.odk.platform.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exact synthetic XForms; never reads historical submission answer XML.
 * A native same-type result remains advisory without grant rights.
 */
class NativeTypedMappingSchemaProbeTest {
    private final JavaRosaFormLoader javaRosa = new JavaRosaFormLoader();

    private String xform(String version, String type) {
        return """
            <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                    xmlns="http://www.w3.org/2002/xforms">
              <h:head><h:title>Household %s</h:title><model>
                <instance><data id="household" version="%s">
                    <age/><meta><instanceID/></meta>
                </data></instance>
                <bind nodeset="/data/age" type="%s"/>
              </model></h:head>
              <h:body><input ref="/data/age">
                <label>Age</label></input></h:body>
            </h:html>
            """.formatted(version, version, type);
    }

    @Test
    void independentJavaRosaVersionsConfirmIntegerButGrantNoRights() {
        var evidence = NativeTypedMappingSchemaProbe.inspect(
            xform("1", "int"), xform("2", "integer"),
            List.of("/data/age"), javaRosa);
        assertEquals(1, evidence.size());
        var result = evidence.get(0);
        assertEquals("STATIC_SAME_PATH_TYPE_CANDIDATE_ONLY",
            result.staticState());
        assertEquals("INTEGER", result.sourceNativeType());
        assertEquals("INTEGER", result.targetNativeType());
        assertEquals("NATIVE_SAME_PATH_TYPE_CANDIDATE_ONLY", result.state());
        assertFalse(result.canReadHistoricalAnswers());
        assertFalse(result.canTransfer());
        assertFalse(result.canFinalize());
        assertFalse(result.canSubmit());
    }

    @Test
    void changedTypeIsBlockedBeforeNativeParserCanTreatItAsCompatible() {
        var evidence = NativeTypedMappingSchemaProbe.inspect(
            xform("1", "int"), xform("2", "decimal"),
            List.of("/data/age"), javaRosa);
        assertEquals("BLOCKED_STATIC_POLICY", evidence.get(0).state());
        assertNull(evidence.get(0).sourceNativeType());
        assertNull(evidence.get(0).targetNativeType());
    }

    @Test
    void unsupportedTypeIsNeverTreatedAsMigrationPermission() {
        var evidence = NativeTypedMappingSchemaProbe.inspect(
            xform("1", "select1"), xform("2", "select1"),
            List.of("/data/age"), javaRosa);
        assertEquals("BLOCKED_STATIC_POLICY", evidence.get(0).state());
    }

    @Test
    void missingQuestionIsBlockedAndCannotLeakAnAnswer() {
        var evidence = NativeTypedMappingSchemaProbe.inspect(
            xform("1", "int"), xform("2", "int"),
            List.of("/data/missing"), javaRosa);
        assertEquals("BLOCKED_STATIC_POLICY", evidence.get(0).state());
    }

    @Test
    void invalidNativeFormCannotProduceTransferEvidence() {
        String old = xform("1", "int");
        String invalidTarget = xform("2", "int").replace(
            "<label>Age</label>", "<label>Age</label><bad_control/>");
        var evidence = NativeTypedMappingSchemaProbe.inspect(
            old, invalidTarget, List.of("/data/age"), javaRosa);
        assertEquals(1, evidence.size());
        assertTrue(List.of("NATIVE_SAME_PATH_TYPE_CANDIDATE_ONLY",
            "BLOCKED_NATIVE_FORM_PARSE", "BLOCKED_NATIVE_FIELD_UNRESOLVED",
            "BLOCKED_STATIC_NATIVE_DISAGREEMENT").contains(evidence.get(0).state()));
        assertFalse(evidence.get(0).canTransfer());
    }

    @Test
    void forgedOrRelativePathsAreRejectedBeforeNativeLoading() {
        assertThrows(IllegalArgumentException.class, () ->
            NativeTypedMappingSchemaProbe.inspect(
                xform("1", "int"), xform("2", "int"),
                List.of("../data/age"), javaRosa));
        assertThrows(IllegalArgumentException.class, () ->
            NativeTypedMappingSchemaProbe.inspect(
                xform("1", "int"), xform("2", "int"),
                List.of("/data/age", "/data/age"), javaRosa));
        assertThrows(IllegalArgumentException.class, () ->
            NativeTypedMappingSchemaProbe.inspect(
                xform("1", "int"), xform("2", "int"),
                List.of("/data/age"), null));
    }

    @Test
    void evidenceConstructorForbidsAllTransferAndUploadRights() {
        assertThrows(IllegalArgumentException.class, () -> new
            NativeTypedMappingSchemaProbe.Evidence(
                "/data/age", "STATIC_SAME_PATH_TYPE_CANDIDATE_ONLY",
                "INTEGER", "INTEGER", "NATIVE_SAME_PATH_TYPE_CANDIDATE_ONLY",
                true, false, false, false));
        assertThrows(IllegalArgumentException.class, () -> new
            NativeTypedMappingSchemaProbe.Evidence(
                "/data/age", "STATIC_SAME_PATH_TYPE_CANDIDATE_ONLY",
                "INTEGER", "INTEGER", "NATIVE_SAME_PATH_TYPE_CANDIDATE_ONLY",
                false, true, false, false));
        assertThrows(IllegalArgumentException.class, () -> new
            NativeTypedMappingSchemaProbe.Evidence(
                "/data/age", "STATIC_SAME_PATH_TYPE_CANDIDATE_ONLY",
                "INTEGER", "INTEGER", "NATIVE_SAME_PATH_TYPE_CANDIDATE_ONLY",
                false, false, true, false));
        assertThrows(IllegalArgumentException.class, () -> new
            NativeTypedMappingSchemaProbe.Evidence(
                "/data/age", "STATIC_SAME_PATH_TYPE_CANDIDATE_ONLY",
                "INTEGER", "INTEGER", "NATIVE_SAME_PATH_TYPE_CANDIDATE_ONLY",
                false, false, false, true));
    }
}
