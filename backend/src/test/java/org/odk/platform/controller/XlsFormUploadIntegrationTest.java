package org.odk.platform.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.odk.platform.OdkPlatformApplication;
import org.odk.platform.domain.FormVersion;
import org.odk.platform.service.FormVersionService;
import org.odk.platform.service.PyxformConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = OdkPlatformApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class XlsFormUploadIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired FormVersionService formVersionService;

    @MockBean PyxformConverter pyxformConverter;

    @BeforeEach
    void setUp() {
        when(pyxformConverter.convert(any(byte[].class), anyString()))
                .thenAnswer(invocation -> PyxformConverter.ConversionResult.builder()
                        .xml(validXForm("people", "1"))
                        .messages(java.util.List.of("pyxform warning example"))
                        .command("xls2xform")
                        .build());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void uploadXlsForm_convertsValidatesStoresSourceAndReturnsWarnings() throws Exception {
        UUID projectId = createProject("XLSForm project");
        byte[] xlsx = workbook("custom_extension");
        MockMultipartFile file = new MockMultipartFile(
                "file", "people.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx);

        String body = mockMvc.perform(multipart("/api/projects/{projectId}/forms/upload-xlsform", projectId)
                        .file(file)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.xmlns").value("people"))
                .andExpect(jsonPath("$.version").value("1"))
                .andExpect(jsonPath("$.title").value("People"))
                .andExpect(jsonPath("$.warnings").isArray())
                .andExpect(jsonPath("$.extraColumns.survey[0]").value("custom_extension"))
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(body);
        UUID versionId = UUID.fromString(json.get("formVersionId").asText());
        FormVersion version = formVersionService.findById(versionId).orElseThrow();

        assertThat(Base64.getDecoder().decode(version.getXlsContent())).isEqualTo(xlsx);
        assertThat(version.getMetadata()).containsKey("xlsform");
        @SuppressWarnings("unchecked")
        Map<String, Object> xlsform = (Map<String, Object>) version.getMetadata().get("xlsform");
        assertThat(xlsform.get("originalFilename")).isEqualTo("people.xlsx");
        assertThat(xlsform.get("sha256")).isNotNull();
        assertThat(version.getXmlContent()).contains("id=\"people\"");
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void uploadXlsForm_sameFormNewVersionCreatesAnotherFormVersion() throws Exception {
        UUID projectId = createProject("Version project");
        byte[] xlsx = workbook(null);
        MockMultipartFile file1 = new MockMultipartFile("file", "people.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx);

        String first = mockMvc.perform(multipart("/api/projects/{projectId}/forms/upload-xlsform", projectId)
                        .file(file1).with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID formId = UUID.fromString(objectMapper.readTree(first).get("formId").asText());

        when(pyxformConverter.convert(any(byte[].class), anyString()))
                .thenReturn(PyxformConverter.ConversionResult.builder()
                        .xml(validXForm("people", "2"))
                        .messages(java.util.List.of())
                        .command("xls2xform")
                        .build());

        MockMultipartFile file2 = new MockMultipartFile("file", "people-v2.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx);
        String second = mockMvc.perform(multipart("/api/projects/{projectId}/forms/upload-xlsform", projectId)
                        .file(file2).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("2"))
                .andReturn().getResponse().getContentAsString();

        assertThat(UUID.fromString(objectMapper.readTree(second).get("formId").asText())).isEqualTo(formId);
        assertThat(formVersionService.findByFormIdOrderByVersionDesc(formId)).hasSize(2);
        assertThat(formVersionService.findByFormIdAndVersion(formId, "2")).isPresent();
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void uploadXlsForm_rejectsWrongExtensionBeforeConversion() throws Exception {
        UUID projectId = createProject("Invalid project");
        MockMultipartFile file = new MockMultipartFile("file", "people.xls",
                "application/vnd.ms-excel", workbook(null));

        mockMvc.perform(multipart("/api/projects/{projectId}/forms/upload-xlsform", projectId)
                        .file(file).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errors[0]").value("Only .xlsx XLSForm workbooks are supported"));
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void uploadXlsForm_duplicateVersionReturnsConflict() throws Exception {
        UUID projectId = createProject("Duplicate version project");
        byte[] xlsx = workbook(null);

        mockMvc.perform(multipart("/api/projects/{projectId}/forms/upload-xlsform", projectId)
                        .file(new MockMultipartFile("file", "people.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx))
                        .with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(multipart("/api/projects/{projectId}/forms/upload-xlsform", projectId)
                        .file(new MockMultipartFile("file", "people-again.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx))
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));
    }

    private UUID createProject(String name) throws Exception {
        String response = mockMvc.perform(post("/api/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", name, "description", "XLSForm test"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }

    private byte[] workbook(String extraColumn) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var survey = workbook.createSheet("survey");
            var header = survey.createRow(0);
            header.createCell(0).setCellValue("type");
            header.createCell(1).setCellValue("name");
            header.createCell(2).setCellValue("label");
            if (extraColumn != null) header.createCell(3).setCellValue(extraColumn);
            var question = survey.createRow(1);
            question.createCell(0).setCellValue("text");
            question.createCell(1).setCellValue("name");
            question.createCell(2).setCellValue("Name");

            var settings = workbook.createSheet("settings");
            var settingsHeader = settings.createRow(0);
            settingsHeader.createCell(0).setCellValue("form_title");
            settingsHeader.createCell(1).setCellValue("form_id");
            settingsHeader.createCell(2).setCellValue("version");
            var settingsRow = settings.createRow(1);
            settingsRow.createCell(0).setCellValue("People");
            settingsRow.createCell(1).setCellValue("people");
            settingsRow.createCell(2).setCellValue("1");

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private String validXForm(String formId, String version) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                        xmlns="http://www.w3.org/2002/xforms"
                        xmlns:jr="http://openrosa.org/javarosa">
                  <h:head>
                    <h:title>People</h:title>
                    <model>
                      <instance>
                        <data id="%s" version="%s">
                          <name/>
                          <meta><instanceID/></meta>
                        </data>
                      </instance>
                      <bind nodeset="/data/name" type="string"/>
                    </model>
                  </h:head>
                  <h:body>
                    <input ref="/data/name"><label>Name</label></input>
                  </h:body>
                </h:html>
                """.formatted(formId, version);
    }
}
