package org.odk.platform.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.odk.platform.OdkPlatformApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = OdkPlatformApplication.class, properties = "odk.test.reference-data-controller-integration=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithMockUser(username = "admin", roles = {"ADMIN"})
class ReferenceDataControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private UUID projectId;

    @BeforeEach
    void createProject() throws Exception {
        String response = mockMvc.perform(post("/api/projects").with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Reference Data Project",
                                "description", "Generic reference-data tests"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        projectId = UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }

    @Test
    void referenceData_crudRelationshipsImportAndExportRoundTrip() throws Exception {
        UUID regionsId = createList("regions", "Regions");
        UUID districtsId = createList("districts", "Districts");

        UUID centralId = createItem(regionsId, "central", "Central", Map.of("code", "C"), List.of());
        UUID kampalaId = createItem(districtsId, "kampala", "Kampala",
                Map.of("population_band", "large"),
                List.of(Map.of("parentItemId", centralId.toString(), "relationshipType", "parent")));

        mockMvc.perform(get("/api/projects/{projectId}/reference-lists/{listId}/items", projectId, districtsId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("kampala"))
                .andExpect(jsonPath("$[0].properties.population_band").value("large"))
                .andExpect(jsonPath("$[0].parents[0].parentName").value("central"))
                .andExpect(jsonPath("$[0].parents[0].parentListName").value("regions"));

        String csv = "name,label,active,sort_order,parent,parent_list,relationship_type,code\n" +
                "wakiso,Wakiso,true,2,central,regions,parent,WAK\n" +
                "mukono,Mukono,true,3,central,regions,parent,MUK\n";
        MockMultipartFile file = new MockMultipartFile("file", "districts.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/projects/{projectId}/reference-lists/{listId}/import", projectId, districtsId)
                        .file(file).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.relationshipsCreated").value(2))
                .andExpect(jsonPath("$.warnings", hasSize(0)));

        mockMvc.perform(get("/api/projects/{projectId}/reference-lists/{listId}/export", projectId, districtsId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/csv")))
                .andExpect(header().string("Content-Disposition", containsString("districts.csv")))
                .andExpect(content().string(containsString("name,label,active,sort_order,parent,parent_list,relationship_type")))
                .andExpect(content().string(containsString("wakiso,Wakiso,true,2,central,regions,parent,WAK")));

        mockMvc.perform(delete("/api/projects/{projectId}/reference-lists/{listId}/items/{itemId}",
                        projectId, districtsId, kampalaId).with(csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/projects/{projectId}/reference-lists/{listId}", projectId, regionsId).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void csvImport_rejectsMissingRequiredColumns() throws Exception {
        UUID listId = createList("codes", "Codes");
        MockMultipartFile file = new MockMultipartFile("file", "codes.csv", "text/csv",
                "code,description\na,Alpha\n".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/projects/{projectId}/reference-lists/{listId}/import", projectId, listId)
                        .file(file).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("name")));
    }

    @Test
    @WithMockUser(username = "viewer", roles = {"USER"})
    void ordinaryUser_canReadAndExportButCannotMutate() throws Exception {
        String response = mockMvc.perform(post("/api/projects/{projectId}/reference-lists", projectId)
                        .with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "viewer-codes",
                                "displayName", "Viewer Codes"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID listId = UUID.fromString(objectMapper.readTree(response).get("id").asText());

        mockMvc.perform(get("/api/projects/{projectId}/reference-lists", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("viewer-codes"));

        mockMvc.perform(get("/api/projects/{projectId}/reference-lists/{listId}/export", projectId, listId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/csv")));

        mockMvc.perform(post("/api/projects/{projectId}/reference-lists", projectId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"blocked\"}"))
                .andExpect(status().isForbidden());
    }

    private UUID createList(String name, String displayName) throws Exception {
        String response = mockMvc.perform(post("/api/projects/{projectId}/reference-lists", projectId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", name, "displayName", displayName))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }

    private UUID createItem(UUID listId, String name, String label, Map<String, String> properties,
                            List<Map<String, String>> parents) throws Exception {
        Map<String, Object> payload = Map.of(
                "name", name,
                "label", label,
                "active", true,
                "properties", properties,
                "parents", parents
        );
        String response = mockMvc.perform(post("/api/projects/{projectId}/reference-lists/{listId}/items", projectId, listId)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }
}
