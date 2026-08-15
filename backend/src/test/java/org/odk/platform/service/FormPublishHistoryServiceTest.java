package org.odk.platform.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.odk.platform.domain.*;
import org.odk.platform.repository.FormPublishHistoryRepository;
import org.odk.platform.repository.FormVersionRepository;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FormPublishHistoryServiceTest {
    @Mock FormPublishHistoryRepository historyRepository;
    @Mock FormVersionRepository versionRepository;
    @InjectMocks FormPublishHistoryService service;

    @Test
    void recordPublishUpdatesVersionAndPersistsHistory() {
        User actor = User.builder().id(UUID.randomUUID()).username("admin").build();
        FormVersion version = FormVersion.builder().id(UUID.randomUUID()).status(FormStatus.VALIDATED).build();
        when(historyRepository.save(any(FormPublishHistory.class))).thenAnswer(inv -> inv.getArgument(0));
        var history = service.recordPublish(version, actor, "publish");
        assertEquals("PUBLISHED", history.getAction());
        assertSame(actor, history.getPerformedBy());
        assertEquals(FormStatus.PUBLISHED, version.getStatus());
        assertTrue(version.getIsPublished());
        assertTrue(version.getIsLatest());
        assertNotNull(version.getPublishedAt());
        verify(versionRepository).save(version);
    }

    @Test
    void recordArchiveMarksVersionInactive() {
        User actor = User.builder().id(UUID.randomUUID()).username("admin").build();
        FormVersion version = FormVersion.builder().id(UUID.randomUUID()).isPublished(true).isLatest(true).build();
        when(historyRepository.save(any(FormPublishHistory.class))).thenAnswer(inv -> inv.getArgument(0));
        service.recordArchive(version, actor, null);
        assertEquals(FormStatus.ARCHIVED, version.getStatus());
        assertFalse(version.getIsPublished());
        assertFalse(version.getIsLatest());
    }
}
