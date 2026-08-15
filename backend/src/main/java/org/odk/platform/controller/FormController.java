package org.odk.platform.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.odk.platform.domain.FormVersion;
import org.odk.platform.service.FormAttachmentService;
import org.odk.platform.service.FormExternalDatasetService;
import org.odk.platform.service.FormVersionService;
import org.odk.platform.web.dto.form.FormAttachmentDto;
import org.odk.platform.web.dto.form.FormAttachmentUploadRequest;
import org.odk.platform.web.dto.form.FormExternalDatasetDto;
import org.odk.platform.web.dto.form.FormExternalDatasetUploadRequest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/forms/{formId}/versions/{versionId}")
@RequiredArgsConstructor
public class FormController {

    private final FormVersionService formVersionService;
    private final FormAttachmentService attachmentService;
    private final FormExternalDatasetService externalDatasetService;

    // Form Version endpoints
    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('USER')")
    public ResponseEntity<FormVersion> getFormVersion(
            @PathVariable UUID formId,
            @PathVariable UUID versionId) {
        return formVersionService.findById(versionId)
                .filter(fv -> fv.getForm().getId().equals(formId))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Form Attachment endpoints
    @GetMapping("/attachments")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('USER')")
    public ResponseEntity<Page<FormAttachmentDto>> getAttachments(
            @PathVariable UUID formId,
            @PathVariable UUID versionId,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<FormAttachmentDto> attachments = attachmentService.getAttachmentsByFormVersion(versionId, pageable);
        return ResponseEntity.ok(attachments);
    }

    @GetMapping("/attachments/all")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('USER')")
    public ResponseEntity<List<FormAttachmentDto>> getAllAttachments(
            @PathVariable UUID formId,
            @PathVariable UUID versionId) {
        List<FormAttachmentDto> attachments = attachmentService.getAllAttachmentsByFormVersion(versionId);
        return ResponseEntity.ok(attachments);
    }

    @GetMapping("/attachments/{attachmentId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('USER')")
    public ResponseEntity<FormAttachmentDto> getAttachment(
            @PathVariable UUID formId,
            @PathVariable UUID versionId,
            @PathVariable UUID attachmentId) {
        return attachmentService.getAttachmentById(attachmentId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/attachments")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER')")
    public ResponseEntity<FormAttachmentDto> uploadAttachment(
            @PathVariable UUID formId,
            @PathVariable UUID versionId,
            @Valid @ModelAttribute FormAttachmentUploadRequest request,
            @RequestParam("file") MultipartFile file) {
        FormAttachmentDto attachment = attachmentService.uploadAttachment(versionId, request, file);
        return ResponseEntity.ok(attachment);
    }

    @PutMapping("/attachments/{attachmentId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER')")
    public ResponseEntity<FormAttachmentDto> updateAttachment(
            @PathVariable UUID formId,
            @PathVariable UUID versionId,
            @PathVariable UUID attachmentId,
            @Valid @RequestBody FormAttachmentUploadRequest request) {
        FormAttachmentDto attachment = attachmentService.updateAttachment(attachmentId, request);
        return ResponseEntity.ok(attachment);
    }

    @DeleteMapping("/attachments/{attachmentId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER')")
    public ResponseEntity<Void> deleteAttachment(
            @PathVariable UUID formId,
            @PathVariable UUID versionId,
            @PathVariable UUID attachmentId) {
        attachmentService.deleteAttachment(attachmentId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/attachments/{attachmentId}/download")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('USER')")
    public ResponseEntity<Resource> downloadAttachment(
            @PathVariable UUID formId,
            @PathVariable UUID versionId,
            @PathVariable UUID attachmentId) {
        byte[] content = attachmentService.downloadAttachment(attachmentId);
        String contentType = attachmentService.getAttachmentContentType(attachmentId);
        String fileName = attachmentService.getAttachmentFileName(attachmentId);

        ByteArrayResource resource = new ByteArrayResource(content);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentLength(content.length)
                .body(resource);
    }

    @GetMapping("/attachments/count")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('USER')")
    public ResponseEntity<Long> getAttachmentCount(
            @PathVariable UUID formId,
            @PathVariable UUID versionId) {
        long count = attachmentService.getAttachmentCount(versionId);
        return ResponseEntity.ok(count);
    }

    @GetMapping("/attachments/total-size")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('USER')")
    public ResponseEntity<Long> getTotalAttachmentSize(
            @PathVariable UUID formId,
            @PathVariable UUID versionId) {
        long size = attachmentService.getTotalAttachmentSize(versionId);
        return ResponseEntity.ok(size);
    }

    // Form External Dataset endpoints
    @GetMapping("/external-datasets")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('USER')")
    public ResponseEntity<Page<FormExternalDatasetDto>> getExternalDatasets(
            @PathVariable UUID formId,
            @PathVariable UUID versionId,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<FormExternalDatasetDto> datasets = externalDatasetService.getExternalDatasetsByFormVersion(versionId, pageable);
        return ResponseEntity.ok(datasets);
    }

    @GetMapping("/external-datasets/all")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('USER')")
    public ResponseEntity<List<FormExternalDatasetDto>> getAllExternalDatasets(
            @PathVariable UUID formId,
            @PathVariable UUID versionId) {
        List<FormExternalDatasetDto> datasets = externalDatasetService.getAllExternalDatasetsByFormVersion(versionId);
        return ResponseEntity.ok(datasets);
    }

    @GetMapping("/external-datasets/active")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('USER')")
    public ResponseEntity<List<FormExternalDatasetDto>> getActiveExternalDatasets(
            @PathVariable UUID formId,
            @PathVariable UUID versionId) {
        List<FormExternalDatasetDto> datasets = externalDatasetService.getActiveExternalDatasetsByFormVersion(versionId);
        return ResponseEntity.ok(datasets);
    }

    @GetMapping("/external-datasets/{externalDatasetId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('USER')")
    public ResponseEntity<FormExternalDatasetDto> getExternalDataset(
            @PathVariable UUID formId,
            @PathVariable UUID versionId,
            @PathVariable UUID externalDatasetId) {
        return externalDatasetService.getExternalDatasetById(externalDatasetId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/external-datasets")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER')")
    public ResponseEntity<FormExternalDatasetDto> uploadExternalDataset(
            @PathVariable UUID formId,
            @PathVariable UUID versionId,
            @Valid @ModelAttribute FormExternalDatasetUploadRequest request,
            @RequestParam("file") MultipartFile file) {
        FormExternalDatasetDto dataset = externalDatasetService.uploadExternalDataset(versionId, request, file);
        return ResponseEntity.ok(dataset);
    }

    @PutMapping("/external-datasets/{externalDatasetId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER')")
    public ResponseEntity<FormExternalDatasetDto> updateExternalDataset(
            @PathVariable UUID formId,
            @PathVariable UUID versionId,
            @PathVariable UUID externalDatasetId,
            @Valid @RequestBody FormExternalDatasetUploadRequest request) {
        FormExternalDatasetDto dataset = externalDatasetService.updateExternalDataset(externalDatasetId, request);
        return ResponseEntity.ok(dataset);
    }

    @DeleteMapping("/external-datasets/{externalDatasetId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER')")
    public ResponseEntity<Void> deleteExternalDataset(
            @PathVariable UUID formId,
            @PathVariable UUID versionId,
            @PathVariable UUID externalDatasetId) {
        externalDatasetService.deleteExternalDataset(externalDatasetId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/external-datasets/{externalDatasetId}/download")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('USER')")
    public ResponseEntity<Resource> downloadExternalDataset(
            @PathVariable UUID formId,
            @PathVariable UUID versionId,
            @PathVariable UUID externalDatasetId) {
        byte[] content = externalDatasetService.downloadExternalDataset(externalDatasetId);
        String contentType = externalDatasetService.getExternalDatasetContentType(externalDatasetId);
        String datasetId = externalDatasetService.getExternalDatasetDatasetId(externalDatasetId);

        ByteArrayResource resource = new ByteArrayResource(content);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + datasetId + "\"")
                .contentLength(content.length)
                .body(resource);
    }

    @GetMapping("/external-datasets/count")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('USER')")
    public ResponseEntity<Long> getExternalDatasetCount(
            @PathVariable UUID formId,
            @PathVariable UUID versionId) {
        long count = externalDatasetService.getExternalDatasetCount(versionId);
        return ResponseEntity.ok(count);
    }
}