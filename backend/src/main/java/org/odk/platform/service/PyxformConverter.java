package org.odk.platform.service;

import lombok.Builder;
import lombok.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Thin process boundary around the official pyxform xls2xform CLI.
 *
 * Keeping conversion behind a small service makes the web application
 * independent of Python internals while allowing deployments to point at a
 * virtualenv/container-installed xls2xform executable.
 */
@Service
public class PyxformConverter {

    private final String command;
    private final Duration timeout;

    public PyxformConverter(
            @org.springframework.beans.factory.annotation.Value("${app.xlsform.pyxform-command:xls2xform}") String command,
            @org.springframework.beans.factory.annotation.Value("${app.xlsform.timeout-seconds:60}") long timeoutSeconds) {
        this.command = command;
        this.timeout = Duration.ofSeconds(Math.max(timeoutSeconds, 5));
    }

    public ConversionResult convert(byte[] workbookBytes, String originalFilename) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("odk-xlsform-");
            String safeBase = sanitizeBaseName(originalFilename);
            Path input = tempDir.resolve(safeBase + ".xlsx");
            Path output = tempDir.resolve(safeBase + ".xml");
            Path log = tempDir.resolve("pyxform.log");
            Files.write(input, workbookBytes);

            ProcessBuilder processBuilder = new ProcessBuilder(command, input.toString(), output.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile());
            Process process;
            try {
                process = processBuilder.start();
            } catch (IOException e) {
                throw new ConversionException("Unable to start pyxform command '" + command
                        + "'. Install pyxform or set PYXFORM_COMMAND to the xls2xform executable", e);
            }

            boolean finished;
            try {
                finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ConversionException("XLSForm conversion was interrupted", e);
            }

            if (!finished) {
                process.destroyForcibly();
                throw new ConversionException("pyxform conversion exceeded " + timeout.toSeconds() + " seconds");
            }

            String outputLog = Files.exists(log)
                    ? Files.readString(log, StandardCharsets.UTF_8).trim()
                    : "";
            if (process.exitValue() != 0) {
                throw new ConversionException("pyxform conversion failed"
                        + (outputLog.isBlank() ? "" : ": " + outputLog));
            }
            if (!Files.exists(output) || Files.size(output) == 0) {
                throw new ConversionException("pyxform completed without producing XForm XML"
                        + (outputLog.isBlank() ? "" : ": " + outputLog));
            }

            String xml = Files.readString(output, StandardCharsets.UTF_8);
            List<String> messages = splitMessages(outputLog);
            return ConversionResult.builder()
                    .xml(xml)
                    .messages(messages)
                    .command(command)
                    .build();
        } catch (IOException e) {
            throw new ConversionException("Unable to prepare XLSForm conversion: " + safeMessage(e), e);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    public boolean isAvailable() {
        try {
            Process process = new ProcessBuilder(command, "--help")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private List<String> splitMessages(String log) {
        if (log == null || log.isBlank()) return List.of();
        List<String> lines = new ArrayList<>();
        for (String raw : log.split("\\R")) {
            String line = raw.trim();
            if (!line.isBlank()) lines.add(line);
        }
        return List.copyOf(lines);
    }

    private String sanitizeBaseName(String originalFilename) {
        String fileName = originalFilename == null ? "form" : originalFilename;
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        if (slash >= 0) fileName = fileName.substring(slash + 1);
        if (fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            fileName = fileName.substring(0, fileName.length() - 5);
        }
        String safe = fileName.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isBlank() ? "form" : safe;
    }

    private void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Temporary cleanup must not hide the conversion result.
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }

    private String safeMessage(Throwable throwable) {
        return throwable.getMessage() == null || throwable.getMessage().isBlank()
                ? throwable.getClass().getSimpleName()
                : throwable.getMessage();
    }

    @Value
    @Builder
    public static class ConversionResult {
        String xml;
        @Builder.Default List<String> messages = List.of();
        String command;
    }

    public static class ConversionException extends RuntimeException {
        public ConversionException(String message) {
            super(message);
        }

        public ConversionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
