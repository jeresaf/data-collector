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
    /**
     * ODK Collect deliberately requests and validates text/xml for OpenRosa
     * XML resources. application/xml is valid XML generally, but Collect's
     * OpenRosa client rejects it for form lists/manifests before parsing.
     */
    private static final String OPENROSA_XML_MEDIA_TYPE = "text/xml;charset=UTF-8";
    private static final MediaType OPENROSA_XML = MediaType.parseMediaType(OPENROSA_XML_MEDIA_TYPE);
    private static final long MAX_CONTENT_LENGTH = 104857600L; // 100MB

    /**
     * GET /v1/projects/{projectId}/formList
     * Returns a list of published forms for the project in OpenRosa XML format
     * Filters forms based on app user assignments if authenticated as an app user
     */
    @GetMapping(value = "/formList", produces = OPENROSA_XML_MEDIA_TYPE)
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
            // Never fall back to an unrestricted form list when identity or
            // assignment resolution fails (for example, during revocation).
            log.warn("OpenRosa formList authorization lookup failed", e);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<xforms xmlns=\"http://openrosa.org/xforms/xformsList\">\n");

        for (XForm form : forms) {
            Optional<FormVersion> latestVersion = formVersionRepository.findLatestByFormId(form.getId());
            if (latestVersion.isPresent()) {
                FormVersion fv = latestVersion.get();
                xml.append("  <xform>\n");
                // formID is the canonical OpenRosa/XForms identifier expected by
                // ODK Collect. Keep xmlns as an additional compatibility field,
                // but do not substitute it for the required formID element.
                xml.append("    <formID>").append(escapeXml(fv.getXmlns())).append("</formID>\n");
                xml.append("    <name>").append(escapeXml(form.getTitle())).append("</name>\n");
                xml.append("    <version>").append(escapeXml(fv.getVersion())).append("</version>\n");
                xml.append("    <hash>md5:").append(computeMd5(fv.getXmlContent())).append("</hash>\n");
                xml.append("    <downloadUrl>").append(buildUrl(request, "/v1/projects/").append(projectId).append("/forms/").append(fv.getXmlns()).append(".xml")).append("</downloadUrl>\n");
                xml.append("    <manifestUrl>").append(buildUrl(request, "/v1/projects/").append(projectId).append("/forms/").append(fv.getXmlns()).append("/manifest")).append("</manifestUrl>\n");
                xml.append("  </xform>\n");
            }
        }

        xml.append("</xforms>");

        return ResponseEntity.ok()
                .contentType(OPENROSA_XML)
                .body(xml.toString());
    }

    /**
     * GET /v1/projects/{projectId}/forms/{xmlFormId}.xml
     * Returns the form XML for the given form ID
     * Filters by app user assignments if authenticated as an app user
     */
    @GetMapping(value = "/forms/{xmlFormId}.xml", produces = OPENROSA_XML_MEDIA_TYPE)
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
            // An authorization lookup failure must not grant form/media access.
            log.warn("OpenRosa form resource authorization lookup failed", e);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
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
                .contentType(OPENROSA_XML)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fv.getXmlns() + ".xml\"")
                .body(fv.getXmlContent());
    }

    /**
     * GET /v1/projects/{projectId}/forms/{xmlFormId}/manifest
     * Returns the form manifest (list of media files and external datasets)
     * Includes download URLs for media files
     */
    @GetMapping(value = "/forms/{xmlFormId}/manifest", produces = OPENROSA_XML_MEDIA_TYPE)
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
            // An authorization lookup failure must not grant form/media access.
            log.warn("OpenRosa form resource authorization lookup failed", e);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        Optional<FormVersion> latestVersion = formVersionRepository.findLatestByFormId(form.getId());
        if (latestVersion.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        FormVersion fv = latestVersion.get();

        // Build URLs from the externally visible request. When the application
        // is behind Cloudflare/Caddy/Nginx, buildBaseUrl() honours forwarded
        // protocol/host headers so Collect receives HTTPS links, not the
        // backend container's internal HTTP scheme.
        String baseUrl = buildBaseUrl(request);

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<manifest xmlns=\"http://openrosa.org/xforms/xformsManifest\">\n");

        // OpenRosa manifests contain supporting files only. Every mediaFile
        // must include filename, hash and downloadUrl. Use the actual uploaded
        // files for this form version rather than metadata placeholders.
        addAvailableFormFilesToManifest(xml, fv, baseUrl, projectId, xmlFormId);

        xml.append("</manifest>");

        return ResponseEntity.ok()
                .contentType(OPENROSA_XML)
                .body(xml.toString());
    }

    /**
     * Add all supporting files that are actually available for this form
     * version. ODK Collect expects each manifest entry to have a filename, an
     * md5 hash and a downloadable URL.
     */
    private void addAvailableFormFilesToManifest(
            StringBuilder xml,
            FormVersion formVersion,
            String baseUrl,
            UUID projectId,
            String xmlFormId) {

        Set<String> addedFilenames = new HashSet<>();

        for (FormAttachment attachment : formAttachmentRepository.findByFormVersionId(formVersion.getId())) {
            String filename = attachment.getFileName();
            if (filename == null || filename.isBlank() || !addedFilenames.add(filename)) {
                continue;
            }

            String hash = attachment.getContentHash();
            if (hash == null || hash.isBlank()) {
                continue;
            }

            appendManifestFile(xml, filename, hash,
                    baseUrl + "/v1/projects/" + projectId + "/forms/"
                            + xmlFormId + "/attachments/" + encodePathSegment(filename));
        }

        for (FormExternalDataset dataset :
                formExternalDatasetRepository.findByFormVersionIdAndIsActiveTrue(formVersion.getId())) {
            String filename = dataset.getDatasetId();
            if (filename == null || filename.isBlank() || !addedFilenames.add(filename)) {
                continue;
            }

            String hash = dataset.getContentHash();
            if (hash == null || hash.isBlank()) {
                continue;
            }

            // Only explicitly declared, fully validated Entity Lists are typed.
            // For an explicitly typed dataset, fail closed when storage bytes,
            // schema, or stored MD5 disagree. Ordinary media stays untyped.
            if (isDeclaredTypedDataset(dataset)) {
                Optional<String> type = verifiedDatasetType(dataset);
                if (type.isEmpty()) {
                    log.warn("Skipping unqualified typed Entity List in manifest: {}", dataset.getId());
                    continue;
                }
                appendManifestFile(xml, filename, hash,
                        baseUrl + "/v1/projects/" + projectId + "/forms/"
                                + xmlFormId + "/attachments/" + encodePathSegment(filename),
                        type.get());
            } else {
                appendManifestFile(xml, filename, hash,
                        baseUrl + "/v1/projects/" + projectId + "/forms/"
                                + xmlFormId + "/attachments/" + encodePathSegment(filename));
            }
        }
    }

    private void appendManifestFile(StringBuilder xml, String filename, String hash, String downloadUrl) {
        appendManifestFile(xml, filename, hash, downloadUrl, null);
    }

    private void appendManifestFile(StringBuilder xml, String filename, String hash, String downloadUrl,
                                    String validatedType) {
        xml.append("  <mediaFile");
        if (validatedType != null) {
            xml.append(" type=\"").append(validatedType).append("\"");
        }
        xml.append(">\n");
        xml.append("    <filename>").append(escapeXml(filename)).append("</filename>\n");
        xml.append("    <hash>md5:").append(escapeXml(stripMd5Prefix(hash))).append("</hash>\n");
        xml.append("    <downloadUrl>").append(escapeXml(downloadUrl)).append("</downloadUrl>\n");
        xml.append("  </mediaFile>\n");
    }

    private boolean isDeclaredTypedDataset(FormExternalDataset dataset) {
        return dataset.getColumnDefinitions() != null
                && dataset.getColumnDefinitions().containsKey(EntityListManifestPreflight.METADATA_KEY);
    }

    private Optional<String> verifiedDatasetType(FormExternalDataset dataset) {
        String filename = dataset.getDatasetId();
        if (filename == null || !EntityListManifestPreflight.validTypedFilename(filename)
                || dataset.getFileSize() != null && dataset.getFileSize() > 20_000_000L) {
            return Optional.empty();
        }
        try {
            byte[] bytes = storageServiceFactory.getStorageService(dataset.getStorageDriver())
                    .read(dataset.getStoragePath());
            return bytes.length > 20_000_000 ? Optional.empty()
                    : EntityListManifestPreflight.verifiedCandidate(
                            dataset.getColumnDefinitions(), bytes, dataset.getContentHash());
        } catch (Exception e) {
            log.warn("Unable to qualify typed Entity List storage bytes for {}", dataset.getId(), e);
            return Optional.empty();
        }
    }

    private String stripMd5Prefix(String hash) {
        return hash != null && hash.regionMatches(true, 0, "md5:", 0, 4)
                ? hash.substring(4)
                : hash;
    }

    private String encodePathSegment(String value) {
        try {
            return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
        } catch (Exception e) {
            return value;
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
            // An authorization lookup failure must not grant form/media access.
            log.warn("OpenRosa form resource authorization lookup failed", e);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // Get the latest form version
        Optional<FormVersion> latestVersion = formVersionRepository.findLatestByFormId(form.getId());
        if (latestVersion.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        FormVersion fv = latestVersion.get();

        // Find the attachment by filename
        Optional<FormAttachment> attachmentOpt =
                formAttachmentRepository.findByFormVersionIdAndFileName(fv.getId(), filename);

        // Ordinary form media.
        if (attachmentOpt.isPresent()) {
            FormAttachment attachment = attachmentOpt.get();
            try {
                StorageService storageService = storageServiceFactory.getStorageService(attachment.getStorageDriver());
                Resource resource = storageService.loadAsResource(attachment.getStoragePath());

                if (resource == null || !resource.exists()) {
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
                }

                String contentType = attachment.getContentType();
                if (contentType == null || contentType.isBlank()) {
                    contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
                }

                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(contentType))
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"" + attachment.getFileName() + "\"")
                        .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(attachment.getFileSize()))
                        .body(resource);
            } catch (Exception e) {
                log.error("Failed to serve attachment {}: {}", filename, e.getMessage(), e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
        }

        // External CSV/XML datasets are also OpenRosa form attachments from
        // Collect's perspective. Serve active datasets through the same URL
        // shape used by the manifest.
        Optional<FormExternalDataset> datasetOpt =
                formExternalDatasetRepository.findByFormVersionIdAndDatasetId(fv.getId(), filename);
        if (datasetOpt.isPresent() && Boolean.TRUE.equals(datasetOpt.get().getIsActive())) {
            FormExternalDataset dataset = datasetOpt.get();
            try {
                StorageService storageService = storageServiceFactory.getStorageService(dataset.getStorageDriver());
                Resource resource;
                if (isDeclaredTypedDataset(dataset)) {
                    // Re-read and verify the exact bytes used in this response.
                    // A changed file must never be served using a stale manifest
                    // hash; using ByteArrayResource prevents a second read race.
                    byte[] bytes = storageService.read(dataset.getStoragePath());
                    if (bytes.length > 20_000_000 || !EntityListManifestPreflight.validTypedFilename(dataset.getDatasetId())
                            || EntityListManifestPreflight.verifiedCandidate(
                                    dataset.getColumnDefinitions(), bytes, dataset.getContentHash()).isEmpty()) {
                        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
                    }
                    resource = new org.springframework.core.io.ByteArrayResource(bytes);
                } else {
                    resource = storageService.loadAsResource(dataset.getStoragePath());
                    if (resource == null || !resource.exists()) {
                        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
                    }
                }

                String contentType = dataset.getContentType();
                if (contentType == null || contentType.isBlank()) {
                    contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
                }

                ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(contentType))
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"" + dataset.getDatasetId() + "\"");
                if (isDeclaredTypedDataset(dataset)) {
                    builder.header(HttpHeaders.CONTENT_LENGTH, String.valueOf(resource.contentLength()));
                } else if (dataset.getFileSize() != null) {
                    builder.header(HttpHeaders.CONTENT_LENGTH, String.valueOf(dataset.getFileSize()));
                }
                return builder.body(resource);
            } catch (Exception e) {
                log.error("Failed to serve external dataset {}: {}", filename, e.getMessage(), e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
        }

        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
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

        Optional<Project> projectOpt = projectRepository.findById(projectId);
        if (projectOpt.isEmpty() || !projectOpt.get().getIsActive()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        // ODK Collect uses HEAD /submission as an OpenRosa capability probe.
        // A compliant submission endpoint must answer this probe with 204 No Content.
        // Collect intentionally rejects other 2xx responses (including 200) and will
        // not attempt the multipart POST when the probe returns the wrong status.
        return ResponseEntity.noContent().build();
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
                    .contentType(OPENROSA_XML)
                    .body(xml.toString());
        } catch (OpenRosaSubmissionService.IntakeException e) {
            return ResponseEntity.status(e.getStatus())
                    .contentType(OPENROSA_XML)
                    .body(buildErrorResponse(e.getMessage()));
        } catch (RuntimeException e) {
            log.error("Unexpected OpenRosa submission failure", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(OPENROSA_XML)
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
        response.setContentType(OPENROSA_XML_MEDIA_TYPE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("X-OpenRosa-Accept-Content-Length", String.valueOf(MAX_CONTENT_LENGTH));
    }

    private StringBuilder buildUrl(HttpServletRequest request, String path) {
        return new StringBuilder(buildBaseUrl(request)).append(path);
    }

    /**
     * Resolve the public origin of a request that may have passed through a TLS
     * terminating reverse proxy such as Cloudflare Tunnel, Caddy or Nginx.
     * Spring's ForwardedHeaderFilter (enabled in application.yml) normally
     * rewrites these servlet values for us; the X-Forwarded fallbacks below
     * make OpenRosa URL generation resilient during development as well.
     */
    private String buildBaseUrl(HttpServletRequest request) {
        String scheme = firstForwardedValue(request.getHeader("X-Forwarded-Proto"));
        if (!StringUtils.hasText(scheme)) {
            scheme = request.getScheme();
        }

        String forwardedHost = firstForwardedValue(request.getHeader("X-Forwarded-Host"));
        String host;
        if (StringUtils.hasText(forwardedHost)) {
            host = forwardedHost;
        } else {
            host = request.getServerName();
            int port = request.getServerPort();
            String forwardedPort = firstForwardedValue(request.getHeader("X-Forwarded-Port"));
            if (StringUtils.hasText(forwardedPort)) {
                try {
                    port = Integer.parseInt(forwardedPort);
                } catch (NumberFormatException ignored) {
                    // Keep servlet port.
                }
            }
            if (("http".equalsIgnoreCase(scheme) && port != 80)
                    || ("https".equalsIgnoreCase(scheme) && port != 443)) {
                host = host + ":" + port;
            }
        }

        return scheme + "://" + host + request.getContextPath();
    }

    private String firstForwardedValue(String header) {
        if (!StringUtils.hasText(header)) {
            return null;
        }
        int comma = header.indexOf(',');
        return (comma >= 0 ? header.substring(0, comma) : header).trim();
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
