package org.odk.platform.config;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;

/**
 * Opt-in, private per-instance multipart spool. The OS lock is held for the
 * entire application lifetime and automatically released on process death.
 * Multipart bytes are transient, never durable attachment storage.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "odk.multipart-spool.enabled", havingValue = "true")
@EnableConfigurationProperties(MultipartProperties.class)
public class ManagedMultipartSpool {
    @Bean(destroyMethod = "close")
    Spool multipartSpool(@Value("${odk.multipart-spool.root}") String root,
                         MultipartProperties properties) throws IOException {
        if (properties.getLocation() != null && !properties.getLocation().isBlank())
            throw new IllegalStateException("Use managed spool root instead of spring.servlet.multipart.location");
        return Spool.open(Path.of(root));
    }

    @Bean
    MultipartConfigElement multipartConfigElement(Spool spool, MultipartProperties properties) {
        MultipartConfigElement defaults = properties.createMultipartConfig();
        return new MultipartConfigElement(spool.requests.toString(), defaults.getMaxFileSize(),
                defaults.getMaxRequestSize(), defaults.getFileSizeThreshold());
    }

    static final class Spool implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        final Path requests;

        private Spool(FileChannel channel, FileLock lock, Path requests) {
            this.channel = channel;
            this.lock = lock;
            this.requests = requests;
        }

        static Spool open(Path configured) throws IOException {
            if (!configured.isAbsolute() || !configured.equals(configured.normalize())
                    || configured.getParent() == null)
                throw new IOException("Managed multipart spool requires a dedicated canonical absolute root");
            Path root = configured;
            Files.createDirectories(root);
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || !root.toRealPath().equals(root))
                throw new IOException("Unsafe multipart spool root");
            Path guard = root.resolve(".u08-spool.lock");
            // The only lock name is permanent: never unlink a lock inode.
            FileChannel channel = FileChannel.open(guard, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            FileLock lock = null;
            try {
                lock = channel.tryLock();
                if (lock == null) throw new IOException("Multipart spool already in use");
                Path requests = root.resolve("requests");
                Path marker = root.resolve(".u08-spool-owned");
                boolean owned = Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                        && Files.size(marker) == 0;
                if (!owned) {
                    // Never adopt an existing request directory or unrelated files.
                    try (var entries = Files.newDirectoryStream(root)) {
                        for (Path entry : entries) {
                            if (!entry.equals(guard)) throw new IOException("Multipart spool root not empty");
                        }
                    }
                    Files.createFile(marker);
                    force(marker);
                    force(root);
                }
                Files.createDirectories(requests);
                if (!Files.isDirectory(requests, LinkOption.NOFOLLOW_LINKS)
                        || !requests.toRealPath().equals(requests))
                    throw new IOException("Unsafe multipart request directory");
                // Validate all entries before deleting any. No recursive sweep,
                // no symlinks, no unexpected directories, no permanent media.
                try (var entries = Files.newDirectoryStream(root)) {
                    for (Path entry : entries) {
                        if (!entry.equals(guard) && !entry.equals(marker) && !entry.equals(requests))
                            throw new IOException("Unexpected multipart spool root entry");
                    }
                }
                try (var entries = Files.newDirectoryStream(requests)) {
                    for (Path entry : entries) {
                        if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS))
                            throw new IOException("Unexpected multipart spool entry");
                    }
                }
                try (var entries = Files.newDirectoryStream(requests)) {
                    for (Path entry : entries) Files.delete(entry);
                }
                force(requests);
                force(root);
                return new Spool(channel, lock, requests);
            } catch (IOException | RuntimeException ex) {
                if (lock != null) lock.release();
                channel.close();
                throw ex;
            }
        }

        private static void force(Path path) throws IOException {
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                channel.force(true);
            }
        }

        @Override public void close() throws IOException {
            try { lock.release(); } finally { channel.close(); }
        }
    }
}
