package org.odk.platform.openrosa;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.odk.platform.audit.AuditActions;
import org.odk.platform.audit.AuditService;
import org.odk.platform.domain.AppUser;
import org.odk.platform.domain.AppUserProject;
import org.odk.platform.domain.FormVersion;
import org.odk.platform.domain.Project;
import org.odk.platform.domain.Submission;
import org.odk.platform.domain.XForm;
import org.odk.platform.repository.FormVersionRepository;
import org.odk.platform.repository.ProjectRepository;
import org.odk.platform.repository.SubmissionRepository;
import org.odk.platform.repository.XFormRepository;
import org.odk.platform.security.CurrentUserService;
import org.odk.platform.service.OpenRosaSubmissionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
public class OpenRosaCompatibilityController {

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private XFormRepository xFormRepository;

    @Autowired
    private FormVersionRepository formVersionRepository;

    @Autowired
    private SubmissionRepository submissionRepository;

    @Autowired
    private CurrentUserService currentUserService;

    @Autowired
    private AuditService auditService;

    @Autowired
    private OpenRosaSubmissionService openRosaSubmissionService;

    private static final String OPEN_ROSA_VERSION = "1.0";
    private static final long MAX_CONTENT_LENGTH = 104857600L;
    private static final String OPENROSA_XML_MEDIA_TYPE = "text/xml;charset=UTF-8"; // 100MB

    /**
     * Get the project ID from the current authenticated app user's project assignment.
     * Returns the first active project the app user is assigned to.
     */
    private Optional<UUID> getAppUserProjectId() {
        return currentUserService.getCurrentUser()
                .filter(user -> user instanceof AppUser)
                .map(user -> (AppUser) user)
                .flatMap(appUser -> appUser.getAppUserProjects().stream()
                        .filter(aup -> "ACTIVE".equals(aup.getStatus()))
                        .filter(aup -> aup.getProject() != null && aup.getProject().getIsActive())
                        .map(aup -> aup.getProject().getId())
                        .findFirst());
    }

    /**
     * GET /formList - Compatibility alias for /v1/projects/{projectId}/formList
     * Uses the authenticated app user's project context
     */
    @GetMapping(value = "/formList", produces = OPENROSA_XML_MEDIA_TYPE)
    public ResponseEntity<String> compatibilityFormList(
            HttpServletRequest request,
            HttpServletResponse response) {

        setOpenRosaHeaders(response);

        Optional<UUID> projectIdOpt = getAppUserProjectId();
        if (projectIdOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(buildErrorResponse("No project context available for this user"));
        }

        UUID projectId = projectIdOpt.get();
        Optional<Project> projectOpt = projectRepository.findById(projectId);
        if (projectOpt.isEmpty() || !projectOpt.get().getIsActive()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        List<XForm> forms = xFormRepository.findPublishedByProjectId(projectId);

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
                xml.append("    <xmlns>").append(escapeXml(fv.getXmlns())).append("</xmlns>\n");
                xml.append("    <hash>md5:").append(computeMd5(fv.getXmlContent())).append("</hash>\n");
                xml.append("    <downloadUrl>").append(buildUrl(request, "/v1/projects/").append(projectId).append("/forms/").append(fv.getXmlns()).append(".xml")).append("</downloadUrl>\n");
                xml.append("    <manifestUrl>").append(buildUrl(request, "/v1/projects/").append(projectId).append("/forms/").append(fv.getXmlns()).append("/manifest")).append("</manifestUrl>\n");
                xml.append("  </xform>\n");
            }
        }

        xml.append("</xforms>");

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(OPENROSA_XML_MEDIA_TYPE))
                .body(xml.toString());
    }

    /**
     * GET /forms/{xmlFormId}.xml - Compatibility alias
     * Uses the authenticated app user's project context
     */
    @GetMapping(value = "/forms/{xmlFormId}.xml", produces = OPENROSA_XML_MEDIA_TYPE)
    public ResponseEntity<String> compatibilityFormDownload(
            @PathVariable("xmlFormId") String xmlFormId,
            HttpServletRequest request,
            HttpServletResponse response) {

        setOpenRosaHeaders(response);

        Optional<UUID> projectIdOpt = getAppUserProjectId();
        if (projectIdOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(buildErrorResponse("No project context available for this user"));
        }

        UUID projectId = projectIdOpt.get();
        Optional<XForm> formOpt = xFormRepository.findByXmlnsAndProjectId(xmlFormId, projectId);
        if (formOpt.isEmpty() || !formOpt.get().getIsPublished() || !formOpt.get().getIsActive()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        XForm form = formOpt.get();
        Optional<FormVersion> latestVersion = formVersionRepository.findLatestByFormId(form.getId());
        
        if (latestVersion.isEmpty() || latestVersion.get().getXmlContent() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        FormVersion version = latestVersion.get();
        auditService.log(
                AuditActions.OPENROSA_FORM_DOWNLOADED,
                "FORM",
                form.getId(),
                form.getTitle(),
                java.util.Map.of(
                        "projectId", projectId.toString(),
                        "xmlFormId", xmlFormId,
                        "formVersionId", version.getId().toString(),
                        "version", version.getVersion(),
                        "compatibilityRoute", true));

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(OPENROSA_XML_MEDIA_TYPE))
                .body(version.getXmlContent());
    }

    /**
     * GET /forms/{xmlFormId}/manifest - Compatibility alias
     * Uses the authenticated app user's project context
     */
    @GetMapping(value = "/forms/{xmlFormId}/manifest", produces = OPENROSA_XML_MEDIA_TYPE)
    public ResponseEntity<String> compatibilityManifest(
            @PathVariable("xmlFormId") String xmlFormId,
            HttpServletRequest request,
            HttpServletResponse response) {

        setOpenRosaHeaders(response);

        Optional<UUID> projectIdOpt = getAppUserProjectId();
        if (projectIdOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(buildErrorResponse("No project context available for this user"));
        }

        UUID projectId = projectIdOpt.get();
        Optional<XForm> formOpt = xFormRepository.findByXmlnsAndProjectId(xmlFormId, projectId);
        if (formOpt.isEmpty() || !formOpt.get().getIsPublished() || !formOpt.get().getIsActive()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        XForm form = formOpt.get();
        Optional<FormVersion> latestVersion = formVersionRepository.findLatestByFormId(form.getId());
        
        if (latestVersion.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        FormVersion fv = latestVersion.get();
        
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<manifest xmlns=\"http://openrosa.org/xforms/xformsManifest\">\n");
        xml.append("  <mediaFile>\n");
        xml.append("    <filename>").append(escapeXml(fv.getXmlns())).append(".xml</filename>\n");
        xml.append("    <hash>md5:").append(computeMd5(fv.getXmlContent())).append("</hash>\n");
        xml.append("  </mediaFile>\n");
        xml.append("</manifest>");

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(OPENROSA_XML_MEDIA_TYPE))
                .body(xml.toString());
    }

    /**
     * HEAD /submission - Compatibility alias
     * Uses the authenticated app user's project context
     */
    @RequestMapping(value = "/submission", method = RequestMethod.HEAD)
    public ResponseEntity<Void> compatibilitySubmissionHead(
            HttpServletRequest request,
            HttpServletResponse response) {

        setOpenRosaHeaders(response);
        response.setHeader("X-OpenRosa-Accept-Content-Length", String.valueOf(MAX_CONTENT_LENGTH));

        Optional<UUID> projectIdOpt = getAppUserProjectId();
        if (projectIdOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        UUID projectId = projectIdOpt.get();
        Optional<Project> projectOpt = projectRepository.findById(projectId);
        if (projectOpt.isEmpty() || !projectOpt.get().getIsActive()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        return ResponseEntity.noContent().build();
    }

    /**
     * POST /submission - Compatibility alias
     * Uses the authenticated app user's project context
     */
    @PostMapping(value = "/submission", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> compatibilitySubmissionPost(
            @RequestPart(value = "xml_submission_file", required = false) MultipartFile xmlFile,
            @RequestPart(value = "xml_submission_file", required = false) MultipartFile xmlFileAlt,
            @RequestParam MultiValueMap<String, MultipartFile> multipartFiles,
            HttpServletRequest request,
            HttpServletResponse response) {

        setOpenRosaHeaders(response);

        Optional<UUID> projectIdOpt = getAppUserProjectId();
        if (projectIdOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(buildErrorResponse("No project context available for this user"));
        }

        MultipartFile submissionFile = xmlFile != null ? xmlFile : xmlFileAlt;
        try {
            OpenRosaSubmissionService.Receipt receipt = openRosaSubmissionService.receive(
                    projectIdOpt.get(), submissionFile, multipartFiles, request);

            StringBuilder xml = new StringBuilder();
            xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            xml.append("<OpenRosaResponse xmlns=\"http://openrosa.org/http/response\">\n");
            xml.append("  <message>Successfully submitted.</message>\n");
            xml.append("  <status>201</status>\n");
            xml.append("  <instanceID>").append(escapeXml(receipt.instanceId())).append("</instanceID>\n");
            xml.append("</OpenRosaResponse>");

            return ResponseEntity.status(HttpStatus.CREATED)
                    .contentType(MediaType.parseMediaType(OPENROSA_XML_MEDIA_TYPE))
                    .body(xml.toString());
        } catch (OpenRosaSubmissionService.IntakeException e) {
            return ResponseEntity.status(e.getStatus())
                    .contentType(MediaType.parseMediaType(OPENROSA_XML_MEDIA_TYPE))
                    .body(buildErrorResponse(e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.parseMediaType(OPENROSA_XML_MEDIA_TYPE))
                    .body(buildErrorResponse("Failed to process submission"));
        }
    }

    // ==================== Helper Methods ====================

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
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
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
}
