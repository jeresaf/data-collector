package org.odk.platform.controller;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Builder;
import org.odk.platform.audit.AuditActions;
import org.odk.platform.audit.AuditService;
import org.odk.platform.domain.FormVersion;
import org.odk.platform.domain.Project;
import org.odk.platform.domain.ProjectSettings;
import org.odk.platform.domain.XForm;
import org.odk.platform.domain.AppUser;
import org.odk.platform.domain.AppUserProject;
import org.odk.platform.domain.User;
import org.odk.platform.service.FormAssignmentService;
import org.odk.platform.service.FormMetadataExtractor;
import org.odk.platform.service.FormPublishHistoryService;
import org.odk.platform.service.FormVersionService;
import org.odk.platform.service.JavaRosaFormLoader;
import org.odk.platform.service.JavaRosaFormValidator;
import org.odk.platform.service.ProjectService;
import org.odk.platform.service.XFormService;
import org.odk.platform.service.XlsFormImportService;
import org.odk.platform.service.PyxformConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.javarosa.core.model.FormDef;
import org.javarosa.core.model.instance.TreeElement;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;
    private final AuditService auditService;
    private final FormAssignmentService formAssignmentService;
    private final FormMetadataExtractor formMetadataExtractor;
    private final FormPublishHistoryService formPublishHistoryService;
    private final FormVersionService formVersionService;
    private final JavaRosaFormValidator javaRosaFormValidator;
    private final JavaRosaFormLoader javaRosaFormLoader;
    private final XFormService xFormService;
    private final XlsFormImportService xlsFormImportService;

    @PostMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('ADMIN') or hasRole('PROJECT_MANAGER')")
    public ResponseEntity<ProjectSummaryResponse> createProject(@Valid @RequestBody CreateProjectRequest request) {
        Project project = projectService.createProject(request.getName(), request.getDescription());
        return ResponseEntity.ok(toProjectSummaryResponse(project));
    }

    @GetMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('USER')")
    public ResponseEntity<Page<ProjectSummaryResponse>> listProjects(
            @PageableDefault(size = 20) Pageable pageable) {
        Page<ProjectSummaryResponse> projects = projectService.findAll(pageable)
                .map(this::toProjectSummaryResponse);
        return ResponseEntity.ok(projects);
    }

    @GetMapping("/my")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('USER')")
    public ResponseEntity<Page<ProjectSummaryResponse>> listMyProjects(
            @PageableDefault(size = 20) Pageable pageable) {
        Page<ProjectSummaryResponse> projects = projectService.findByMemberUserId(
                        projectService.getCurrentUserId(), pageable)
                .map(this::toProjectSummaryResponse);
        return ResponseEntity.ok(projects);
    }

    @GetMapping("/{projectId}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('USER')")
    public ResponseEntity<ProjectSummaryResponse> getProject(@PathVariable UUID projectId) {
        return projectService.findById(projectId)
                .map(this::toProjectSummaryResponse)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{projectId}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('ADMIN') or hasRole('PROJECT_MANAGER')")
    public ResponseEntity<ProjectSummaryResponse> updateProject(
            @PathVariable UUID projectId,
            @Valid @RequestBody UpdateProjectRequest request) {
        try {
            Project project = projectService.updateProject(
                    projectId, request.getName(), request.getDescription(), request.getIsActive());
            return ResponseEntity.ok(toProjectSummaryResponse(project));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{projectId}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('ADMIN')")
    public ResponseEntity<Void> deleteProject(@PathVariable UUID projectId) {
        try {
            projectService.deleteProject(projectId);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{projectId}/archive")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('ADMIN') or hasRole('PROJECT_MANAGER')")
    public ResponseEntity<Void> archiveProject(@PathVariable UUID projectId) {
        try {
            projectService.archiveProject(projectId);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // Project Settings endpoints
    @GetMapping("/{projectId}/settings")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('USER')")
    public ResponseEntity<Map<String, Object>> getProjectSettings(@PathVariable UUID projectId) {
        Map<String, Object> settings = projectService.getAllSettingsAsMap(projectId);
        return ResponseEntity.ok(settings);
    }

    @GetMapping("/{projectId}/settings/{settingKey}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('USER')")
    public ResponseEntity<ProjectSettingResponse> getProjectSetting(
            @PathVariable UUID projectId,
            @PathVariable String settingKey) {
        return projectService.getSetting(projectId, settingKey)
                .map(this::toProjectSettingResponse)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{projectId}/settings/{settingKey}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('ADMIN') or hasRole('PROJECT_MANAGER')")
    public ResponseEntity<ProjectSettingResponse> updateProjectSetting(
            @PathVariable UUID projectId,
            @PathVariable String settingKey,
            @Valid @RequestBody UpdateSettingRequest request) {
        ProjectSettings settings = projectService.updateSetting(
                projectId, settingKey, request.getValue(), request.getDescription());
        return ResponseEntity.ok(toProjectSettingResponse(settings));
    }

    @DeleteMapping("/{projectId}/settings/{settingKey}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('ADMIN')")
    public ResponseEntity<Void> deleteProjectSetting(
            @PathVariable UUID projectId,
            @PathVariable String settingKey) {
        projectService.deleteSetting(projectId, settingKey);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{projectId}/settings/initialize")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('ADMIN') or hasRole('PROJECT_MANAGER')")
    public ResponseEntity<Void> initializeDefaultSettings(@PathVariable UUID projectId) {
        projectService.initializeDefaultSettings(projectId, projectService.getCurrentUser());
        return ResponseEntity.ok().build();
    }

    // Form upload endpoints
    @PostMapping("/{projectId}/forms/upload")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('ADMIN') or hasRole('PROJECT_MANAGER')")
    public ResponseEntity<?> uploadFormXml(
            @PathVariable UUID projectId,
            @RequestParam(value = "file", required = false) MultipartFile file) {

        Project project = projectService.findById(projectId).orElse(null);
        if (project == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ErrorResponse.builder()
                            .error("PROJECT_NOT_FOUND")
                            .message("Project not found: " + projectId)
                            .build());
        }

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.builder()
                            .error("INVALID_FORM_FILE")
                            .message("An XForm XML file is required")
                            .build());
        }

        try {
            String xmlContent = new String(file.getBytes(), StandardCharsets.UTF_8);

            try {
                javaRosaFormValidator.validateOrThrow(xmlContent);
            } catch (Exception e) {
                auditService.logError(
                        AuditActions.FORM_VALIDATED,
                        "FORM",
                        null,
                        auditFileName(file),
                        auditDetails("projectId", projectId, "source", "xml_upload"),
                        e.getMessage() == null ? "XForm validation failed" : e.getMessage());
                return ResponseEntity.badRequest()
                        .body(ErrorResponse.builder()
                                .error("INVALID_XFORM")
                                .message("XForm validation failed"
                                        + (e.getMessage() == null ? "" : ": " + e.getMessage()))
                                .build());
            }

            FormDef formDef = javaRosaFormLoader.loadFormDef(xmlContent);

            String formId = javaRosaFormLoader.getFormId(formDef);
            String extractedVersion = javaRosaFormLoader.getFormVersion(formDef);
            String title = javaRosaFormLoader.getFormTitle(formDef);
            String formHash = javaRosaFormLoader.generateFormHash(xmlContent);

            if (formId == null || formId.isBlank()) {
                return ResponseEntity.badRequest()
                        .body(ErrorResponse.builder()
                                .error("MISSING_FORM_ID")
                                .message("XForm primary instance is missing a form ID")
                                .build());
            }

            /*
             * The existing persistence/API model calls this property `xmlns`, but
             * throughout this project it is used as the OpenRosa xmlFormId
             * (for duplicate detection and /forms/{xmlFormId} routes).
             *
             * Keep JavaRosa's actual namespace semantics separate and persist the
             * canonical form ID here.
             */
            String xmlns = formId;

            /*
             * Older/minimal XForms in the integration fixtures omit the version
             * attribute. The API has historically treated those as version 1.0.
             */
            String version = (extractedVersion == null || extractedVersion.isBlank())
                    ? "1.0"
                    : extractedVersion;

            Optional<XForm> existingForm = xFormService.findByXmlnsAndProjectId(xmlns, projectId);
            if (existingForm.isPresent()) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(ErrorResponse.builder()
                                .error("FORM_ALREADY_EXISTS")
                                .message("A form with ID '" + formId + "' already exists in this project")
                                .build());
            }

            XForm form = XForm.builder()
                    .project(project)
                    .title(title)
                    .xmlns(xmlns)
                    .version(version)
                    .xmlContent(xmlContent)
                    .createdBy(projectService.getCurrentUser())
                    .build();
            form = xFormService.save(form);

            FormVersion formVersionEntity = FormVersion.builder()
                    .form(form)
                    .version(version)
                    .xmlContent(xmlContent)
                    .xmlns(xmlns)
                    .isPublished(false)
                    .isLatest(true)
                    .createdBy(projectService.getCurrentUser())
                    .build();
            formVersionEntity = formVersionService.save(formVersionEntity);

            try {
                FormMetadataExtractor.FormMetadataResult metadata =
                        formMetadataExtractor.extractMetadata(formDef, xmlContent);
                formMetadataExtractor.storeMetadata(formVersionEntity, metadata);
                formVersionService.save(formVersionEntity);
            } catch (Exception e) {
                // Metadata enrichment is non-fatal for an otherwise valid XForm.
                System.err.println("Failed to extract metadata: " + e.getMessage());
            }

            Map<String, Object> uploadAudit = auditDetails(
                    "projectId", projectId,
                    "xmlFormId", formId,
                    "version", version,
                    "formVersionId", formVersionEntity.getId(),
                    "source", "xml_upload",
                    "fileName", auditFileName(file));
            auditService.log(AuditActions.FORM_VALIDATED, "FORM", form.getId(), title, uploadAudit);
            auditService.log(AuditActions.FORM_UPLOADED, "FORM", form.getId(), title, uploadAudit);

            // Do not expose the JPA entity graph directly. XForm has relationships
            // back to Project/FormVersion which can recurse during JSON serialization.
            return ResponseEntity.ok(XFormResponse.builder()
                    .id(form.getId())
                    .name(title)
                    .title(title)
                    .description(form.getDescription())
                    .xmlns(xmlns)
                    .version(version)
                    .xmlContent(xmlContent)
                    .md5Hash(formHash)
                    .isPublished(false)
                    .isActive(true)
                    .formVersionId(formVersionEntity.getId())
                    .createdAt(form.getCreatedAt())
                    .updatedAt(form.getUpdatedAt())
                    .build());

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ErrorResponse.builder()
                            .error("FORM_UPLOAD_FAILED")
                            .message(e.getMessage() == null ? "Form upload failed" : e.getMessage())
                            .build());
        }
    }

    @PostMapping("/{projectId}/forms/validate")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('ADMIN') or hasRole('PROJECT_MANAGER')")
    public ResponseEntity<ValidationResponse> validateFormXml(
            @PathVariable UUID projectId,
            @RequestParam(value = "file", required = false) MultipartFile file) {
        try {
            if (file == null || file.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(ValidationResponse.builder()
                                .valid(false)
                                .errors(List.of("File is empty"))
                                .warnings(List.of())
                                .info(List.of())
                                .build());
            }
            
            String xmlContent = new String(file.getBytes(), StandardCharsets.UTF_8);
            
            // Validate XML using JavaRosa
            boolean isValid = javaRosaFormValidator.validate(xmlContent);
            
            if (isValid) {
                // Load form to extract metadata
                FormDef formDef = javaRosaFormLoader.loadFormDef(xmlContent);
                String formId = javaRosaFormLoader.getFormId(formDef);
                String extractedVersion = javaRosaFormLoader.getFormVersion(formDef);
                String title = javaRosaFormLoader.getFormTitle(formDef);
                String instanceRootNode = javaRosaFormLoader.getInstanceRootNode(formDef);
                String formHash = javaRosaFormLoader.generateFormHash(xmlContent);

                String xmlns = formId;
                String version = (extractedVersion == null || extractedVersion.isBlank())
                        ? "1.0"
                        : extractedVersion;
                
                auditService.log(
                        AuditActions.FORM_VALIDATED,
                        "FORM",
                        null,
                        title != null && !title.isBlank() ? title : formId,
                        auditDetails(
                                "projectId", projectId,
                                "xmlFormId", formId,
                                "version", version,
                                "source", "validation_endpoint",
                                "fileName", auditFileName(file)));

                return ResponseEntity.ok(ValidationResponse.builder()
                        .valid(true)
                        .formId(formId)
                        .version(version)
                        .title(title)
                        .xmlns(xmlns)
                        .instanceRootNode(instanceRootNode)
                        .formHash(formHash)
                        .errors(List.of())
                        .warnings(List.of())
                        .info(List.of())
                        .build());
            } else {
                auditService.logError(
                        AuditActions.FORM_VALIDATED,
                        "FORM",
                        null,
                        auditFileName(file),
                        auditDetails("projectId", projectId, "source", "validation_endpoint"),
                        "Form validation failed");
                return ResponseEntity.ok(ValidationResponse.builder()
                        .valid(false)
                        .errors(List.of("Form validation failed"))
                        .warnings(List.of())
                        .info(List.of())
                        .build());
            }
                    
        } catch (Exception e) {
            auditService.logError(
                    AuditActions.FORM_VALIDATED,
                    "FORM",
                    null,
                    auditFileName(file),
                    auditDetails("projectId", projectId, "source", "validation_endpoint"),
                    e.getMessage() == null ? "Validation failed" : e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ValidationResponse.builder()
                            .valid(false)
                            .errors(List.of("Validation failed: " + e.getMessage()))
                            .warnings(List.of())
                            .info(List.of())
                            .build());
        }
    }
    
    @DeleteMapping("/{projectId}/forms/{formId}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('ADMIN') or hasRole('PROJECT_MANAGER')")
    public ResponseEntity<Void> deleteForm(
            @PathVariable UUID projectId,
            @PathVariable UUID formId) {
        try {
            XForm form = xFormService.findByIdAndProjectId(formId, projectId)
                    .orElseThrow(() -> new IllegalArgumentException("Form not found: " + formId));
            
            // Delete form versions first
            List<FormVersion> versions = formVersionService.findByFormIdOrderByVersionDesc(formId);
            for (FormVersion version : versions) {
                formVersionService.deleteById(version.getId());
            }
            
            // Delete form
            xFormService.deleteById(formId);
            
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    @PostMapping("/{projectId}/forms/upload-xlsform")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('ADMIN') or hasRole('PROJECT_MANAGER')")
    public ResponseEntity<FormUploadResponse> uploadXLSForm(
            @PathVariable UUID projectId,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "version", required = false) String version,
            @RequestParam(value = "changelog", required = false) String changelog) {

        Project project = projectService.findById(projectId).orElse(null);
        if (project == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(FormUploadResponse.builder()
                            .success(false)
                            .message("Project not found: " + projectId)
                            .errors(List.of("Project not found"))
                            .warnings(List.of())
                            .info(List.of())
                            .build());
        }
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(FormUploadResponse.builder()
                            .success(false)
                            .message("An XLSForm .xlsx file is required")
                            .errors(List.of("An XLSForm .xlsx file is required"))
                            .warnings(List.of())
                            .info(List.of())
                            .build());
        }

        try {
            XlsFormImportService.ImportResult result = xlsFormImportService.importWorkbook(
                    project,
                    file.getBytes(),
                    file.getOriginalFilename(),
                    version,
                    changelog,
                    projectService.getCurrentUser());

            Map<String, Object> xlsAudit = auditDetails(
                    "projectId", projectId,
                    "formVersionId", result.getFormVersionId(),
                    "xmlFormId", result.getXmlns(),
                    "version", result.getVersion(),
                    "source", "xlsform",
                    "fileName", auditFileName(file));
            auditService.log(AuditActions.FORM_VALIDATED, "FORM", result.getFormId(), result.getTitle(), xlsAudit);
            auditService.log(AuditActions.FORM_UPLOADED, "FORM", result.getFormId(), result.getTitle(), xlsAudit);

            return ResponseEntity.ok(FormUploadResponse.builder()
                    .success(true)
                    .message("XLSForm converted, validated and stored successfully")
                    .formId(result.getFormId())
                    .formVersionId(result.getFormVersionId())
                    .formHash(result.getFormHash())
                    .xmlns(result.getXmlns())
                    .version(result.getVersion())
                    .title(result.getTitle())
                    .errors(List.of())
                    .warnings(result.getWarnings())
                    .info(result.getInfo())
                    .sheetHeaders(result.getSheetHeaders())
                    .extraColumns(result.getExtraColumns())
                    .build());
        } catch (XlsFormImportService.ValidationException e) {
            auditService.logError(
                    AuditActions.FORM_VALIDATED,
                    "FORM",
                    null,
                    auditFileName(file),
                    auditDetails("projectId", projectId, "source", "xlsform"),
                    e.getMessage());
            return ResponseEntity.badRequest()
                    .body(FormUploadResponse.builder()
                            .success(false)
                            .message("XLSForm validation failed")
                            .errors(e.getErrors())
                            .warnings(e.getWarnings())
                            .info(e.getInfo())
                            .build());
        } catch (XlsFormImportService.DuplicateVersionException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(FormUploadResponse.builder()
                            .success(false)
                            .message(e.getMessage())
                            .errors(List.of(e.getMessage()))
                            .warnings(List.of())
                            .info(List.of())
                            .build());
        } catch (PyxformConverter.ConversionException e) {
            auditService.logError(
                    AuditActions.FORM_VALIDATED,
                    "FORM",
                    null,
                    auditFileName(file),
                    auditDetails("projectId", projectId, "source", "xlsform"),
                    e.getMessage());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(FormUploadResponse.builder()
                            .success(false)
                            .message(e.getMessage())
                            .errors(List.of(e.getMessage()))
                            .warnings(List.of())
                            .info(List.of())
                            .build());
        } catch (IOException e) {
            return ResponseEntity.badRequest()
                    .body(FormUploadResponse.builder()
                            .success(false)
                            .message("Unable to read XLSForm upload: " + e.getMessage())
                            .errors(List.of("Unable to read XLSForm upload"))
                            .warnings(List.of())
                            .info(List.of())
                            .build());
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(FormUploadResponse.builder()
                            .success(false)
                            .message(e.getMessage() == null ? "XLSForm import failed" : e.getMessage())
                            .errors(List.of(e.getMessage() == null ? "XLSForm import failed" : e.getMessage()))
                            .warnings(List.of())
                            .info(List.of())
                            .build());
        }
    }
    
    @GetMapping("/{projectId}/forms")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('USER')")
    public ResponseEntity<Page<XFormResponse>> listForms(
            @PathVariable UUID projectId,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<XFormResponse> forms = xFormService.findByProjectIdAndIsActiveTrue(projectId, pageable)
                .map(this::toXFormResponse);
        return ResponseEntity.ok(forms);
    }
    
    @GetMapping("/{projectId}/forms/{formId}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('USER')")
    public ResponseEntity<XFormResponse> getForm(
            @PathVariable UUID projectId,
            @PathVariable UUID formId) {
        return xFormService.findByIdAndProjectId(formId, projectId)
                .map(this::toXFormResponse)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
    
    @GetMapping("/{projectId}/forms/{formId}/versions")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('USER')")
    public ResponseEntity<List<FormVersion>> listFormVersions(
            @PathVariable UUID projectId,
            @PathVariable UUID formId) {
        List<FormVersion> versions = formVersionService.findByFormIdOrderByVersionDesc(formId);
        return ResponseEntity.ok(versions);
    }
    
    @PostMapping("/{projectId}/forms/{formId}/publish")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('ADMIN') or hasRole('PROJECT_MANAGER')")
    public ResponseEntity<PublishFormResponse> publishForm(
            @PathVariable UUID projectId,
            @PathVariable UUID formId,
            @RequestParam UUID versionId) {
        FormVersion version = formVersionService.findById(versionId)
                .orElseThrow(() -> new IllegalArgumentException("Form version not found: " + versionId));
        
        if (!version.getForm().getId().equals(formId)) {
            return ResponseEntity.badRequest().build();
        }
        
        // Unpublish all other versions
        List<FormVersion> allVersions = formVersionService.findByFormIdOrderByVersionDesc(formId);
        for (FormVersion v : allVersions) {
            if (v.getIsPublished()) {
                v.setIsPublished(false);
                v.setPublishedAt(null);
                v.setPublishedBy(null);
                formVersionService.save(v);
            }
        }
        
        // Publish this version
        User currentUser = projectService.getCurrentUser();
        version.setIsPublished(true);
        version.setIsLatest(true);
        version.setPublishedAt(java.time.Instant.now());
        version.setPublishedBy(currentUser);
        formVersionService.save(version);

        // Persist explicit form lifecycle history in addition to the general audit event.
        formPublishHistoryService.recordPublish(
                version,
                currentUser,
                "Published version " + version.getVersion() + " for form " + version.getForm().getTitle());

        // Update form
        XForm form = version.getForm();
        form.setIsPublished(true);
        form.setCurrentVersion(version);
        form.setPublishedAt(java.time.Instant.now());
        form.setPublishedBy(currentUser);
        xFormService.save(form);

        auditService.log(
                AuditActions.FORM_PUBLISHED,
                "FORM",
                form.getId(),
                form.getTitle(),
                auditDetails(
                        "projectId", projectId,
                        "formVersionId", version.getId(),
                        "version", version.getVersion()));
        return ResponseEntity.ok(PublishFormResponse.builder()
                .formId(form.getId())
                .formVersionId(version.getId())
                .version(version.getVersion())
                .published(Boolean.TRUE.equals(version.getIsPublished()))
                .latest(Boolean.TRUE.equals(version.getIsLatest()))
                .publishedAt(version.getPublishedAt())
                .build());
    }

    @PostMapping("/{projectId}/forms/{formId}/archive")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('ADMIN') or hasRole('PROJECT_MANAGER')")
    public ResponseEntity<Void> archiveForm(
            @PathVariable UUID projectId,
            @PathVariable UUID formId) {
        XForm form = xFormService.findByIdAndProjectId(formId, projectId)
                .orElseThrow(() -> new IllegalArgumentException("Form not found: " + formId));
        
        User currentUser = projectService.getCurrentUser();
        FormVersion versionToArchive = form.getCurrentVersion();
        if (versionToArchive == null) {
            versionToArchive = formVersionService.findByFormIdOrderByVersionDesc(formId)
                    .stream()
                    .filter(version -> Boolean.TRUE.equals(version.getIsPublished()))
                    .findFirst()
                    .orElse(null);
        }

        if (versionToArchive != null) {
            formPublishHistoryService.recordArchive(
                    versionToArchive,
                    currentUser,
                    "Archived form " + form.getTitle());
        }

        form.setIsActive(false);
        form.setIsPublished(false);
        xFormService.save(form);

        return ResponseEntity.ok().build();
    }

    // Form Assignment endpoints for app users (mobile users)
    @PostMapping("/{projectId}/app-users/{appUserId}/form-assignments")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('ADMIN') or hasRole('PROJECT_MANAGER')")
    public ResponseEntity<List<AppUserProject>> assignFormsToAppUser(
            @PathVariable UUID projectId,
            @PathVariable UUID appUserId,
            @Valid @RequestBody AssignFormsRequest request) {
        User currentUser = projectService.getCurrentUser();
        List<AppUserProject> assignments = formAssignmentService.assignFormsToAppUser(
                projectId, appUserId, request.getFormIds(), currentUser);
        auditService.log(
                AuditActions.FORM_ASSIGNED,
                "APP_USER",
                appUserId,
                appUserId.toString(),
                auditDetails(
                        "projectId", projectId,
                        "formIds", request.getFormIds(),
                        "activeAssignmentCount", assignments.size()));
        return ResponseEntity.ok(assignments);
    }

    @GetMapping("/{projectId}/app-users/{appUserId}/form-assignments")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('USER')")
    public ResponseEntity<List<AppUserProject>> getAppUserFormAssignments(
            @PathVariable UUID projectId,
            @PathVariable UUID appUserId) {
        List<AppUserProject> assignments = formAssignmentService.getAppUserFormAssignments(projectId, appUserId);
        return ResponseEntity.ok(assignments);
    }

    @GetMapping("/{projectId}/app-users/{appUserId}/form-assignments/active")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('USER')")
    public ResponseEntity<List<AppUserProject>> getActiveAppUserFormAssignments(
            @PathVariable UUID projectId,
            @PathVariable UUID appUserId) {
        List<AppUserProject> assignments = formAssignmentService.getActiveAppUserFormAssignments(projectId, appUserId);
        return ResponseEntity.ok(assignments);
    }

    @GetMapping("/{projectId}/forms/{formId}/assignments")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('USER')")
    public ResponseEntity<List<AppUserProject>> getFormAssignments(
            @PathVariable UUID projectId,
            @PathVariable UUID formId) {
        List<AppUserProject> assignments = formAssignmentService.getFormAssignments(projectId, formId);
        return ResponseEntity.ok(assignments);
    }

    @DeleteMapping("/{projectId}/app-users/{appUserId}/form-assignments/{formId}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('ADMIN') or hasRole('PROJECT_MANAGER')")
    public ResponseEntity<Void> removeFormAssignment(
            @PathVariable UUID projectId,
            @PathVariable UUID appUserId,
            @PathVariable UUID formId) {
        formAssignmentService.removeFormAssignment(projectId, appUserId, formId);
        auditService.log(
                AuditActions.FORM_UNASSIGNED,
                "APP_USER",
                appUserId,
                appUserId.toString(),
                auditDetails("projectId", projectId, "formId", formId));
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{projectId}/app-users/{appUserId}/form-assignments")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('ADMIN') or hasRole('PROJECT_MANAGER')")
    public ResponseEntity<Void> removeAllFormAssignments(
            @PathVariable UUID projectId,
            @PathVariable UUID appUserId) {
        formAssignmentService.removeAllFormAssignments(projectId, appUserId);
        auditService.log(
                AuditActions.FORM_UNASSIGNED,
                "APP_USER",
                appUserId,
                appUserId.toString(),
                auditDetails("projectId", projectId, "allForms", true));
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{projectId}/app-users/{appUserId}/form-assignments/check/{formId}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('USER')")
    public ResponseEntity<FormAccessResponse> checkFormAccess(
            @PathVariable UUID projectId,
            @PathVariable UUID appUserId,
            @PathVariable UUID formId) {
        boolean hasAccess = formAssignmentService.hasFormAccess(appUserId, projectId, formId);
        return ResponseEntity.ok(FormAccessResponse.builder()
                .hasAccess(hasAccess)
                .build());
    }

    @GetMapping("/{projectId}/app-users/{appUserId}/form-assignments/ids")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('USER')")
    public ResponseEntity<List<UUID>> getAssignedFormIds(
            @PathVariable UUID projectId,
            @PathVariable UUID appUserId) {
        List<UUID> formIds = formAssignmentService.getAssignedFormIds(appUserId, projectId);
        return ResponseEntity.ok(formIds);
    }

    private Map<String, Object> auditDetails(Object... keyValues) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object value = keyValues[i + 1];
            if (value != null) {
                details.put(String.valueOf(keyValues[i]), value);
            }
        }
        return details;
    }

    private String auditFileName(MultipartFile file) {
        if (file == null || file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()) {
            return "form-upload";
        }
        return file.getOriginalFilename();
    }

    private ProjectSummaryResponse toProjectSummaryResponse(Project project) {
        return ProjectSummaryResponse.builder()
                .id(project.getId())
                .name(project.getName())
                .description(project.getDescription())
                .isActive(project.getIsActive())
                .createdAt(project.getCreatedAt())
                .updatedAt(project.getUpdatedAt())
                .build();
    }

    private ProjectSettingResponse toProjectSettingResponse(ProjectSettings settings) {
        return ProjectSettingResponse.builder()
                .settingKey(settings.getSettingKey())
                .settingValue(settings.getSettingValue())
                .description(settings.getDescription())
                .build();
    }

    private XFormResponse toXFormResponse(XForm form) {
        UUID formVersionId = null;
        if (form.getCurrentVersion() != null) {
            formVersionId = form.getCurrentVersion().getId();
        } else if (form.getIsPublished()) {
            // Fallback: find the published version
            List<FormVersion> versions = formVersionService.findByFormIdOrderByVersionDesc(form.getId());
            for (FormVersion v : versions) {
                if (v.getIsPublished()) {
                    formVersionId = v.getId();
                    break;
                }
            }
        }
        
        return XFormResponse.builder()
                .id(form.getId())
                .name(form.getTitle())
                .title(form.getTitle())
                .description(form.getDescription())
                .xmlns(form.getXmlns())
                .version(form.getVersion())
                .xmlContent(form.getXmlContent())
                .isPublished(form.getIsPublished())
                .isActive(form.getIsActive())
                .formVersionId(formVersionId)
                .createdAt(form.getCreatedAt())
                .updatedAt(form.getUpdatedAt())
                .build();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ProjectSummaryResponse {
        private UUID id;
        private String name;
        private String description;
        private Boolean isActive;
        private Instant createdAt;
        private Instant updatedAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ProjectSettingResponse {
        private String settingKey;
        private Map<String, Object> settingValue;
        private String description;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PublishFormResponse {
        private UUID formId;
        private UUID formVersionId;
        private String version;
        private boolean published;
        private boolean latest;
        private Instant publishedAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class XFormResponse {
        private UUID id;
        private String name;
        private String title;
        private String description;
        private String xmlns;
        private String version;
        private String xmlContent;
        private String md5Hash;
        private Boolean isPublished;
        private Boolean isActive;
        private UUID formVersionId;
        private Instant createdAt;
        private Instant updatedAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ErrorResponse {
        private String error;
        private String message;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateProjectRequest {
        @jakarta.validation.constraints.NotBlank(message = "Project name is required")
        private String name;
        private String description;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateProjectRequest {
        private String name;
        private String description;
        private Boolean isActive;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateSettingRequest {
        private Map<String, Object> value;
        private String description;
    }
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class FormUploadResponse {
        private boolean success;
        private String message;
        private UUID formId;
        private UUID formVersionId;
        private String formHash;
        private String xmlns;
        private String version;
        private String title;
        @Builder.Default
        private List<String> errors = List.of();
        @Builder.Default
        private List<String> warnings = List.of();
        @Builder.Default
        private List<String> info = List.of();
        @Builder.Default
        private Map<String, List<String>> sheetHeaders = Map.of();
        @Builder.Default
        private Map<String, List<String>> extraColumns = Map.of();
    }
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ValidationResponse {
        private boolean valid;
        private String formId;
        private String version;
        private String title;
        private String xmlns;
        private String instanceRootNode;
        private String formHash;
        private List<String> errors;
        private List<String> warnings;
        private List<String> info;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AssignFormsRequest {
        private List<UUID> formIds;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class FormAccessResponse {
        private boolean hasAccess;
    }
}
