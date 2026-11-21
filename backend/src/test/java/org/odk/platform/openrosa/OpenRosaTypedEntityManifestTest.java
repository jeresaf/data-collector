package org.odk.platform.openrosa;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.odk.platform.domain.AppUser;
import org.odk.platform.domain.FormExternalDataset;
import org.odk.platform.domain.FormVersion;
import org.odk.platform.domain.XForm;
import org.odk.platform.repository.AppUserProjectRepository;
import org.odk.platform.repository.FormAttachmentRepository;
import org.odk.platform.repository.FormExternalDatasetRepository;
import org.odk.platform.repository.FormVersionRepository;
import org.odk.platform.repository.XFormRepository;
import org.odk.platform.security.CurrentUserService;
import org.odk.platform.service.storage.StorageService;
import org.odk.platform.service.storage.StorageServiceFactory;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests the exact-source OpenRosa controller paths without a live client.
 * Full mobile Basic and native ODK importer tests remain separate gates.
 */
class OpenRosaTypedEntityManifestTest {
    private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-000000000085");
    private static final UUID FORM = UUID.fromString("00000000-0000-0000-0000-000000000086");
    private static final UUID VERSION = UUID.fromString("00000000-0000-0000-0000-000000000087");
    private static final String FILE = "households.csv";
    private static final byte[] VALID = ("name,label,__version\n"
            + "d6b8a5e0-79d8-46e7-aab9-5bf1a57162ad,Household,1\n")
            .getBytes(StandardCharsets.UTF_8);

    private OpenRosaController controller;
    private StorageService storage;
    private FormExternalDataset dataset;
    private CurrentUserService currentUserService;
    private AppUserProjectRepository appUserProjectRepository;

    @BeforeEach
    void setUp() throws Exception {
        controller = new OpenRosaController();
        XFormRepository forms = mock(XFormRepository.class);
        XForm form = mock(XForm.class);
        when(form.getId()).thenReturn(FORM);
        when(form.getIsPublished()).thenReturn(true);
        when(form.getIsActive()).thenReturn(true);
        when(forms.findByXmlnsAndProjectId("sample_form", PROJECT)).thenReturn(Optional.of(form));

        FormVersionRepository versions = mock(FormVersionRepository.class);
        FormVersion version = mock(FormVersion.class);
        when(version.getId()).thenReturn(VERSION);
        when(versions.findLatestByFormId(FORM)).thenReturn(Optional.of(version));

        FormAttachmentRepository attachments = mock(FormAttachmentRepository.class);
        when(attachments.findByFormVersionId(VERSION)).thenReturn(List.of());
        FormExternalDatasetRepository datasets = mock(FormExternalDatasetRepository.class);

        storage = mock(StorageService.class);
        StorageServiceFactory factory = mock(StorageServiceFactory.class);
        when(factory.getStorageService("local")).thenReturn(storage);

        currentUserService = mock(CurrentUserService.class);
        when(currentUserService.getCurrentUser()).thenReturn(Optional.empty());
        appUserProjectRepository = mock(AppUserProjectRepository.class);

        ReflectionTestUtils.setField(controller, "xFormRepository", forms);
        ReflectionTestUtils.setField(controller, "formVersionRepository", versions);
        ReflectionTestUtils.setField(controller, "formAttachmentRepository", attachments);
        ReflectionTestUtils.setField(controller, "formExternalDatasetRepository", datasets);
        ReflectionTestUtils.setField(controller, "storageServiceFactory", factory);
        ReflectionTestUtils.setField(controller, "currentUserService", currentUserService);
        ReflectionTestUtils.setField(controller, "appUserProjectRepository", appUserProjectRepository);

        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(VALID));
        dataset = FormExternalDataset.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000088"))
                .datasetId(FILE)
                .storageDriver("local")
                .storagePath("project/households.csv")
                .contentHash(hash)
                .contentType("text/csv")
                .fileSize((long) VALID.length)
                .columnDefinitions(Map.of(EntityListManifestPreflight.METADATA_KEY, "entityList"))
                .isActive(true)
                .build();

        when(datasets.findByFormVersionIdAndIsActiveTrue(VERSION)).thenAnswer(i -> List.of(dataset));
        when(datasets.findByFormVersionIdAndDatasetId(VERSION, FILE)).thenAnswer(i -> Optional.of(dataset));
        when(storage.read("project/households.csv")).thenReturn(VALID);
    }

    private String manifest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("https");
        request.setServerName("example.test");
        request.setServerPort(443);
        return controller.formManifest(PROJECT, "sample_form",
                request, new MockHttpServletResponse()).getBody();
    }

    private org.springframework.http.ResponseEntity<Resource> download() {
        return controller.attachmentDownload(PROJECT, "sample_form", FILE,
                new MockHttpServletRequest(), new MockHttpServletResponse());
    }

    @Test
    void explicitlyTypedAndHashVerifiedDatasetGetsTypedManifestAndExactDownloadBytes() throws Exception {
        assertTrue(manifest().contains("<mediaFile type=\"entityList\">"));
        var result = download();
        assertEquals(200, result.getStatusCode().value());
        assertArrayEquals(VALID, result.getBody().getInputStream().readAllBytes());
    }

    @Test
    void untypedCsvRemainsOrdinaryMediaWithoutNewStorageReadRequirement() throws Exception {
        dataset.setColumnDefinitions(Map.of());
        String xml = manifest();
        assertTrue(xml.contains("<mediaFile>"));
        assertFalse(xml.contains("type=\"entityList\""));
        verify(storage, never()).read("project/households.csv");
    }

    @Test
    void changedBytesAfterManifestAreNotServedWithStaleHash() throws Exception {
        assertTrue(manifest().contains("type=\"entityList\""));
        when(storage.read("project/households.csv")).thenReturn(
                "name,label,__version\nnot-an-id,Changed,2\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(404, download().getStatusCode().value());
    }

    @Test
    void explicitlyTypedInvalidOrHashMismatchedDatasetIsOmittedAndDownloadDenied() throws Exception {
        dataset.setContentHash("00000000000000000000000000000000");
        assertFalse(manifest().contains("<mediaFile"));
        assertEquals(404, download().getStatusCode().value());
    }
    private void mobilePrincipalWithAssignments(List<UUID> assigned) {
        AppUser appUser = mock(AppUser.class);
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000089");
        when(appUser.getId()).thenReturn(userId);
        when(currentUserService.getCurrentUser()).thenReturn(Optional.of(appUser));
        when(appUserProjectRepository.findAssignedFormIdsByAppUserIdAndProjectId(userId, PROJECT))
                .thenReturn(assigned);
    }

    @Test
    void mobileUserWithoutFormAssignmentCannotReadTypedManifestOrDataset() throws Exception {
        mobilePrincipalWithAssignments(List.of());
        var request = new MockHttpServletRequest();
        request.setScheme("https");
        request.setServerName("example.test");
        assertEquals(404, controller.formManifest(
                PROJECT, "sample_form", request, new MockHttpServletResponse()).getStatusCode().value());
        assertEquals(404, download().getStatusCode().value());
        verifyNoInteractions(storage);
    }

    @Test
    void assignedMobileUserCanReadValidatedTypedManifestAndDataset() throws Exception {
        mobilePrincipalWithAssignments(List.of(FORM));
        assertTrue(manifest().contains("<mediaFile type=\"entityList\">"));
        assertEquals(200, download().getStatusCode().value());
        verify(storage, atLeastOnce()).read("project/households.csv");
    }

    @Test
    void assignmentLookupFailureDeniesTypedManifestAndDatasetBeforeStorageRead() {
        AppUser appUser = mock(AppUser.class);
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000090");
        when(appUser.getId()).thenReturn(userId);
        when(currentUserService.getCurrentUser()).thenReturn(Optional.of(appUser));
        when(appUserProjectRepository.findAssignedFormIdsByAppUserIdAndProjectId(userId, PROJECT))
                .thenThrow(new IllegalStateException("synthetic assignment outage"));

        MockHttpServletRequest request = new MockHttpServletRequest();
        assertEquals(403, controller.formManifest(
                PROJECT, "sample_form", request, new MockHttpServletResponse()).getStatusCode().value());
        assertEquals(403, download().getStatusCode().value());
        verifyNoInteractions(storage);
    }

    @Test
    void approvalEntityListUsesDistinctManifestTypeAndRetainsExactBytes() throws Exception {
        dataset.setColumnDefinitions(Map.of(
                EntityListManifestPreflight.METADATA_KEY, "approvalEntityList"));
        assertTrue(manifest().contains("<mediaFile type=\"approvalEntityList\">"));
        var result = download();
        assertEquals(200, result.getStatusCode().value());
        assertArrayEquals(VALID, result.getBody().getInputStream().readAllBytes());
    }

    @Test
    void unknownExplicitMediaTypeIsNotSilentlyDowngradedToOrdinaryCsv() {
        dataset.setColumnDefinitions(Map.of(
                EntityListManifestPreflight.METADATA_KEY, "futureUnsupportedType"));
        assertFalse(manifest().contains("<mediaFile"));
        assertEquals(404, download().getStatusCode().value());
    }

    @Test
    void typedDatasetWithoutCsvFilenameIsDeniedBeforeStorage() {
        dataset.setDatasetId("households.json");
        assertFalse(manifest().contains("<mediaFile"));
        verifyNoInteractions(storage);
    }

}
