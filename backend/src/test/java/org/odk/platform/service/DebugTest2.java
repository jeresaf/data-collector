package org.odk.platform.service;

import org.javarosa.core.model.FormDef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DebugTest2 {

    private XFormBindExtractor bindExtractor;
    private JavaRosaFormLoader formLoader;

    private static final String VALID_XFORM_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns:jr="http://openrosa.org/javarosa" xmlns="http://www.w3.org/2002/xforms">
                <h:head>
                    <h:title>Test Form</h:title>
                    <model>
                        <instance>
                            <data id="test_form" version="1.0.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                <name/>
                                <age/>
                                <email/>
                                <meta>
                                    <instanceID/>
                                </meta>
                            </data>
                        </instance>
                        <bind nodeset="/data/name" type="string" required="true()"/>
                        <bind nodeset="/data/age" type="int" constraint=". &gt; 0 and . &lt; 120" required="true()"/>
                        <bind nodeset="/data/email" type="string" constraint="regex(., '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}')" required="false()"/>
                    </model>
                </h:head>
                <h:body>
                    <group ref="/data">
                        <input ref="/data/name">
                            <label>Name</label>
                        </input>
                        <input ref="/data/age">
                            <label>Age</label>
                        </input>
                        <input ref="/data/email">
                            <label>Email</label>
                        </input>
                    </group>
                </h:body>
            </h:html>
            """;

    @Test
    void debugRequired() {
        formLoader = new JavaRosaFormLoader();
        bindExtractor = new XFormBindExtractor(formLoader);
        
        FormDef formDef = formLoader.loadFormFromXml(VALID_XFORM_XML);
        XFormBindExtractor.BindExtractionResult result = bindExtractor.extractBinds(formDef);

        List<XFormBindExtractor.BindInfo> binds = result.getBinds();
        for (XFormBindExtractor.BindInfo bind : binds) {
            System.out.println("nodePath: " + bind.getNodePath());
            System.out.println("  required: '" + bind.getRequired() + "'");
            System.out.println("  hasRequired(): " + bind.hasRequired());
            System.out.println("---");
        }
        
        // Also check source binds
        Map<String, Map<String, String>> sourceBinds = formLoader.extractBindAttributes(formDef);
        System.out.println("Source binds:");
        for (Map.Entry<String, Map<String, String>> entry : sourceBinds.entrySet()) {
            System.out.println("  " + entry.getKey() + " -> " + entry.getValue());
        }
    }
}
