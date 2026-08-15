package org.odk.platform.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class XFormRuntimePreprocessorTest {
    @Test
    void discoversExternalDatasetNamesFromInstanceAndPulldata() {
        String xml = """
                <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns="http://www.w3.org/2002/xforms">
                  <h:head><model>
                    <instance id="cities" src="jr://file/cities.csv"/>
                    <bind nodeset="/data/name" calculate="pulldata('people','name','id', /data/id)"/>
                  </model></h:head><h:body/></h:html>
                """;
        var names = XFormRuntimePreprocessor.referencedExternalDatasetNames(xml);
        assertTrue(names.contains("cities"));
        assertTrue(names.contains("people"));
    }

    @Test
    void prepareRejectsBlankAndLeavesSimpleXFormStable() {
        assertThrows(IllegalArgumentException.class, () -> XFormRuntimePreprocessor.prepare(" ", Map.of()));
        String xml = "<h:html xmlns:h=\"http://www.w3.org/1999/xhtml\"><h:head/><h:body/></h:html>";
        var prepared = XFormRuntimePreprocessor.prepare(xml, Map.of("unused", "x".getBytes(StandardCharsets.UTF_8)));
        assertEquals(xml, prepared.xml());
    }
}
