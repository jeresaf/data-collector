package org.odk.platform.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FormVersionSchemaDiffTest {

    private static final String V1 = """
            <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                    xmlns="http://www.w3.org/2002/xforms">
              <h:head><model><instance><data id="survey"/></instance>
                <bind nodeset="/data/name" type="string"/>
                <bind nodeset="/data/age" type="int"/>
                <bind nodeset="/data/members/member_name" type="string"/>
              </model></h:head>
              <h:body><repeat nodeset="/data/members"><label>Members</label></repeat></h:body>
            </h:html>
            """;

    private static final String V2 = """
            <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                    xmlns="http://www.w3.org/2002/xforms">
              <h:head><model><instance><data id="survey"/></instance>
                <bind nodeset="/data/name" type="decimal"/>
                <bind nodeset="/data/ageYears" type="string"/>
                <bind nodeset="/data/members/member_name" type="string"/>
              </model></h:head>
              <h:body><repeat nodeset="/data/citizens"><label>Citizens</label></repeat></h:body>
            </h:html>
            """;

    @Test
    void identifiesRenamesAsUnmappedRemovedAddedAndDetectsRetypesAndRepeatChanges() {
        var report = FormVersionSchemaDiff.compare(V1, V2);

        assertEquals("BIND_DELTA_REVIEW_REQUIRED", report.status());
        assertTrue(report.mappingApprovalRequired());
        assertTrue(report.warnings().isEmpty());
        List<String> differences = report.changes().stream()
                .map(change -> change.kind() + ":" + change.path())
                .toList();
        assertTrue(differences.contains("REMOVED:/data/age"));
        assertTrue(differences.contains("ADDED:/data/ageYears"));
        assertTrue(differences.contains("TYPE_CHANGED:/data/name"));
        assertTrue(differences.contains("REPEAT_REMOVED:/data/members"));
        assertTrue(differences.contains("REPEAT_ADDED:/data/citizens"));
        assertFalse(differences.contains("TYPE_CHANGED:/data/members/member_name"));

        var retyped = report.changes().stream()
                .filter(change -> change.path().equals("/data/name"))
                .findFirst().orElseThrow();
        assertEquals("string", retyped.sourceType());
        assertEquals("decimal", retyped.targetType());
        assertFalse(differences.stream().anyMatch(value -> value.contains("RENAMED")),
                "A lexical similarity is never approval for a renamed field");
    }

    @Test
    void identicalBindInventoryStillRequiresReviewOfUncomparedSemantics() {
        var report = FormVersionSchemaDiff.compare(V1,
                V1.replace("<bind nodeset=\"/data/age\" type=\"int\"/>",
                        "<bind nodeset=\"/data/age\" type=\"int\" required=\"true()\"/>"));

        assertEquals("NO_BIND_DELTA_REVIEW_REQUIRED", report.status());
        assertTrue(report.mappingApprovalRequired(),
                "Identical bind paths and types do not prove constraints are compatible");
        assertTrue(report.changes().isEmpty());
        assertTrue(report.warnings().isEmpty());
    }

    @Test
    void missingUnsafeAndRelativeSourceSchemasFailClosed() {
        var unavailable = FormVersionSchemaDiff.compare(null, V2);
        assertEquals("SOURCE_SCHEMA_UNAVAILABLE", unavailable.status());
        assertTrue(unavailable.mappingApprovalRequired());
        assertTrue(unavailable.changes().isEmpty());

        String unsafe = "<!DOCTYPE h:html [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + V1;
        var rejected = FormVersionSchemaDiff.compare(unsafe, V2);
        assertEquals("UNKNOWN_REVIEW_REQUIRED", rejected.status());
        assertTrue(rejected.mappingApprovalRequired());
        assertFalse(rejected.warnings().isEmpty());

        var relative = FormVersionSchemaDiff.compare(
                V1.replace("/data/age", "../age"), V2);
        assertEquals("UNKNOWN_REVIEW_REQUIRED", relative.status());
        assertTrue(relative.mappingApprovalRequired());
        assertTrue(relative.warnings().stream()
                .anyMatch(message -> message.contains("unsupported or relative")));
    }
}
