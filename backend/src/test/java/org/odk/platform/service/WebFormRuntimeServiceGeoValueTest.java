package org.odk.platform.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.odk.platform.domain.WebFormAnswer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest
@ActiveProfiles("test")
class WebFormRuntimeServiceGeoValueTest {

    @Autowired
    private WebFormRuntimeService webFormRuntimeService;

    @ParameterizedTest
    @CsvSource({
            "geopoint, '0.3913105285808219 32.6123563657993 0 5'",
            "geotrace, '0.3913 32.6123 0 5;0.3920 32.6130 0 5'",
            "geoshape, '0.3913 32.6123 0 5;0.3920 32.6130 0 5;0.3913 32.6123 0 5'",
            "binary, 'farm-photo.jpg'"
    })
    void odkLexicalValuesAreStoredAsTextNotJson(String type, String value) {
        WebFormAnswer answer = new WebFormAnswer();

        ReflectionTestUtils.invokeMethod(
                webFormRuntimeService,
                "setAnswerValue",
                answer,
                value,
                type);

        assertEquals(value, answer.getValueText());
        assertNull(answer.getValueJson());
        assertNull(answer.getValueNumber());
        assertNull(answer.getValueDate());
        assertNull(answer.getValueBoolean());
    }
}
