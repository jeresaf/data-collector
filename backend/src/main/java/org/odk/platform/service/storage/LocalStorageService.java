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

    private static final Path LEGACY_ODK_ROOT = Paths.get("/var/lib/odk").toAbsolutePath().normalize();
    private static final Path LEGACY_PLATFORM_ROOT = Paths.get("/var/lib/odk-platform/storage").toAbsolutePath().normalize();

    @Value("${app.storage.local.base-path:${app.storage.local.path:/var/lib/odk}}")
    private String basePath;

    @Override
    public void store(InputStream inputStream, String path, String driver) throws IOException {
        Path fullPath = resolveStoragePath(path);
        Files.createDirectories(fullPath.getParent());
        Files.copy(inputStream, fullPath, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public byte[] read(String path) throws IOException {
        return Files.readAllBytes(resolveStoragePath(path));
    }

    @Override
    public void delete(String path) throws IOException {
        Files.deleteIfExists(resolveStoragePath(path));
    }

    @Override
    public boolean exists(String path) {
        try {
            return Files.exists(resolveStoragePath(path));
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public String getUrl(String path) {
        return "/api/storage/" + path;
    }

    @Override
    public Resource loadAsResource(String path) throws IOException {
        Path fullPath = resolveStoragePath(path);
        try {
            Resource resource = new UrlResource(fullPath.toUri());
            if (resource.exists() && resource.isReadable()) {
                return resource;
            }
            throw new IOException("Could not read file: " + path);
        } catch (MalformedURLException e) {
            throw new IOException("Could not read file: " + path, e);
        }
    }

    /**
     * Resolve a database storage path beneath the configured storage root.
     *
     * New records use relative paths (for example external-datasets/...).
     * Older records may contain absolute /var/lib/odk/... paths. Those are
     * accepted and mapped to the configured root without prefixing the root
     * a second time. Absolute paths outside known storage roots are rejected.
     */
    private Path resolveStoragePath(String path) throws IOException {
        if (path == null || path.isBlank()) {
            throw new IOException("Storage path is empty");
        }

        Path root = Paths.get(basePath).toAbsolutePath().normalize();
        Path requested = Paths.get(path).normalize();
        Path resolved;

        if (requested.isAbsolute()) {
            Path absoluteRequested = requested.toAbsolutePath().normalize();
            if (absoluteRequested.startsWith(root)) {
                resolved = absoluteRequested;
            } else if (absoluteRequested.startsWith(LEGACY_ODK_ROOT)) {
                resolved = root.resolve(LEGACY_ODK_ROOT.relativize(absoluteRequested)).normalize();
            } else if (absoluteRequested.startsWith(LEGACY_PLATFORM_ROOT)) {
                resolved = root.resolve(LEGACY_PLATFORM_ROOT.relativize(absoluteRequested)).normalize();
            } else {
                throw new IOException("Storage path is outside the configured storage root: " + path);
            }
        } else {
            resolved = root.resolve(requested).normalize();
        }

        if (!resolved.startsWith(root)) {
            throw new IOException("Storage path escapes the configured storage root: " + path);
        }

        return resolved;
    }
}
