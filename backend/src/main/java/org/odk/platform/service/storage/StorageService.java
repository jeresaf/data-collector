package org.odk.platform.service.storage;

import org.springframework.core.io.Resource;

import java.io.IOException;

public interface StorageService {

    void store(java.io.InputStream inputStream, String path, String driver) throws IOException;

    byte[] read(String path) throws IOException;

    void delete(String path) throws IOException;

    boolean exists(String path);

    String getUrl(String path);

    Resource loadAsResource(String path) throws IOException;
}
