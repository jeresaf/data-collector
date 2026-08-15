package org.odk.platform.controller;

import lombok.RequiredArgsConstructor;
import org.odk.platform.service.ReportDefinitionService;
import org.odk.platform.service.ReportExportService;
import org.odk.platform.service.ReportRunService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects/{projectId}/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportDefinitionService definitions;
    private final ReportRunService runner;
    private final ReportExportService exporter;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('USER')")
    public List<ReportDefinitionService.DefinitionView> list(@PathVariable UUID projectId) {
        return definitions.listDefinitions(projectId);
    }

    @GetMapping("/{reportId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('USER')")
    public ReportDefinitionService.DefinitionView get(@PathVariable UUID projectId, @PathVariable UUID reportId) {
        return definitions.getDefinition(projectId, reportId);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER')")
    public ReportDefinitionService.DefinitionView create(@PathVariable UUID projectId,
                                                          @RequestBody ReportDefinitionService.DefinitionCommand command) {
        return definitions.createDefinition(projectId, command);
    }

    @PutMapping("/{reportId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER')")
    public ReportDefinitionService.DefinitionView update(@PathVariable UUID projectId, @PathVariable UUID reportId,
                                                          @RequestBody ReportDefinitionService.DefinitionCommand command) {
        return definitions.updateDefinition(projectId, reportId, command);
    }

    @DeleteMapping("/{reportId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER')")
    public ResponseEntity<Void> delete(@PathVariable UUID projectId, @PathVariable UUID reportId) {
        definitions.deleteDefinition(projectId, reportId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/forms/{formId}/fields")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('USER')")
    public List<ReportDefinitionService.AvailableField> fields(@PathVariable UUID projectId, @PathVariable UUID formId) {
        return definitions.discoverFields(projectId, formId);
    }

    @PostMapping("/{reportId}/run")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('USER')")
    public ReportRunService.ReportResult run(@PathVariable UUID projectId, @PathVariable UUID reportId,
                                              @RequestBody(required = false) ReportRunService.RunParameters parameters) {
        return runner.run(projectId, reportId, parameters);
    }

    @PostMapping("/{reportId}/export/{format}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('USER')")
    public ResponseEntity<byte[]> export(@PathVariable UUID projectId, @PathVariable UUID reportId,
                                          @PathVariable String format,
                                          @RequestBody(required = false) ReportRunService.RunParameters parameters) {
        ReportExportService.ExportPayload export = exporter.export(projectId, reportId, format, parameters);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(export.contentType()));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(export.filename(), StandardCharsets.UTF_8).build());
        headers.set("X-Report-Run-Id", export.runId().toString());
        headers.set("X-Report-Export-Id", export.exportId().toString());
        return ResponseEntity.ok().headers(headers).body(export.bytes());
    }

    @GetMapping("/widgets")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('USER')")
    public List<ReportDefinitionService.WidgetView> widgets(@PathVariable UUID projectId) {
        return definitions.listWidgets(projectId);
    }

    @PostMapping("/widgets")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER')")
    public ReportDefinitionService.WidgetView createWidget(@PathVariable UUID projectId,
                                                            @RequestBody ReportDefinitionService.WidgetCommand command) {
        return definitions.createWidget(projectId, command);
    }

    @DeleteMapping("/widgets/{widgetId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER')")
    public ResponseEntity<Void> deleteWidget(@PathVariable UUID projectId, @PathVariable UUID widgetId) {
        definitions.deleteWidget(projectId, widgetId);
        return ResponseEntity.noContent().build();
    }
}
