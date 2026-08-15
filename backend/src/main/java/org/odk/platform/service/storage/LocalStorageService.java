package org.odk.platform.service.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@Service("local")
public class LocalStorageService implements StorageService {

    @Value("${app.storage.local.base-path:/var/lib/odk}")
    private String basePath;

    @Override
    public void store(InputStream inputStream, String path, String driver) throws IOException {
        Path fullPath = Paths.get(basePath, path);
        Files.createDirectories(fullPath.getParent());
        Files.copy(inputStream, fullPath, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public byte[] read(String path) throws IOException {
        Path fullPath = Paths.get(basePath, path);
        return Files.readAllBytes(fullPath);
    }

    @Override
    public void delete(String path) throws IOException {
        Path fullPath = Paths.get(basePath, path);
        Files.deleteIfExists(fullPath);
    }

    @Override
    public boolean exists(String path) {
        Path fullPath = Paths.get(basePath, path);
        return Files.exists(fullPath);
    }

    @Override
    public String getUrl(String path) {
        return "/api/storage/" + path;
    }

    @Override
    public Resource loadAsResource(String path) throws IOException {
        Path fullPath = Paths.get(basePath, path);
        try {
            Resource resource = new UrlResource(fullPath.toUri());
            if (resource.exists() || resource.isReadable()) {
                return resource;
            } else {
                throw new IOException("Could not read file: " + path);
            }
        } catch (MalformedURLException e) {
            throw new IOException("Could not read file: " + path, e);
        }
    }
}
