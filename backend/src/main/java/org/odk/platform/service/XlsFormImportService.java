package org.odk.platform.service;

import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.javarosa.core.model.FormDef;
import org.odk.platform.domain.FormStatus;
import org.odk.platform.domain.FormVersion;
import org.odk.platform.domain.Project;
import org.odk.platform.domain.User;
import org.odk.platform.domain.XForm;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * End-to-end XLSForm import pipeline:
 * XLSX preflight -> official pyxform conversion -> JavaRosa validation ->
 * normal XForm/FormVersion persistence.
 */
@Service
@RequiredArgsConstructor
public class XlsFormImportService {

    private static final long MAX_XLSFORM_BYTES = 10L * 1024L * 1024L;

    private final XlsFormWorkbookValidator workbookValidator;
    private final PyxformConverter pyxformConverter;
    private final JavaRosaFormValidator javaRosaFormValidator;
    private final JavaRosaFormLoader javaRosaFormLoader;
    private final FormMetadataExtractor formMetadataExtractor;
    private final XFormService xFormService;
    private final FormVersionService formVersionService;

    @Transactional
    public ImportResult importWorkbook(Project project,
                                       byte[] workbookBytes,
                                       String originalFilename,
                                       String requestedVersion,
                                       String changelog,
                                       User currentUser) {
        if (project == null) throw new IllegalArgumentException("Project is required");
        if (currentUser == null) throw new IllegalArgumentException("Current user is required");
        if (workbookBytes == null || workbookBytes.length == 0) {
            throw new ValidationException(List.of("An XLSForm .xlsx file is required"), List.of(), List.of());
        }
        if (workbookBytes.length > MAX_XLSFORM_BYTES) {
            throw new ValidationException(List.of("XLSForm file must be 10MB or smaller"), List.of(), List.of());
        }

        XlsFormWorkbookValidator.ValidationResult preflight =
                workbookValidator.validate(workbookBytes, originalFilename);
        if (!preflight.isValid()) {
            throw new ValidationException(preflight.getErrors(), preflight.getWarnings(), preflight.getInfo());
        }

        List<String> warnings = new ArrayList<>(preflight.getWarnings());
        List<String> info = new ArrayList<>(preflight.getInfo());

        PyxformConverter.ConversionResult conversion =
                pyxformConverter.convert(workbookBytes, originalFilename);
        if (!conversion.getMessages().isEmpty()) {
            warnings.addAll(conversion.getMessages());
        }

        String xmlContent = conversion.getXml();
        try {
            javaRosaFormValidator.validateOrThrow(xmlContent);
        } catch (RuntimeException e) {
            throw new ValidationException(
                    List.of("Generated XForm failed JavaRosa 4.3.2 validation: " + safeMessage(e)),
                    warnings,
                    info);
        }

        FormDef formDef = javaRosaFormLoader.loadFormDef(xmlContent);
        String xmlFormId = javaRosaFormLoader.getFormId(formDef);
        String extractedVersion = javaRosaFormLoader.getFormVersion(formDef);
        String title = javaRosaFormLoader.getFormTitle(formDef);
        String formHash = javaRosaFormLoader.generateFormHash(xmlContent);

        if (xmlFormId == null || xmlFormId.isBlank()) {
            throw new ValidationException(List.of("Generated XForm has no form ID"), warnings, info);
        }
        String version = (extractedVersion == null || extractedVersion.isBlank()) ? "1" : extractedVersion;
        if (title == null || title.isBlank()) title = xmlFormId;

        if (requestedVersion != null && !requestedVersion.isBlank() && !requestedVersion.trim().equals(version)) {
            warnings.add("Requested version '" + requestedVersion.trim() + "' was not applied because XLSForm settings/XForm XML are authoritative; generated version is '" + version + "'");
        }

        String encodedWorkbook = Base64.getEncoder().encodeToString(workbookBytes);
        String xlsHash = sha256(workbookBytes);

        Optional<XForm> existing = xFormService.findByXmlnsAndProjectId(xmlFormId, project.getId());
        XForm form;
        if (existing.isPresent()) {
            form = existing.get();
            if (formVersionService.findByFormIdAndVersion(form.getId(), version).isPresent()) {
                throw new DuplicateVersionException(xmlFormId, version);
            }
            for (FormVersion prior : formVersionService.findByFormIdOrderByVersionDesc(form.getId())) {
                if (Boolean.TRUE.equals(prior.getIsLatest())) {
                    prior.setIsLatest(false);
                    formVersionService.save(prior);
                }
            }
            form.setTitle(title);
            form.setVersion(version);
            form.setXmlContent(xmlContent);
            form.setXlsContent(encodedWorkbook);
            form.setUpdatedBy(currentUser);
            form = xFormService.save(form);
        } else {
            form = XForm.builder()
                    .project(project)
                    .title(title)
                    .xmlns(xmlFormId)
                    .version(version)
                    .xmlContent(xmlContent)
                    .xlsContent(encodedWorkbook)
                    .createdBy(currentUser)
                    .isPublished(false)
                    .isActive(true)
                    .build();
            form = xFormService.save(form);
        }

        FormVersion formVersion = FormVersion.builder()
                .form(form)
                .version(version)
                .xmlContent(xmlContent)
                .xlsContent(encodedWorkbook)
                .xmlns(xmlFormId)
                .formHash(formHash)
                .isPublished(false)
                .isLatest(true)
                .status(FormStatus.VALIDATED)
                .validatedAt(Instant.now())
                .validatedBy(currentUser)
                .changelog(blankToNull(changelog))
                .createdBy(currentUser)
                .build();
        formVersion = formVersionService.save(formVersion);

        try {
            FormMetadataExtractor.FormMetadataResult metadata =
                    formMetadataExtractor.extractMetadata(formDef, xmlContent);
            formMetadataExtractor.storeMetadata(formVersion, metadata);
        } catch (RuntimeException e) {
            warnings.add("Generated XForm was valid but metadata enrichment was incomplete: " + safeMessage(e));
        }

        Map<String, Object> metadata = new HashMap<>(
                formVersion.getMetadata() == null ? Map.of() : formVersion.getMetadata());
        Map<String, Object> xlsformMetadata = new LinkedHashMap<>();
        xlsformMetadata.put("originalFilename", originalFilename);
        xlsformMetadata.put("contentEncoding", "base64");
        xlsformMetadata.put("sha256", xlsHash);
        xlsformMetadata.put("byteSize", workbookBytes.length);
        xlsformMetadata.put("sheetHeaders", preflight.getSheetHeaders());
        xlsformMetadata.put("extraColumns", preflight.getExtraColumns());
        xlsformMetadata.put("warnings", List.copyOf(warnings));
        xlsformMetadata.put("converter", "pyxform");
        xlsformMetadata.put("converterCommand", conversion.getCommand());
        xlsformMetadata.put("importedAt", Instant.now().toString());
        metadata.put("xlsform", xlsformMetadata);
        formVersion.setMetadata(metadata);
        formVersion = formVersionService.save(formVersion);

        Map<String, Object> formMetadata = new HashMap<>(form.getMetadata() == null ? Map.of() : form.getMetadata());
        formMetadata.put("latestXlsFormSha256", xlsHash);
        formMetadata.put("latestXlsFormFilename", originalFilename);
        form.setMetadata(formMetadata);
        form = xFormService.save(form);

        info.add("Generated XForm validated successfully with JavaRosa 4.3.2");

        return ImportResult.builder()
                .formId(form.getId())
                .formVersionId(formVersion.getId())
                .formHash(formHash)
                .xmlns(xmlFormId)
                .version(version)
                .title(title)
                .warnings(List.copyOf(warnings))
                .info(List.copyOf(info))
                .sheetHeaders(preflight.getSheetHeaders())
                .extraColumns(preflight.getExtraColumns())
                .build();
    }

    private String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte b : digest) value.append(String.format("%02x", b & 0xff));
            return value.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String safeMessage(Throwable throwable) {
        return throwable.getMessage() == null || throwable.getMessage().isBlank()
                ? throwable.getClass().getSimpleName()
                : throwable.getMessage();
    }

    @Value
    @Builder
    public static class ImportResult {
        UUID formId;
        UUID formVersionId;
        String formHash;
        String xmlns;
        String version;
        String title;
        @Builder.Default List<String> warnings = List.of();
        @Builder.Default List<String> info = List.of();
        @Builder.Default Map<String, List<String>> sheetHeaders = Map.of();
        @Builder.Default Map<String, List<String>> extraColumns = Map.of();
    }

    @Value
    public static class ValidationException extends RuntimeException {
        List<String> errors;
        List<String> warnings;
        List<String> info;

        public ValidationException(List<String> errors, List<String> warnings, List<String> info) {
            super(errors == null || errors.isEmpty() ? "XLSForm validation failed" : errors.get(0));
            this.errors = errors == null ? List.of() : List.copyOf(errors);
            this.warnings = warnings == null ? List.of() : List.copyOf(warnings);
            this.info = info == null ? List.of() : List.copyOf(info);
        }
    }

    public static class DuplicateVersionException extends RuntimeException {
        private final String formId;
        private final String version;

        public DuplicateVersionException(String formId, String version) {
            super("Form '" + formId + "' already has version '" + version + "'");
            this.formId = formId;
            this.version = version;
        }

        public String getFormId() { return formId; }
        public String getVersion() { return version; }
    }
}
