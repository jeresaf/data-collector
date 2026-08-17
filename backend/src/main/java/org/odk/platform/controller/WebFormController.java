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
import org.odk.platform.service.FormAttachmentService;
import org.odk.platform.service.WebFormAccessService;
import org.odk.platform.service.WebFormRuntimeService;
import org.javarosa.core.model.Constants;
import org.javarosa.core.model.FormIndex;
import org.javarosa.form.api.FormEntryController;
import org.javarosa.form.api.FormEntryPrompt;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
    private final FormAttachmentService formAttachmentService;
    private final AuditService auditService;

    /**
     * Serve prompt media referenced by the XForm (audio/image/video) through
     * the authenticated browser API. The browser cannot attach the platform's
     * bearer token to a raw <audio>/<img>/<video> URL, so the Vue client fetches
     * this endpoint as a blob through the configured Axios instance.
     */
    @GetMapping("/forms/{formVersionId}/media/{fileName:.+}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Resource> getPromptMedia(
            @PathVariable UUID formVersionId,
            @PathVariable String fileName) {
        webFormAccessService.authorizeDefinition(formVersionId);

        var attachment = formAttachmentService
                .getAttachmentByFormVersionAndFileName(formVersionId, fileName)
                .orElse(null);
        if (attachment == null) {
            return ResponseEntity.notFound().build();
        }

        byte[] content = formAttachmentService.downloadAttachment(attachment.getId());
        String contentType = formAttachmentService.getAttachmentContentType(attachment.getId());
        if (contentType == null || contentType.isBlank()) {
            contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .contentLength(content.length)
                .body(new ByteArrayResource(content));
    }

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

        // A new session starts before the first JavaRosa event. An existing
        // draft already has a persisted JavaRosa position and must resume there
        // rather than being reset to the beginning every time Fill Form opens.
        boolean isNewSessionPosition = session.getCurrentScreen() == null
                || session.getCurrentScreen().isBlank()
                || "start".equals(session.getCurrentScreen());

        FormDefinition formDef = isNewSessionPosition
                ? webFormRuntimeService.getFormDefinitionWithInstance(session.getId())
                : webFormRuntimeService.getFormDefinitionWithSessionState(session.getId());

        int event = isNewSessionPosition
                ? formDef.stepToNextScreenEvent()
                : formDef.normalizeCurrentScreen(true);
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

        int originalEvent = formDef.getCurrentEvent();
        int event = originalEvent == FormEntryController.EVENT_BEGINNING_OF_FORM
                ? formDef.stepToNextScreenEvent()
                : formDef.normalizeCurrentScreen(true);
        if (event != originalEvent) {
            webFormRuntimeService.persistFormState(sessionId, formDef);
        }
        
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
     * Get answer-based progress across the currently reachable form.
     */
    @GetMapping("/sessions/{sessionId}/progress")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ProgressResponse> getProgress(@PathVariable UUID sessionId) {
        webFormAccessService.authorizeSession(sessionId);
        WebFormRuntimeService.FormProgress progress = webFormRuntimeService.calculateProgress(sessionId);
        return ResponseEntity.ok(ProgressResponse.builder()
                .answeredQuestions(progress.answeredQuestions())
                .totalQuestions(progress.totalQuestions())
                .percent(progress.percent())
                .build());
    }

    /**
     * Return all currently reachable questions contained in an
     * appearance=field-list group. The group is enumerated from a fresh
     * JavaRosa runtime so building the page never changes the persisted session
     * position and every question keeps JavaRosa's exact instance path.
     */
    @GetMapping("/sessions/{sessionId}/field-list")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<FieldListResponse> getFieldList(@PathVariable UUID sessionId) {
        webFormAccessService.authorizeSession(sessionId);
        FormDefinition groupForm = webFormRuntimeService.getFormDefinitionWithSessionState(sessionId);

        if (!groupForm.isCurrentFieldListGroup()) {
            return ResponseEntity.ok(FieldListResponse.builder()
                    .groupPath(null)
                    .groupInstancePath(null)
                    .questions(List.of())
                    .build());
        }

        String groupPath = groupForm.getCurrentFieldListPath();
        String groupInstancePath = groupForm.getCurrentFieldListInstancePath();
        List<QuestionResponse> questions = collectFieldListQuestions(sessionId, groupInstancePath);

        return ResponseEntity.ok(FieldListResponse.builder()
                .groupPath(groupPath)
                .groupInstancePath(groupInstancePath)
                .questions(questions)
                .build());
    }

    /**
     * Collect field-list questions by walking the live JavaRosa event stream.
     * This deliberately avoids reconstructing instance paths from bind paths:
     * JavaRosa instance references contain multiplicity predicates (including
     * [1] on ordinary descendants), and predicting those paths was the reason
     * valid field-list groups could render as empty.
     */
    private List<QuestionResponse> collectFieldListQuestions(
            UUID sessionId,
            String groupInstancePath) {

        if (groupInstancePath == null || groupInstancePath.isBlank()) {
            return List.of();
        }

        FormDefinition scan = webFormRuntimeService.getFormDefinitionWithInstance(sessionId);
        List<QuestionResponse> questions = new java.util.ArrayList<>();
        boolean enteredGroup = false;
        int event = scan.getCurrentEvent();
        int safetyCounter = 0;

        while (safetyCounter++ < 10000) {
            boolean insideGroup = scan.isCurrentEventWithinGroup(groupInstancePath);

            if (insideGroup) {
                enteredGroup = true;

                if (event == FormEntryController.EVENT_QUESTION
                        && scan.isIndexRelevant(scan.getCurrentFormIndex())
                        && !isInsideNestedRepeat(scan, groupInstancePath)) {
                    questions.add(buildQuestionResponse(scan));
                }
            } else if (enteredGroup) {
                break;
            }

            if (event == FormEntryController.EVENT_END_OF_FORM) {
                break;
            }
            event = scan.stepToNextEvent();
        }

        if (safetyCounter >= 10000) {
            throw new IllegalStateException(
                    "Unable to enumerate field-list group: " + groupInstancePath);
        }

        return questions;
    }

    private boolean isInsideNestedRepeat(
            FormDefinition formDef,
            String fieldListInstancePath) {
        for (FormDefinition.GroupContext context : formDef.getCurrentGroupHierarchy()) {
            if (!context.repeat() || context.instancePath() == null) {
                continue;
            }
            if (context.instancePath().startsWith(fieldListInstancePath + "/")) {
                return true;
            }
        }
        return false;
    }

    @PostMapping("/sessions/{sessionId}/field-list/navigate/next")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<NavigationResponse> navigatePastFieldList(
            @PathVariable UUID sessionId,
            @RequestBody FieldListNavigateRequest request) {
        webFormAccessService.authorizeSession(sessionId);
        FormDefinition formDef = webFormRuntimeService.getFormDefinitionWithSessionState(sessionId);
        int event = stepOutsideGroup(formDef, request.getGroupInstancePath(), true);
        webFormRuntimeService.persistFormState(sessionId, formDef);
        return buildNavigationResponse(formDef, event);
    }

    @PostMapping("/sessions/{sessionId}/field-list/navigate/previous")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<NavigationResponse> navigateBeforeFieldList(
            @PathVariable UUID sessionId,
            @RequestBody FieldListNavigateRequest request) {
        webFormAccessService.authorizeSession(sessionId);
        FormDefinition formDef = webFormRuntimeService.getFormDefinitionWithSessionState(sessionId);
        int event = stepOutsideGroup(formDef, request.getGroupInstancePath(), false);
        webFormRuntimeService.persistFormState(sessionId, formDef);
        return buildNavigationResponse(formDef, event);
    }

    private int stepOutsideGroup(FormDefinition formDef, String groupInstancePath, boolean forward) {
        if (groupInstancePath == null || groupInstancePath.isBlank()) {
            throw new IllegalArgumentException("Field-list group path is required");
        }

        int event = formDef.getCurrentEvent();
        int safetyCounter = 0;

        // The session may currently be on the group event itself or on any child
        // question because answer saves intentionally persist their exact
        // JavaRosa position. Walk until the next event is outside this group.
        while (safetyCounter++ < 10000) {
            if (forward && event == FormEntryController.EVENT_END_OF_FORM) {
                return event;
            }
            if (!forward && event == FormEntryController.EVENT_BEGINNING_OF_FORM) {
                return event;
            }

            event = forward ? formDef.stepToNextEvent() : formDef.stepToPreviousEvent();
            if (!formDef.isCurrentEventWithinGroup(groupInstancePath)) {
                return formDef.normalizeCurrentScreen(forward);
            }
        }

        throw new IllegalStateException("Unable to leave field-list group: " + groupInstancePath);
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
        int event = formDef.stepToNextScreenEvent();
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
        int event = formDef.stepToPreviousScreenEvent();
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

        ValidationResponse validation = validateSession(sessionId);
        if (!validation.isValid()) {
            FinalizeResponse response = FinalizeResponse.builder()
                    .success(false)
                    .message(validation.getMessage())
                    .validation(validation)
                    .build();
            return ResponseEntity.badRequest().body(response);
        }

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
        return ResponseEntity.ok(validateSession(sessionId));
    }

    private ValidationResponse validateSession(UUID sessionId) {
        FormDefinition formDef = webFormRuntimeService.getFormDefinitionWithInstance(sessionId);
        FormDefinition.ValidationResult result = formDef.validate();

        QuestionResponse failedQuestion = null;
        String message = null;

        if (!result.isValid() && result.getFailedPrompt() != null) {
            String failedPath = formDef.getApiPath(result.getFailedPrompt());
            String failedInstancePath = formDef.getApiInstancePath(result.getFailedPrompt());
            FormDefinition failedForm = webFormRuntimeService.getFormDefinitionWithInstance(sessionId);

            if (failedForm.moveToQuestion(failedPath, failedInstancePath, null, null)) {
                failedQuestion = buildQuestionResponse(failedForm);
                message = validationMessage(result.getOutcomeCode(), failedQuestion);
                // Keep the persisted runtime aligned with the field the user now
                // needs to correct.
                webFormRuntimeService.persistFormState(sessionId, failedForm);
            }
        }

        if (!result.isValid() && message == null) {
            message = "Please correct the highlighted form field before continuing.";
        }

        return ValidationResponse.builder()
                .valid(result.isValid())
                .failedPromptIndex(result.getFailedPrompt() != null ? result.getFailedPrompt().toString() : null)
                .outcomeCode(result.getOutcomeCode())
                .message(message)
                .failedQuestion(failedQuestion)
                .build();
    }

    private String validationMessage(int outcomeCode, QuestionResponse question) {
        String label = question.getLabel();
        if (label == null || label.isBlank()) {
            label = question.getName() != null && !question.getName().isBlank()
                    ? question.getName()
                    : question.getPath();
        }

        if (outcomeCode == FormEntryController.ANSWER_REQUIRED_BUT_EMPTY) {
            return label + " is required.";
        }
        if (outcomeCode == FormEntryController.ANSWER_CONSTRAINT_VIOLATED) {
            if (question.getConstraintText() != null && !question.getConstraintText().isBlank()) {
                return question.getConstraintText();
            }
            return "Please enter a valid value for " + label + ".";
        }
        return "Please check " + label + ".";
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

    private String getCurrentQuestionType(FormDefinition formDef) {
        int controlType = formDef.getCurrentControlType();
        int dataType = formDef.getCurrentDataType();

        if (controlType == Constants.CONTROL_LABEL) return "note";
        // XLSForm compiles `note` rows as readonly string <input> controls.
        // Expose those as semantic notes so the web client renders content,
        // not a disabled answer box.
        if (controlType == Constants.CONTROL_INPUT
                && dataType == Constants.DATATYPE_TEXT
                && formDef.isCurrentReadOnly()) {
            return "note";
        }
        if (controlType == Constants.CONTROL_SELECT_ONE) return "select1";
        if (controlType == Constants.CONTROL_SELECT_MULTI || controlType == Constants.CONTROL_RANK) return "select";

        return switch (dataType) {
            case Constants.DATATYPE_GEOPOINT -> "geopoint";
            case Constants.DATATYPE_GEOSHAPE -> "geoshape";
            case Constants.DATATYPE_GEOTRACE -> "geotrace";
            case Constants.DATATYPE_BARCODE -> "barcode";
            case Constants.DATATYPE_BINARY -> "binary";
            case Constants.DATATYPE_DATE -> "date";
            case Constants.DATATYPE_TIME -> "time";
            case Constants.DATATYPE_DATE_TIME -> "datetime";
            case Constants.DATATYPE_INTEGER -> "int";
            case Constants.DATATYPE_LONG -> "long";
            case Constants.DATATYPE_DECIMAL -> "decimal";
            case Constants.DATATYPE_CHOICE -> "select1";
            case Constants.DATATYPE_MULTIPLE_ITEMS -> "select";
            case Constants.DATATYPE_TEXT -> "text";
            default -> {
                if (controlType == Constants.CONTROL_UPLOAD
                        || controlType == Constants.CONTROL_IMAGE_CHOOSE
                        || controlType == Constants.CONTROL_AUDIO_CAPTURE
                        || controlType == Constants.CONTROL_VIDEO_CAPTURE
                        || controlType == Constants.CONTROL_FILE_CAPTURE) {
                    yield "binary";
                }
                yield "text";
            }
        };
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
                .questionType(getCurrentQuestionType(formDef))
                .required(formDef.isCurrentRequired())
                .readOnly(formDef.isCurrentReadOnly())
                .constraintText(formDef.getCurrentConstraintText())
                .appearanceHint(formDef.getCurrentAppearanceHint())
                .mediaType(formDef.getCurrentMediaType())
                .promptAudioFile(formDef.getCurrentPromptMediaFile("audio"))
                .promptImageFile(formDef.getCurrentPromptMediaFile("image"))
                .promptVideoFile(formDef.getCurrentPromptMediaFile("video"))
                .currentAnswer(formDef.getCurrentAnswerValue())
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
                .appearanceHint(formDef.getCurrentGroupAppearanceHint())
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
                        .appearanceHint(context.appearanceHint())
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
        private ValidationResponse validation;
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
    public static class ProgressResponse {
        private int answeredQuestions;
        private int totalQuestions;
        private int percent;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ValidationResponse {
        private boolean valid;
        private String failedPromptIndex;
        private int outcomeCode;
        private String message;
        private QuestionResponse failedQuestion;
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
        private String questionType;
        private boolean required;
        private boolean readOnly;
        private String constraintText;
        private String appearanceHint;
        private String mediaType;
        private String promptAudioFile;
        private String promptImageFile;
        private String promptVideoFile;
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
        private String appearanceHint;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class FieldListResponse {
        private String groupPath;
        private String groupInstancePath;
        private List<QuestionResponse> questions;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class FieldListNavigateRequest {
        private String groupInstancePath;
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
        private String appearanceHint;
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