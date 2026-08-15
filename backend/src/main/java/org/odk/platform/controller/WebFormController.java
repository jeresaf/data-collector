package org.odk.platform.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.odk.platform.audit.AuditActions;
import org.odk.platform.audit.AuditService;
import org.odk.platform.domain.FormVersion;
import org.odk.platform.domain.WebFormAnswer;
import org.odk.platform.domain.WebFormAttachment;
import org.odk.platform.domain.WebFormSession;
import org.odk.platform.service.FormDefinition;
import org.odk.platform.service.WebFormAccessService;
import org.odk.platform.service.WebFormRuntimeService;
import org.javarosa.core.model.FormIndex;
import org.javarosa.form.api.FormEntryController;
import org.javarosa.form.api.FormEntryPrompt;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/web")
@RequiredArgsConstructor
@Slf4j
public class WebFormController {

    private final WebFormRuntimeService webFormRuntimeService;
    private final WebFormAccessService webFormAccessService;
    private final AuditService auditService;

    /**
     * Start a new web form session or resume an existing draft.
     */
    @PostMapping("/forms/{formVersionId}/sessions")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<StartSessionResponse> startSession(
            @PathVariable UUID formVersionId,
            @RequestBody(required = false) StartSessionRequest request) {

        UUID requestedUserId = request != null ? request.getUserId() : null;
        UUID requestedAppUserId = request != null ? request.getAppUserId() : null;

        WebFormAccessService.SessionIdentity identity =
                webFormAccessService.authorizeStart(
                        formVersionId,
                        requestedUserId,
                        requestedAppUserId);

        WebFormSession session = webFormRuntimeService.startSession(
                formVersionId,
                identity.userId(),
                identity.appUserId());

        // Get form definition with session data to return initial state
        FormDefinition formDef = webFormRuntimeService.getFormDefinitionWithInstance(session.getId());
        
        // Step to the first renderable screen and persist that position.
        int event = formDef.stepToNextEvent();
        webFormRuntimeService.persistFormState(session.getId(), formDef);
        
        // Update session status to active if it was draft
        if ("draft".equals(session.getStatus())) {
            session = webFormRuntimeService.updateSessionStatus(session.getId(), "active");
        }
        
        StartSessionResponse response = StartSessionResponse.builder()
                .sessionId(session.getId())
                .formVersionId(formVersionId)
                .status(session.getStatus())
                .currentEvent(event)
                .isAtBeginning(formDef.isAtBeginningOfForm())
                .isAtEnd(formDef.isAtEndOfForm())
                .build();

        // If on a question, add question details
        if (event == FormEntryController.EVENT_QUESTION) {
            response.setQuestion(buildQuestionResponse(formDef));
        } else if (event == FormEntryController.EVENT_GROUP || event == FormEntryController.EVENT_REPEAT) {
            response.setGroup(buildGroupResponse(formDef));
        } else if (event == FormEntryController.EVENT_REPEAT_JUNCTURE
                || event == FormEntryController.EVENT_PROMPT_NEW_REPEAT) {
            response.setRepeatJuncture(buildRepeatJunctureResponse(formDef));
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Get session state.
     */
    @GetMapping("/sessions/{sessionId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<SessionStateResponse> getSession(@PathVariable UUID sessionId) {
        webFormAccessService.authorizeSession(sessionId);
        WebFormSession session = webFormRuntimeService.getSession(sessionId);
        FormDefinition formDef = webFormRuntimeService.getFormDefinitionWithInstance(sessionId);

        SessionStateResponse response = SessionStateResponse.builder()
                .sessionId(session.getId())
                .formVersionId(session.getFormVersion().getId())
                .status(session.getStatus())
                .currentScreen(session.getCurrentScreen())
                .startedAt(session.getStartedAt())
                .lastActivityAt(session.getLastActivityAt())
                .completedAt(session.getCompletedAt())
                .build();

        return ResponseEntity.ok(response);
    }

    /**
     * Get current question/screen state for rendering.
     */
    @GetMapping("/sessions/{sessionId}/current")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CurrentScreenResponse> getCurrentScreen(@PathVariable UUID sessionId) {
        webFormAccessService.authorizeSession(sessionId);
        FormDefinition formDef = webFormRuntimeService.getFormDefinitionWithSessionState(sessionId);
        
        int event = formDef.getCurrentEvent();
        
        CurrentScreenResponse response = CurrentScreenResponse.builder()
                .event(event)
                .isAtBeginning(formDef.isAtBeginningOfForm())
                .isAtEnd(formDef.isAtEndOfForm())
                .build();

        if (event == FormEntryController.EVENT_QUESTION) {
            response.setQuestion(buildQuestionResponse(formDef));
        } else if (event == FormEntryController.EVENT_GROUP || event == FormEntryController.EVENT_REPEAT) {
            response.setGroup(buildGroupResponse(formDef));
        } else if (event == FormEntryController.EVENT_REPEAT_JUNCTURE
                || event == FormEntryController.EVENT_PROMPT_NEW_REPEAT) {
            response.setRepeatJuncture(buildRepeatJunctureResponse(formDef));
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Save an answer for the current question.
     */
    @PostMapping("/sessions/{sessionId}/answers")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AnswerResponse> saveAnswer(
            @PathVariable UUID sessionId,
            @RequestBody SaveAnswerRequest request) {

        webFormAccessService.authorizeSession(sessionId);

        WebFormAnswer answer = webFormRuntimeService.saveAnswer(
                sessionId,
                request.getQuestionPath(),
                request.getInstancePath(),
                request.getValue(),
                request.getRepeatPath(),
                request.getRepeatIndex()
        );

        // Restore the persisted JavaRosa state after the answer.
        FormDefinition formDef = webFormRuntimeService.getFormDefinitionWithSessionState(sessionId);
        int event = formDef.getCurrentEvent();

        AnswerResponse response = AnswerResponse.builder()
                .answerId(answer.getId())
                .questionPath(answer.getQuestionPath())
                .instancePath(answer.getInstancePath())
                .questionName(answer.getQuestionName())
                .value(getAnswerValue(answer))
                .isRelevant(answer.getIsRelevant())
                .isRequired(answer.getIsRequired())
                .validationStatus(answer.getValidationStatus())
                .constraintMessage(answer.getConstraintMessage())
                .currentEvent(event)
                .isAtEnd(formDef.isAtEndOfForm())
                .build();

        // If next is a question, include it
        if (event == FormEntryController.EVENT_QUESTION) {
            response.setNextQuestion(buildQuestionResponse(formDef));
        } else if (event == FormEntryController.EVENT_GROUP || event == FormEntryController.EVENT_REPEAT) {
            response.setNextGroup(buildGroupResponse(formDef));
        } else if (event == FormEntryController.EVENT_REPEAT_JUNCTURE
                || event == FormEntryController.EVENT_PROMPT_NEW_REPEAT) {
            response.setNextRepeatJuncture(buildRepeatJunctureResponse(formDef));
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Navigate to next question/screen.
     */
    @PostMapping("/sessions/{sessionId}/navigate/next")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<NavigationResponse> navigateNext(@PathVariable UUID sessionId) {
        webFormAccessService.authorizeSession(sessionId);
        FormDefinition formDef = webFormRuntimeService.getFormDefinitionWithSessionState(sessionId);
        int event = formDef.stepToNextEvent();
        webFormRuntimeService.persistFormState(sessionId, formDef);

        return buildNavigationResponse(formDef, event);
    }

    /**
     * Navigate to previous question/screen.
     */
    @PostMapping("/sessions/{sessionId}/navigate/previous")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<NavigationResponse> navigatePrevious(@PathVariable UUID sessionId) {
        webFormAccessService.authorizeSession(sessionId);
        FormDefinition formDef = webFormRuntimeService.getFormDefinitionWithSessionState(sessionId);
        int event = formDef.stepToPreviousEvent();
        webFormRuntimeService.persistFormState(sessionId, formDef);

        return buildNavigationResponse(formDef, event);
    }

    /**
     * Navigate to a specific question by index.
     */
    @PostMapping("/sessions/{sessionId}/navigate/index")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<NavigationResponse> navigateToIndex(
            @PathVariable UUID sessionId,
            @RequestBody NavigateToIndexRequest request) {
        webFormAccessService.authorizeSession(sessionId);
        FormDefinition formDef = webFormRuntimeService.getFormDefinitionWithSessionState(sessionId);
        // Note: JavaRosa FormEntryController doesn't have direct jump to index
        // This would require additional implementation
        int event = formDef.getCurrentEvent();

        return buildNavigationResponse(formDef, event);
    }

    /**
     * Handle repeat operations.
     */
    @PostMapping("/sessions/{sessionId}/repeats/{repeatIndex}/descend")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<NavigationResponse> descendIntoRepeat(
            @PathVariable UUID sessionId,
            @PathVariable int repeatIndex) {
        webFormAccessService.authorizeSession(sessionId);
        FormDefinition formDef = webFormRuntimeService.getFormDefinitionWithSessionState(sessionId);
        formDef.descendIntoRepeat(repeatIndex);
        int event = formDef.getCurrentEvent();
        webFormRuntimeService.persistFormState(sessionId, formDef);

        NavigationResponse response = buildNavigationResponse(formDef, event).getBody();
        response.setSuccess(true);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/sessions/{sessionId}/repeats/new")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<NavigationResponse> createNewRepeat(@PathVariable UUID sessionId) {
        webFormAccessService.authorizeSession(sessionId);
        FormDefinition formDef = webFormRuntimeService.getFormDefinitionWithSessionState(sessionId);
        formDef.createNewRepeat();
        int event = formDef.getCurrentEvent();

        // Repeat structure is part of the JavaRosa instance tree.
        webFormRuntimeService.persistFormState(sessionId, formDef);

        NavigationResponse response = buildNavigationResponse(formDef, event).getBody();
        response.setSuccess(true);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/sessions/{sessionId}/repeats/delete")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<NavigationResponse> deleteRepeat(@PathVariable UUID sessionId) {
        webFormAccessService.authorizeSession(sessionId);
        FormDefinition formDef = webFormRuntimeService.getFormDefinitionWithSessionState(sessionId);
        formDef.deleteRepeat();
        int event = formDef.getCurrentEvent();
        webFormRuntimeService.persistFormState(sessionId, formDef);

        NavigationResponse response = buildNavigationResponse(formDef, event).getBody();
        response.setSuccess(true);
        return ResponseEntity.ok(response);
    }

    /**
     * Finalize session and create submission.
     */
    @PostMapping("/sessions/{sessionId}/finalize")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<FinalizeResponse> finalizeSession(@PathVariable UUID sessionId) {
        webFormAccessService.authorizeSession(sessionId);
        try {
            var submission = webFormRuntimeService.finalizeSession(sessionId);

            Map<String, Object> auditDetails = new java.util.LinkedHashMap<>();
            auditDetails.put("source", "WEB_FORM");
            auditDetails.put("sessionId", sessionId.toString());
            if (submission.getForm() != null) {
                auditDetails.put("formId", submission.getForm().getId().toString());
            }
            if (submission.getFormVersion() != null) {
                auditDetails.put("formVersionId", submission.getFormVersion().getId().toString());
            }
            auditService.log(
                    AuditActions.SUBMISSION_RECEIVED,
                    "SUBMISSION",
                    submission.getId(),
                    submission.getInstanceId(),
                    auditDetails);
            
            FinalizeResponse response = FinalizeResponse.builder()
                    .success(true)
                    .submissionId(submission.getId())
                    .instanceId(submission.getInstanceId())
                    .message("Form submitted successfully")
                    .build();
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            auditService.logError(
                    AuditActions.SUBMISSION_RECEIVED,
                    "WEB_FORM_SESSION",
                    sessionId,
                    sessionId.toString(),
                    Map.of("source", "WEB_FORM"),
                    e.getMessage() == null ? "Web form finalization failed" : e.getMessage());
            FinalizeResponse response = FinalizeResponse.builder()
                    .success(false)
                    .message("Failed to finalize: " + e.getMessage())
                    .build();
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * Save draft (update session status to draft).
     */
    @PostMapping("/sessions/{sessionId}/draft")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<SessionStateResponse> saveDraft(@PathVariable UUID sessionId) {
        webFormAccessService.authorizeSession(sessionId);
        WebFormSession session = webFormRuntimeService.updateSessionStatus(sessionId, "draft");
        
        SessionStateResponse response = SessionStateResponse.builder()
                .sessionId(session.getId())
                .formVersionId(session.getFormVersion().getId())
                .status(session.getStatus())
                .currentScreen(session.getCurrentScreen())
                .startedAt(session.getStartedAt())
                .lastActivityAt(session.getLastActivityAt())
                .completedAt(session.getCompletedAt())
                .build();

        return ResponseEntity.ok(response);
    }

    /**
     * Upload attachment for a session.
     */
    @PostMapping("/sessions/{sessionId}/attachments")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AttachmentResponse> uploadAttachment(
            @PathVariable UUID sessionId,
            @RequestBody UploadAttachmentRequest request) {

        webFormAccessService.authorizeSession(sessionId);

        try {
            WebFormAttachment attachment = webFormRuntimeService.saveAttachment(
                    sessionId,
                    request.getAnswerId(),
                    request.getQuestionPath(),
                    request.getFileName(),
                    request.getContentType(),
                    request.getFileSize(),
                    request.getContent()
            );

            AttachmentResponse response = AttachmentResponse.builder()
                    .attachmentId(attachment.getId())
                    .fileName(attachment.getFileName())
                    .contentType(attachment.getContentType())
                    .fileSize(attachment.getFileSize())
                    .storagePath(attachment.getStoragePath())
                    .contentHash(attachment.getContentHash())
                    .build();

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("Rejected web-form attachment upload: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (IOException e) {
            log.error("Failed to upload attachment", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Delete session and all associated data.
     */
    @DeleteMapping("/sessions/{sessionId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> deleteSession(@PathVariable UUID sessionId) {
        webFormAccessService.authorizeSession(sessionId);
        webFormRuntimeService.deleteSession(sessionId);
        return ResponseEntity.ok().build();
    }

    /**
     * Validate entire form.
     */
    @PostMapping("/sessions/{sessionId}/validate")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ValidationResponse> validateForm(@PathVariable UUID sessionId) {
        webFormAccessService.authorizeSession(sessionId);
        FormDefinition formDef = webFormRuntimeService.getFormDefinitionWithInstance(sessionId);
        FormDefinition.ValidationResult result = formDef.validate();

        ValidationResponse response = ValidationResponse.builder()
                .valid(result.isValid())
                .failedPromptIndex(result.getFailedPrompt() != null ? result.getFailedPrompt().toString() : null)
                .outcomeCode(result.getOutcomeCode())
                .build();

        return ResponseEntity.ok(response);
    }

    /**
     * Get form definition metadata (title, choices, etc.) for rendering.
     */
    @GetMapping("/forms/{formVersionId}/definition")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<FormDefinitionResponse> getFormDefinition(@PathVariable UUID formVersionId) {
        webFormAccessService.authorizeDefinition(formVersionId);
        FormDefinition formDef = webFormRuntimeService.getFormDefinitionForVersion(formVersionId);

        FormDefinitionResponse response = FormDefinitionResponse.builder()
                .formVersionId(formVersionId)
                .title(formDef.getFormTitle())
                .xmlFormId(formDef.getXmlFormId())
                .availableLocales(formDef.getAvailableLocales())
                .compatibilityWarnings(formDef.getCompatibilityWarnings())
                .build();

        return ResponseEntity.ok(response);
    }

    // Helper methods

    private ResponseEntity<NavigationResponse> buildNavigationResponse(FormDefinition formDef, int event) {
        NavigationResponse response = NavigationResponse.builder()
                .event(event)
                .isAtBeginning(formDef.isAtBeginningOfForm())
                .isAtEnd(formDef.isAtEndOfForm())
                .success(true)
                .build();

        if (event == FormEntryController.EVENT_QUESTION) {
            response.setQuestion(buildQuestionResponse(formDef));
        } else if (event == FormEntryController.EVENT_GROUP || event == FormEntryController.EVENT_REPEAT) {
            response.setGroup(buildGroupResponse(formDef));
        } else if (event == FormEntryController.EVENT_REPEAT_JUNCTURE
                || event == FormEntryController.EVENT_PROMPT_NEW_REPEAT) {
            response.setRepeatJuncture(buildRepeatJunctureResponse(formDef));
        }

        return ResponseEntity.ok(response);
    }

    private QuestionResponse buildQuestionResponse(FormDefinition formDef) {
        FormEntryPrompt prompt = formDef.getCurrentPrompt();
        String questionName = null;
        if (prompt != null) {
            FormIndex index = prompt.getIndex();
            if (index != null) {
                org.javarosa.core.model.instance.TreeElement element = formDef.getFormDef().getMainInstance().resolveReference(index.getReference());
                if (element != null) {
                    questionName = element.getName();
                }
            }
        }
        
        // Use the question number from FormDefinition (1-based index among all questions)
        int questionIndex = formDef.getCurrentQuestionNumber();
        
        return QuestionResponse.builder()
                .index(questionIndex)
                .name(questionName)
                .path(formDef.getCurrentQuestionPath())
                .instancePath(formDef.getCurrentQuestionInstancePath())
                .groupHierarchy(buildGroupHierarchy(formDef))
                .repeatPath(formDef.getCurrentRepeatPath())
                .repeatIndex(formDef.getCurrentRepeatIndex())
                .label(formDef.getCurrentLabel())
                .hint(formDef.getCurrentHint())
                .dataType(formDef.getCurrentDataType())
                .controlType(formDef.getCurrentControlType())
                .required(formDef.isCurrentRequired())
                .readOnly(formDef.isCurrentReadOnly())
                .constraintText(formDef.getCurrentConstraintText())
                .appearanceHint(formDef.getCurrentAppearanceHint())
                .mediaType(formDef.getCurrentMediaType())
                .currentAnswer(formDef.getCurrentAnswerText())
                .choices(formDef.getCurrentChoices())
                .build();
    }

    private GroupResponse buildGroupResponse(FormDefinition formDef) {
        List<GroupContextResponse> hierarchy = buildGroupHierarchy(formDef);
        GroupContextResponse current = hierarchy.isEmpty() ? null : hierarchy.get(hierarchy.size() - 1);

        return GroupResponse.builder()
                .label(formDef.getCurrentCaptionText())
                .path(current != null ? current.getPath() : null)
                .instancePath(current != null ? current.getInstancePath() : null)
                .depth(current != null ? current.getDepth() : 0)
                .hierarchy(hierarchy)
                .isRepeat(formDef.isCurrentRepeat())
                .repeatCount(formDef.getCurrentRepeatCount())
                .currentRepeatIndex(formDef.getCurrentRepeatIndex())
                .repeatTargetCount(formDef.getCurrentRepeatTargetCount())
                .repeatCountControlled(formDef.isCurrentRepeatCountControlled())
                .canAddRepeat(formDef.canAddCurrentRepeat())
                .canDeleteRepeat(formDef.canDeleteCurrentRepeat())
                .build();
    }

    private RepeatJunctureResponse buildRepeatJunctureResponse(FormDefinition formDef) {
        List<GroupContextResponse> hierarchy = buildGroupHierarchy(formDef);
        GroupContextResponse current = hierarchy.isEmpty() ? null : hierarchy.get(hierarchy.size() - 1);

        return RepeatJunctureResponse.builder()
                .label(formDef.getCurrentCaptionText())
                .path(current != null ? current.getPath() : null)
                .instancePath(current != null ? current.getInstancePath() : null)
                .depth(current != null ? current.getDepth() : 0)
                .hierarchy(hierarchy)
                .repeatCount(formDef.getCurrentRepeatCount())
                .currentRepeatIndex(formDef.getCurrentRepeatIndex())
                .repeatTargetCount(formDef.getCurrentRepeatTargetCount())
                .repeatCountControlled(formDef.isCurrentRepeatCountControlled())
                .canAddRepeat(formDef.canAddCurrentRepeat())
                .canDeleteRepeat(formDef.canDeleteCurrentRepeat())
                .build();
    }

    private List<GroupContextResponse> buildGroupHierarchy(FormDefinition formDef) {
        return formDef.getCurrentGroupHierarchy().stream()
                .map(context -> GroupContextResponse.builder()
                        .label(context.label())
                        .path(context.path())
                        .instancePath(context.instancePath())
                        .repeat(context.repeat())
                        .repeatIndex(context.repeatIndex())
                        .depth(context.depth())
                        .build())
                .toList();
    }

    private Object getAnswerValue(org.odk.platform.domain.WebFormAnswer answer) {
        if (answer.getValueText() != null) return answer.getValueText();
        if (answer.getValueNumber() != null) return answer.getValueNumber();
        if (answer.getValueDate() != null) return answer.getValueDate();
        if (answer.getValueBoolean() != null) return answer.getValueBoolean();
        if (answer.getValueJson() != null) return answer.getValueJson();
        return null;
    }

    // DTOs

    /**
     * Map invalid IDs/state supplied by the client to HTTP 400 instead of
     * allowing IllegalArgumentException to escape as an internal server error.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDeniedException(
            AccessDeniedException exception) {

        String message = exception.getMessage() != null
                ? exception.getMessage()
                : "Access denied";

        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", message));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgumentException(
            IllegalArgumentException exception) {

        String message = exception.getMessage() != null
                ? exception.getMessage()
                : "Invalid request";

        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class StartSessionRequest {
        private UUID userId;
        private UUID appUserId;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class StartSessionResponse {
        private UUID sessionId;
        private UUID formVersionId;
        private String status;
        private int currentEvent;
        private boolean isAtBeginning;
        private boolean isAtEnd;
        private QuestionResponse question;
        private GroupResponse group;
        private RepeatJunctureResponse repeatJuncture;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SessionStateResponse {
        private UUID sessionId;
        private UUID formVersionId;
        private String status;
        private String currentScreen;
        private java.time.OffsetDateTime startedAt;
        private java.time.OffsetDateTime lastActivityAt;
        private java.time.OffsetDateTime completedAt;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class CurrentScreenResponse {
        private int event;

        @com.fasterxml.jackson.annotation.JsonProperty("isAtBeginning")
        private boolean isAtBeginning;

        @com.fasterxml.jackson.annotation.JsonProperty("isAtEnd")
        private boolean isAtEnd;

        private QuestionResponse question;
        private GroupResponse group;
        private RepeatJunctureResponse repeatJuncture;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SaveAnswerRequest {
        private String questionPath;
        private String instancePath;
        private Object value;
        private String repeatPath;
        private Integer repeatIndex;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class AnswerResponse {
        private UUID answerId;
        private String questionPath;
        private String instancePath;
        private String questionName;
        private Object value;
        private boolean isRelevant;
        private boolean isRequired;
        private String validationStatus;
        private String constraintMessage;
        private int currentEvent;
        private boolean isAtEnd;
        private QuestionResponse nextQuestion;
        private GroupResponse nextGroup;
        private RepeatJunctureResponse nextRepeatJuncture;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class NavigationResponse {
        private int event;
        private boolean isAtBeginning;
        private boolean isAtEnd;
        private boolean success;
        private QuestionResponse question;
        private GroupResponse group;
        private RepeatJunctureResponse repeatJuncture;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class NavigateToIndexRequest {
        private int index;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class FinalizeResponse {
        private boolean success;
        private UUID submissionId;
        private String instanceId;
        private String message;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class UploadAttachmentRequest {
        private UUID answerId;
        private String questionPath;
        private String fileName;
        private String contentType;
        private long fileSize;
        private byte[] content;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class AttachmentResponse {
        private UUID attachmentId;
        private String fileName;
        private String contentType;
        private long fileSize;
        private String storagePath;
        private String contentHash;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ValidationResponse {
        private boolean valid;
        private String failedPromptIndex;
        private int outcomeCode;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class FormDefinitionResponse {
        private UUID formVersionId;
        private String title;
        private String xmlFormId;
        private String[] availableLocales;
        private List<String> compatibilityWarnings;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class QuestionResponse {
        private int index;
        private String name;
        private String path;
        private String instancePath;
        private List<GroupContextResponse> groupHierarchy;
        private String repeatPath;
        private Integer repeatIndex;
        private String label;
        private String hint;
        private int dataType;
        private int controlType;
        private boolean required;
        private boolean readOnly;
        private String constraintText;
        private String appearanceHint;
        private String mediaType;
        private String currentAnswer;
        private List<FormDefinition.SelectChoice> choices;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class GroupResponse {
        private String label;
        private String path;
        private String instancePath;
        private int depth;
        private List<GroupContextResponse> hierarchy;
        private boolean isRepeat;
        private int repeatCount;
        private Integer currentRepeatIndex;
        private Integer repeatTargetCount;
        private boolean repeatCountControlled;
        private boolean canAddRepeat;
        private boolean canDeleteRepeat;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class GroupContextResponse {
        private String label;
        private String path;
        private String instancePath;
        private boolean repeat;
        private Integer repeatIndex;
        private int depth;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class RepeatJunctureResponse {
        private String label;
        private String path;
        private String instancePath;
        private int depth;
        private List<GroupContextResponse> hierarchy;
        private int repeatCount;
        private Integer currentRepeatIndex;
        private Integer repeatTargetCount;
        private boolean repeatCountControlled;
        private boolean canAddRepeat;
        private boolean canDeleteRepeat;
    }
}