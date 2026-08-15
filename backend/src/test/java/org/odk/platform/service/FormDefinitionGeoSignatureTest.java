package org.odk.platform.service;

import org.javarosa.core.model.data.GeoPointData;
import org.javarosa.core.model.data.GeoShapeData;
import org.javarosa.core.model.data.GeoTraceData;
import org.javarosa.core.model.data.IAnswerData;
import org.javarosa.form.api.FormEntryController;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FormDefinitionGeoSignatureTest {

    private static final String GEO_SIGNATURE_XFORM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                    xmlns="http://www.w3.org/2002/xforms"
                    xmlns:jr="http://openrosa.org/javarosa">
              <h:head>
                <h:title>Geo and Signature Form</h:title>
                <model>
                  <instance>
                    <data id="geo_signature_form" version="1">
                      <point/>
                      <trace/>
                      <shape/>
                      <signature/>
                      <meta><instanceID/></meta>
                    </data>
                  </instance>
                  <bind nodeset="/data/point" type="geopoint"/>
                  <bind nodeset="/data/trace" type="geotrace"/>
                  <bind nodeset="/data/shape" type="geoshape"/>
                  <bind nodeset="/data/signature" type="binary"/>
                </model>
              </h:head>
              <h:body>
                <input ref="/data/point"><label>Point</label></input>
                <input ref="/data/trace"><label>Trace</label></input>
                <input ref="/data/shape"><label>Shape</label></input>
                <upload ref="/data/signature" mediatype="image/*" appearance="signature">
                  <label>Signature</label>
                </upload>
              </h:body>
            </h:html>
            """;

    @Test
    void geoAnswers_areParsedByJavaRosa_andSerializedInOdkFormat() {
        FormDefinition form = FormDefinition.createNew(GEO_SIGNATURE_XFORM, "1");

        assertEquals(FormEntryController.EVENT_QUESTION, form.stepToNextEvent());
        IAnswerData point = form.parseAnswer("0.3476 32.5825 120 4.5");
        assertInstanceOf(GeoPointData.class, point);
        assertEquals(FormEntryController.ANSWER_OK, form.answerQuestion(point));

        assertEquals(FormEntryController.EVENT_QUESTION, form.stepToNextEvent());
        IAnswerData trace = form.parseAnswer("0.3476 32.5825 0 5;0.348 32.583 0 4");
        assertInstanceOf(GeoTraceData.class, trace);
        assertEquals(FormEntryController.ANSWER_OK, form.answerQuestion(trace));

        assertEquals(FormEntryController.EVENT_QUESTION, form.stepToNextEvent());
        String shapeValue = "0.3476 32.5825 0 5;0.348 32.583 0 4;0.349 32.581 0 3;0.3476 32.5825 0 5";
        IAnswerData shape = form.parseAnswer(shapeValue);
        assertInstanceOf(GeoShapeData.class, shape);
        assertEquals(FormEntryController.ANSWER_OK, form.answerQuestion(shape));

        assertEquals(FormEntryController.EVENT_QUESTION, form.stepToNextEvent());
        assertEquals("signature", form.getCurrentAppearanceHint());
        assertEquals("image/*", form.getCurrentMediaType());
        assertEquals(FormEntryController.ANSWER_OK, form.answerQuestion(form.parseAnswer("signature-field.png")));

        String xml = form.finalizeAndSerialize();
        assertTrue(xml.contains("<point>"), xml);
        assertTrue(xml.contains("0.3476 32.5825"), xml);
        assertTrue(xml.contains("<trace>"), xml);
        assertTrue(xml.contains(";"), xml);
        assertTrue(xml.contains("<shape>"), xml);
        assertTrue(xml.contains("0.349 32.581"), xml);
        assertTrue(xml.contains("<signature>signature-field.png</signature>"), xml);
    }

}
