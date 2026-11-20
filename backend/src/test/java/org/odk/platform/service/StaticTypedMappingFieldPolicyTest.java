package org.odk.platform.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Static XForm policy is only an advisory screen, not JavaRosa validation,
 * authorized access to old answers, signed grants or a source copy.
 */
class StaticTypedMappingFieldPolicyTest {

    private String form(String type, String extraBind,
                        String extraBody, boolean duplicate) {
        String bind = type == null ? "" :
            "<bind nodeset=\"/data/age\" type=\"" + type + "\"" +
                extraBind + "/>";
        return """
            <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                    xmlns="http://www.w3.org/2002/xforms">
              <h:head><h:title>Static pilot</h:title>
                <model><instance><data id="household"><age/><name/>
                    <meta><instanceID/></meta>
                </data></instance>%s</model></h:head>
              <h:body>
                <input ref="/data/age"><label>Age</label></input>
                %s
                <input ref="/data/name"><label>Name</label></input>
                %s
              </h:body>
            </h:html>
            """.formatted(bind, extraBody, duplicate
                    ? "<input ref=\"/data/age\"><label>Duplicate</label></input>"
                    : "");
    }

    private String form(String type) {
        return form(type, "", "", false);
    }

    @Test
    void samePathIntegerIsOnlyStaticCandidateNotGrantedPermission() {
        var findings = StaticTypedMappingFieldPolicy.inspect(
            form("int"), form("integer"), List.of("/data/age"));
        assertEquals(1, findings.size());
        var finding = findings.get(0);
        assertEquals("INTEGER", finding.sourceType());
        assertEquals("INTEGER", finding.targetType());
        assertEquals("STATIC_SAME_PATH_TYPE_CANDIDATE_ONLY", finding.state());
    }

    @Test
    void implicitTextBindingIsConservativelyScreened() {
        var findings = StaticTypedMappingFieldPolicy.inspect(
            form(null), form(null), List.of("/data/name"));
        assertEquals("TEXT", findings.get(0).sourceType());
        assertEquals("STATIC_SAME_PATH_TYPE_CANDIDATE_ONLY",
            findings.get(0).state());
    }

    @Test
    void changedTypeIsNotStaticallyCompatible() {
        var findings = StaticTypedMappingFieldPolicy.inspect(
            form("int"), form("decimal"), List.of("/data/age"));
        assertEquals("BLOCKED_TYPE_CHANGE", findings.get(0).state());
    }

    @Test
    void unsafeDynamicBindRepeatAndDuplicateControlsFailClosed() {
        assertEquals("BLOCKED_DYNAMIC_OR_EXTERNAL_FORM",
            StaticTypedMappingFieldPolicy.inspect(
                form("int"), form("int", " calculate=\"1+1\"", "", false),
                List.of("/data/age")).get(0).state());
        assertEquals("BLOCKED_DYNAMIC_OR_EXTERNAL_FORM",
            StaticTypedMappingFieldPolicy.inspect(
                form("int"), form("int",
                    "", "<repeat nodeset=\"/data/rows\"><input ref=\"/data/age\"/></repeat>",
                    false), List.of("/data/age")).get(0).state());
        assertEquals("BLOCKED_DYNAMIC_OR_EXTERNAL_FORM",
            StaticTypedMappingFieldPolicy.inspect(
                form("int"), form("int", "",
                    "<group relevant=\"/data/name != ''\">" +
                    "<input ref=\"/data/age\"><label>Age</label></input>" +
                    "</group>", false),
                List.of("/data/age")).get(0).state());
        assertEquals("BLOCKED_DYNAMIC_OR_EXTERNAL_FORM",
            StaticTypedMappingFieldPolicy.inspect(
                form("int"), form("int", "",
                    "<select1 ref=\"/data/name\"><label>Choice</label>" +
                    "<item><label>A</label><value>a</value></item></select1>", false),
                List.of("/data/age")).get(0).state());
        assertEquals("BLOCKED_MISSING_OR_AMBIGUOUS_FIELD",
            StaticTypedMappingFieldPolicy.inspect(
                form("int"), form("int", "", "", true),
                List.of("/data/age")).get(0).state());
    }

    @Test
    void metadataAndUnmappedPathNeverQualify() {
        var findings = StaticTypedMappingFieldPolicy.inspect(
            form(null), form(null),
            List.of("/data/meta/instanceID", "/data/unknown"));
        assertEquals("BLOCKED_METADATA", findings.get(0).state());
        assertEquals("BLOCKED_MISSING_OR_AMBIGUOUS_FIELD",
            findings.get(1).state());
    }

    @Test
    void externalEntitiesAndDoctypeAreRejectedRatherThanDereferenced() {
        String withDoctype = "<!DOCTYPE h:html [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
            + form("int");
        assertThrows(IllegalArgumentException.class, () ->
            StaticTypedMappingFieldPolicy.inspect(
                withDoctype, form("int"), List.of("/data/age")));
    }

    @Test
    void noPathDuplicationRelativePathsOrMassInventoryAllowed() {
        assertThrows(IllegalArgumentException.class, () ->
            StaticTypedMappingFieldPolicy.inspect(
                form("int"), form("int"), List.of("/data/age", "/data/age")));
        assertThrows(IllegalArgumentException.class, () ->
            StaticTypedMappingFieldPolicy.inspect(
                form("int"), form("int"), List.of("../data/age")));
        assertThrows(IllegalArgumentException.class, () ->
            StaticTypedMappingFieldPolicy.inspect(
                form("int"), form("int"),
                java.util.Collections.nCopies(31, "/data/age")));
    }
}
