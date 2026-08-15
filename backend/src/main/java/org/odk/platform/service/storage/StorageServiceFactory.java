package org.odk.platform.service.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class StorageServiceFactory {

    private final Map<String, StorageService> storageServices;

    public StorageService getStorageService(String driver) {
        StorageService service = storageServices.get(driver);
        if (service == null) {
            throw new IllegalArgumentException("Unknown storage driver: " + driver);
        }
        return service;
    }

    public StorageService getDefaultStorageService() {
        return storageServices.get("local");
    }
}