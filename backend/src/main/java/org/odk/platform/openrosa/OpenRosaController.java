package org.odk.platform.openrosa;

import org.odk.platform.audit.AuditActions;
import org.odk.platform.audit.AuditService;
import org.odk.platform.domain.AppUser;
import org.odk.platform.domain.FormAttachment;
import org.odk.platform.domain.FormExternalDataset;
import org.odk.platform.domain.FormVersion;
import org.odk.platform.domain.Project;
import org.odk.platform.domain.Submission;
import org.odk.platform.domain.XForm;
import org.odk.platform.repository.AppUserProjectRepository;
import org.odk.platform.repository.FormAttachmentRepository;
import org.odk.platform.repository.FormExternalDatasetRepository;
import org.odk.platform.repository.FormVersionRepository;
import org.odk.platform.repository.ProjectRepository;
import org.odk.platform.repository.SubmissionRepository;
import org.odk.platform.repository.XFormRepository;
import org.odk.platform.security.CurrentUserService;
import org.odk.platform.service.OpenRosaSubmissionService;
import org.odk.platform.service.SubmissionAttachmentService;
import org.odk.platform.service.SubmissionReplacementService;
import org.odk.platform.service.storage.StorageService;
import org.odk.platform.service.storage.StorageServiceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * OpenRosa API Controller
 * Implements the OpenRosa API specification for form management and submission
 * Base path: /v1/projects/{projectId}
 */
@RestController
@RequestMapping("/v1/projects/{projectId}")
public class OpenRosaController {

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private XFormRepository xFormRepository;

    @Autowired
    private FormVersionRepository formVersionRepository;

    @Autowired
    private FormAttachmentRepository formAttachmentRepository;

    @Autowired
    private FormExternalDatasetRepository formExternalDatasetRepository;

    @Autowired
    private SubmissionRepository submissionRepository;

    @Autowired
    private AppUserProjectRepository appUserProjectRepository;

    @Autowired
    private CurrentUserService currentUserService;

    @Autowired
    private StorageServiceFactory storageServiceFactory;

    @Autowired
    private SubmissionAttachmentService submissionAttachmentService;

    @Autowired
    private SubmissionReplacementService submissionReplacementService;

    @Autowired
    private AuditService auditService;

    @Autowired
    private OpenRosaSubmissionService openRosaSubmissionService;

    private static final Logger log = LoggerFactory.getLogger(OpenRosaController.class);

    private static final String OPEN_ROSA_VERSION = "1.0";
    private static final long MAX_CONTENT_LENGTH = 104857600L; // 100MB

    /**
     * GET /v1/projects/{projectId}/formList
     * Returns a list of published forms for the project in OpenRosa XML format
     * Filters forms based on app user assignments if authenticated as an app user
     */
    @GetMapping(value = "/formList", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> formList(
            @PathVariable("projectId") UUID projectId,
            HttpServletRequest request,
            HttpServletResponse response) {

        setOpenRosaHeaders(response);

        Optional<Project> projectOpt = projectRepository.findById(projectId);
        if (projectOpt.isEmpty() || !projectOpt.get().getIsActive()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        // Get all published forms for the project
        List<XForm> forms = xFormRepository.findPublishedByProjectId(projectId);

        // Filter forms by app user assignments if authenticated as an app user
        try {
            var currentUser = currentUserService.getCurrentUser();
            if (currentUser.isPresent() && currentUser.get() instanceof AppUser appUser) {
                // Get form IDs assigned to this app user in this project
                List<UUID> assignedFormIds = appUserProjectRepository.findAssignedFormIdsByAppUserIdAndProjectId(appUser.getId(), projectId);
                if (!assignedFormIds.isEmpty()) {
                    // Filter forms to only those assigned to the app user
                    forms = forms.stream()
                            .filter(form -> assignedFormIds.contains(form.getId()))
                            .toList();
                } else {
                    // No forms assigned, return empty list
                    forms = List.of();
                }
            }
        } catch (Exception e) {
            // User not authenticated or not an app user, return all published forms
        }

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<xforms xmlns=\"http://openrosa.org/xforms/xformsList\">\n");

        for (XForm form : forms) {
            Optional<FormVersion> latestVersion = formVersionRepository.findLatestByFormId(form.getId());
            if (latestVersion.isPresent()) {
                FormVersion fv = latestVersion.get();
                xml.append("  <xform>\n");
                xml.append("    <name>").append(escapeXml(form.getTitle())).append("</name>\n");
                xml.append("    <version>").append(escapeXml(fv.getVersion())).append("</version>\n");
                xml.append("    <xmlns>").append(escapeXml(fv.getXmlns())).append("</xmlns>\n");
                xml.append("    <hash>md5:").append(computeMd5(fv.getXmlContent())).append("</hash>\n");
                xml.append("    <downloadUrl>").append(buildUrl(request, "/v1/projects/").append(projectId).append("/forms/").append(fv.getXmlns()).append(".xml")).append("</downloadUrl>\n");
                xml.append("    <manifestUrl>").append(buildUrl(request, "/v1/projects/").append(projectId).append("/forms/").append(fv.getXmlns()).append("/manifest")).append("</manifestUrl>\n");
                xml.append("  </xform>\n");
            }
        }

        xml.append("</xforms>");

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .body(xml.toString());
    }

    /**
     * GET /v1/projects/{projectId}/forms/{xmlFormId}.xml
     * Returns the form XML for the given form ID
     * Filters by app user assignments if authenticated as an app user
     */
    @GetMapping(value = "/forms/{xmlFormId}.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> formDownload(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("xmlFormId") String xmlFormId,
            HttpServletRequest request,
            HttpServletResponse response) {

        setOpenRosaHeaders(response);

        Optional<XForm> formOpt = xFormRepository.findByXmlnsAndProjectId(xmlFormId, projectId);
        if (formOpt.isEmpty() || !formOpt.get().getIsPublished() || !formOpt.get().getIsActive()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        XForm form = formOpt.get();

        // Filter by app user assignments if authenticated as an app user
        try {
            var currentUser = currentUserService.getCurrentUser();
            if (currentUser.isPresent() && currentUser.get() instanceof AppUser appUser) {
                List<UUID> assignedFormIds = appUserProjectRepository.findAssignedFormIdsByAppUserIdAndProjectId(appUser.getId(), projectId);
                if (!assignedFormIds.contains(form.getId())) {
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
                }
            }
        } catch (Exception e) {
            // User not authenticated or not an app user, allow access
        }

        Optional<FormVersion> latestVersion = formVersionRepository.findLatestByFormId(form.getId());
        if (latestVersion.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        FormVersion fv = latestVersion.get();

        auditService.log(
                AuditActions.OPENROSA_FORM_DOWNLOADED,
                "FORM",
                form.getId(),
                form.getTitle(),
                Map.of(
                        "projectId", projectId.toString(),
                        "xmlFormId", xmlFormId,
                        "formVersionId", fv.getId().toString(),
                        "version", fv.getVersion()));

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fv.getXmlns() + ".xml\"")
                .body(fv.getXmlContent());
    }

    /**
     * GET /v1/projects/{projectId}/forms/{xmlFormId}/manifest
     * Returns the form manifest (list of media files and external datasets)
     * Includes download URLs for media files
     */
    @GetMapping(value = "/forms/{xmlFormId}/manifest", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> formManifest(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("xmlFormId") String xmlFormId,
            HttpServletRequest request,
            HttpServletResponse response) {

        setOpenRosaHeaders(response);

        Optional<XForm> formOpt = xFormRepository.findByXmlnsAndProjectId(xmlFormId, projectId);
        if (formOpt.isEmpty() || !formOpt.get().getIsPublished() || !formOpt.get().getIsActive()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        XForm form = formOpt.get();

        // Filter by app user assignments if authenticated as an app user
        try {
            var currentUser = currentUserService.getCurrentUser();
            if (currentUser.isPresent() && currentUser.get() instanceof AppUser appUser) {
                List<UUID> assignedFormIds = appUserProjectRepository.findAssignedFormIdsByAppUserIdAndProjectId(appUser.getId(), projectId);
                if (!assignedFormIds.contains(form.getId())) {
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
                }
            }
        } catch (Exception e) {
            // User not authenticated or not an app user, allow access
        }

        Optional<FormVersion> latestVersion = formVersionRepository.findLatestByFormId(form.getId());
        if (latestVersion.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        FormVersion fv = latestVersion.get();

        // Build base URL for download links
        String baseUrl = ServletUriComponentsBuilder.fromRequestUri(request)
                .replacePath(null)
                .build()
                .toUriString();

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<manifest xmlns=\"http://openrosa.org/xforms/manifest\">\n");
        xml.append("  <mediaFile>\n");
        xml.append("    <filename>").append(escapeXml(fv.getXmlns())).append(".xml</filename>\n");
        xml.append("    <hash>md5:").append(computeMd5(fv.getXmlContent())).append("</hash>\n");
        xml.append("  </mediaFile>\n");

        // Add media files from form version metadata with download URLs
        addMediaFilesToManifest(xml, fv, baseUrl, projectId, xmlFormId);

        // Add external datasets from form version metadata
        addExternalDatasetsToManifest(xml, fv);

        xml.append("</manifest>");

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .body(xml.toString());
    }

    /**
     * Add media files from FormVersion metadata to the manifest XML
     * Includes download URLs for media files
     */
    private void addMediaFilesToManifest(StringBuilder xml, FormVersion formVersion, String baseUrl, UUID projectId, String xmlFormId) {
        Map<String, Object> metadata = formVersion.getMetadata();
        if (metadata == null || metadata.isEmpty()) {
            return;
        }

        // Get media references from metadata
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mediaReferences = (List<Map<String, Object>>) metadata.get("media");
        if (mediaReferences == null || mediaReferences.isEmpty()) {
            return;
        }

        // Get form attachments to find MD5 hashes
        List<FormAttachment> attachments = formAttachmentRepository.findByFormVersionId(formVersion.getId());
        Map<String, String> attachmentHashes = new HashMap<>();
        for (FormAttachment attachment : attachments) {
            if (attachment.getContentHash() != null) {
                attachmentHashes.put(attachment.getFileName(), attachment.getContentHash());
            }
        }

        // Track unique filenames to avoid duplicates
        Set<String> addedFilenames = new HashSet<>();

        for (Map<String, Object> mediaRef : mediaReferences) {
            String filename = (String) mediaRef.get("filename");
            if (filename == null || filename.isBlank() || addedFilenames.contains(filename)) {
                continue;
            }

            String hash = attachmentHashes.get(filename);
            if (hash == null) {
                // If no attachment found, compute hash from filename as fallback
                // In production, this should be the actual file hash
                hash = computeMd5(filename);
            }

            // Build download URL for the media file
            String downloadUrl = baseUrl + "/v1/projects/" + projectId + "/forms/" + xmlFormId + "/attachments/" + filename;

            xml.append("  <mediaFile>\n");
            xml.append("    <filename>").append(escapeXml(filename)).append("</filename>\n");
            xml.append("    <hash>md5:").append(hash).append("</hash>\n");
            xml.append("    <downloadUrl>").append(escapeXml(downloadUrl)).append("</downloadUrl>\n");
            xml.append("  </mediaFile>\n");

            addedFilenames.add(filename);
        }
    }

    /**
     * Add external datasets from FormVersion metadata to the manifest XML
     */
    private void addExternalDatasetsToManifest(StringBuilder xml, FormVersion formVersion) {
        Map<String, Object> metadata = formVersion.getMetadata();
        if (metadata == null || metadata.isEmpty()) {
            return;
        }

        // Get external dataset references from metadata
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> externalDatasets = (List<Map<String, Object>>) metadata.get("externalDatasets");
        if (externalDatasets == null || externalDatasets.isEmpty()) {
            return;
        }

        // Get form external datasets to find content hashes
        List<FormExternalDataset> datasets = formExternalDatasetRepository.findByFormVersionId(formVersion.getId());
        Map<String, String> datasetHashes = new HashMap<>();
        for (FormExternalDataset dataset : datasets) {
            if (dataset.getContentHash() != null) {
                datasetHashes.put(dataset.getDatasetId(), dataset.getContentHash());
            }
        }

        for (Map<String, Object> datasetRef : externalDatasets) {
            String datasetId = (String) datasetRef.get("datasetId");
            if (datasetId == null || datasetId.isBlank()) {
                // Try to get from nodeset or other fields
                datasetId = (String) datasetRef.get("nodeset");
            }
            if (datasetId == null || datasetId.isBlank()) {
                continue;
            }

            String hash = datasetHashes.get(datasetId);
            if (hash == null) {
                // Fallback hash
                hash = computeMd5(datasetId);
            }

            xml.append("  <mediaFile>\n");
            xml.append("    <filename>").append(escapeXml(datasetId)).append("</filename>\n");
            xml.append("    <hash>md5:").append(hash).append("</hash>\n");
            xml.append("  </mediaFile>\n");
        }
    }

    /**
     * GET /v1/projects/{projectId}/forms/{xmlFormId}/attachments/{filename}
     * Returns form attachments (media files)
     * Filters by app user assignments if authenticated as an app user
     * Prevents path traversal attacks
     */
    @GetMapping(value = "/forms/{xmlFormId}/attachments/{filename}")
    public ResponseEntity<Resource> attachmentDownload(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("xmlFormId") String xmlFormId,
            @PathVariable("filename") String filename,
            HttpServletRequest request,
            HttpServletResponse response) {

        setOpenRosaHeaders(response);

        // Prevent path traversal attacks
        if (!isValidFilename(filename)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        Optional<XForm> formOpt = xFormRepository.findByXmlnsAndProjectId(xmlFormId, projectId);
        if (formOpt.isEmpty() || !formOpt.get().getIsPublished() || !formOpt.get().getIsActive()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        XForm form = formOpt.get();

        // Filter by app user assignments if authenticated as an app user
        try {
            var currentUser = currentUserService.getCurrentUser();
            if (currentUser.isPresent() && currentUser.get() instanceof AppUser appUser) {
                List<UUID> assignedFormIds = appUserProjectRepository.findAssignedFormIdsByAppUserIdAndProjectId(appUser.getId(), projectId);
                if (!assignedFormIds.contains(form.getId())) {
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
                }
            }
        } catch (Exception e) {
            // User not authenticated or not an app user, allow access
        }

        // Get the latest form version
        Optional<FormVersion> latestVersion = formVersionRepository.findLatestByFormId(form.getId());
        if (latestVersion.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        FormVersion fv = latestVersion.get();

        // Find the attachment by filename
        Optional<FormAttachment> attachmentOpt = formAttachmentRepository.findByFormVersionIdAndFileName(fv.getId(), filename);
        if (attachmentOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        FormAttachment attachment = attachmentOpt.get();

        // Serve the file from storage
        try {
            StorageService storageService = storageServiceFactory.getStorageService(attachment.getStorageDriver());
            Resource resource = storageService.loadAsResource(attachment.getStoragePath());

            if (resource == null || !resource.exists()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }

            String contentType = attachment.getContentType();
            if (contentType == null) {
                contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + attachment.getFileName() + "\"")
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(attachment.getFileSize()))
                    .body(resource);

        } catch (Exception e) {
            log.error("Failed to serve attachment {}: {}", filename, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * HEAD /v1/projects/{projectId}/submission
     * Returns headers for submission endpoint
     */
    @RequestMapping(value = "/submission", method = RequestMethod.HEAD)
    public ResponseEntity<Void> submissionHead(
            @PathVariable("projectId") UUID projectId,
            HttpServletRequest request,
            HttpServletResponse response) {

        setOpenRosaHeaders(response);
        response.setHeader("X-OpenRosa-Accept-Content-Length", String.valueOf(MAX_CONTENT_LENGTH));

        // HEAD has no response body. Keep the OpenRosa-specific header above
        // as the accepted upload-size limit, and advertise the actual response
        // body length separately.
        response.setContentLength(0);

        Optional<Project> projectOpt = projectRepository.findById(projectId);
        if (projectOpt.isEmpty() || !projectOpt.get().getIsActive()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        return ResponseEntity.ok().build();
    }

    /**
     * POST /v1/projects/{projectId}/submission
     * Accepts form submissions
     */
    @PostMapping(value = "/submission", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> submissionPost(
            @PathVariable("projectId") UUID projectId,
            @RequestPart(value = "xml_submission_file", required = false) MultipartFile xmlFile,
            @RequestPart(value = "xml_submission_file", required = false) MultipartFile xmlFileAlt,
            @RequestParam MultiValueMap<String, MultipartFile> multipartFiles,
            HttpServletRequest request,
            HttpServletResponse response) {

        setOpenRosaHeaders(response);
        MultipartFile submissionFile = xmlFile != null ? xmlFile : xmlFileAlt;

        try {
            OpenRosaSubmissionService.Receipt receipt = openRosaSubmissionService.receive(
                    projectId, submissionFile, multipartFiles, request);

            StringBuilder xml = new StringBuilder();
            xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            xml.append("<OpenRosaResponse xmlns=\"http://openrosa.org/http/response\">\n");
            xml.append("  <message>Submission received</message>\n");
            xml.append("  <status>201</status>\n");
            xml.append("  <instanceID>").append(escapeXml(receipt.instanceId())).append("</instanceID>\n");
            xml.append("</OpenRosaResponse>");

            return ResponseEntity.status(HttpStatus.CREATED)
                    .contentType(MediaType.APPLICATION_XML)
                    .body(xml.toString());
        } catch (OpenRosaSubmissionService.IntakeException e) {
            return ResponseEntity.status(e.getStatus())
                    .contentType(MediaType.APPLICATION_XML)
                    .body(buildErrorResponse(e.getMessage()));
        } catch (RuntimeException e) {
            log.error("Unexpected OpenRosa submission failure", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_XML)
                    .body(buildErrorResponse("Failed to process submission"));
        }
    }

    // ==================== Helper Methods ====================

    private Map<String, Object> submissionAuditDetails(
            UUID projectId,
            XForm form,
            String instanceId,
            HttpServletRequest request) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("projectId", projectId.toString());
        details.put("formId", form.getId().toString());
        details.put("xmlFormId", form.getXmlns());
        details.put("instanceId", instanceId);
        details.put("source", detectSourceClient(
                request.getHeader("User-Agent"),
                request.getHeader("X-OpenRosa-Version")));
        return details;
    }

    private void setOpenRosaHeaders(HttpServletResponse response) {
        response.setHeader("X-OpenRosa-Version", OPEN_ROSA_VERSION);
        response.setHeader("X-OpenRosa-Accept-Content-Length", String.valueOf(MAX_CONTENT_LENGTH));
    }

    private StringBuilder buildUrl(HttpServletRequest request, String path) {
        StringBuilder url = new StringBuilder();
        String scheme = request.getScheme();
        String serverName = request.getServerName();
        int serverPort = request.getServerPort();
        String contextPath = request.getContextPath();

        url.append(scheme).append("://").append(serverName);
        if ((scheme.equals("http") && serverPort != 80) || (scheme.equals("https") && serverPort != 443)) {
            url.append(":").append(serverPort);
        }
        url.append(contextPath).append(path);
        return url;
    }

    private String escapeXml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private String computeMd5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return "unknown";
        }
    }

    private String extractInstanceId(String xml) {
        try {
            int start = xml.indexOf("<instanceID>");
            if (start == -1) start = xml.indexOf("<instanceId>");
            if (start == -1) return null;
            start = xml.indexOf(">", start) + 1;
            int end = xml.indexOf("</", start);
            if (end == -1) return null;
            return xml.substring(start, end).trim();
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> buildSubmissionMetadata(HttpServletRequest request) {
        Map<String, Object> metadata = new LinkedHashMap<>();

        String userAgent = request.getHeader("User-Agent");
        String openRosaVersion = request.getHeader("X-OpenRosa-Version");

        String sourceClient = detectSourceClient(userAgent, openRosaVersion);
        metadata.put("sourceClient", sourceClient);

        if (userAgent != null && !userAgent.isBlank()) {
            metadata.put("userAgent", userAgent);
        }

        if (openRosaVersion != null && !openRosaVersion.isBlank()) {
            metadata.put("openRosaVersion", openRosaVersion);
        }

        return metadata;
    }

    private String detectSourceClient(String userAgent, String openRosaVersion) {
        String normalized = userAgent == null
                ? ""
                : userAgent.toLowerCase(Locale.ROOT);

        if (normalized.contains("odk collect")) {
            return "ODK Collect";
        }

        if (normalized.contains("kobocollect")
                || normalized.contains("kobotoolbox")
                || normalized.contains("kobo collect")) {
            return "KoboToolbox";
        }

        if (normalized.contains("enketo")) {
            return "Enketo";
        }

        if (openRosaVersion != null && !openRosaVersion.isBlank()) {
            return "OpenRosa Client";
        }

        return "Unknown";
    }

    private String extractDeprecatedId(String xml) {
        try {
            int start = xml.indexOf("<deprecatedID>");
            if (start == -1) start = xml.indexOf("<deprecatedId>");
            if (start == -1) return null;

            start = xml.indexOf(">", start) + 1;
            int end = xml.indexOf("</", start);
            if (end == -1) return null;

            String value = xml.substring(start, end).trim();
            return value.isEmpty() ? null : value;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractFormId(String xml) {
        try {
            int start = xml.indexOf("<data");
            if (start == -1) return null;
            int idStart = xml.indexOf("id=\"", start);
            if (idStart == -1) return null;
            idStart += 4;
            int idEnd = xml.indexOf("\"", idStart);
            if (idEnd == -1) return null;
            return xml.substring(idStart, idEnd).trim();
        } catch (Exception e) {
            return null;
        }
    }

    private String buildErrorResponse(String message) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<OpenRosaResponse xmlns=\"http://openrosa.org/http/response\">\n" +
                "  <message>" + escapeXml(message) + "</message>\n" +
                "</OpenRosaResponse>";
    }

    /**
     * Validates filename to prevent path traversal attacks
     * Only allows alphanumeric characters, dots, hyphens, and underscores
     */
    private boolean isValidFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return false;
        }
        
        // Prevent path traversal
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            return false;
        }
        
        // Only allow safe characters: alphanumeric, dot, hyphen, underscore
        return filename.matches("^[a-zA-Z0-9._-]+$");
    }
}
