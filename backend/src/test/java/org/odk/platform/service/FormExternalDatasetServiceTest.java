package org.odk.platform.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.odk.platform.domain.FormExternalDataset;
import org.odk.platform.repository.FormExternalDatasetRepository;
import org.odk.platform.repository.FormVersionRepository;
import org.odk.platform.security.CurrentUserService;
import org.odk.platform.service.storage.StorageService;
import org.odk.platform.service.storage.StorageServiceFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FormExternalDatasetServiceTest {

    @Mock
    private FormExternalDatasetRepository externalDatasetRepository;
    @Mock
    private FormVersionRepository formVersionRepository;
    @Mock
    private StorageServiceFactory storageServiceFactory;
    @Mock
    private CurrentUserService currentUserService;
    @Mock
    private StorageService storageService;

    private FormExternalDatasetService service;

    @BeforeEach
    void setUp() {
        service = new FormExternalDatasetService(
                externalDatasetRepository,
                formVersionRepository,
                storageServiceFactory,
                currentUserService);
    }

    @Test
    void loadActiveDatasetContents_usesConfiguredStorageDriverAndProvidesUsefulAliases() throws Exception {
        UUID formVersionId = UUID.randomUUID();
        String storagePath = "/var/lib/odk/external-datasets/" + formVersionId + "/2026/08/14/cities.csv";
        byte[] content = "code,label\nkla,Kampala\n".getBytes();

        FormExternalDataset dataset = FormExternalDataset.builder()
                .datasetId("cities")
                .storageDriver("local")
                .storagePath(storagePath)
                .isActive(true)
                .build();

        when(externalDatasetRepository.findByFormVersionIdAndIsActiveTrue(formVersionId))
                .thenReturn(List.of(dataset));
        when(storageServiceFactory.getStorageService("local")).thenReturn(storageService);
        when(storageService.read(storagePath)).thenReturn(content);

        Map<String, byte[]> loaded = service.loadActiveDatasetContents(formVersionId);

        assertEquals(2, loaded.size());
        assertArrayEquals(content, loaded.get("cities"));
        assertArrayEquals(content, loaded.get("cities.csv"));
    }
}
