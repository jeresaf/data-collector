package org.odk.platform.controller;

import lombok.RequiredArgsConstructor;
import org.odk.platform.service.ReferenceDataService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects/{projectId}/reference-lists")
@RequiredArgsConstructor
public class ReferenceDataController {

    private static final long MAX_CSV_BYTES = 5L * 1024 * 1024;
    private final ReferenceDataService service;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('USER')")
    public List<ReferenceDataService.ReferenceListView> list(@PathVariable UUID projectId) {
        return service.listLists(projectId);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER')")
    public ReferenceDataService.ReferenceListView create(@PathVariable UUID projectId,
                                                          @RequestBody ListRequest request) {
        return service.createList(projectId, new ReferenceDataService.CreateListCommand(
                request.name(), request.displayName(), request.description(), request.active()));
    }

    @PutMapping("/{listId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER')")
    public ReferenceDataService.ReferenceListView update(@PathVariable UUID projectId, @PathVariable UUID listId,
                                                          @RequestBody ListRequest request) {
        return service.updateList(projectId, listId, new ReferenceDataService.UpdateListCommand(
                request.name(), request.displayName(), request.description(), request.active()));
    }

    @DeleteMapping("/{listId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER')")
    public ResponseEntity<Void> delete(@PathVariable UUID projectId, @PathVariable UUID listId) {
        service.deleteList(projectId, listId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{listId}/items")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('USER')")
    public List<ReferenceDataService.ReferenceItemView> items(@PathVariable UUID projectId, @PathVariable UUID listId) {
        return service.listItems(projectId, listId);
    }

    @PostMapping("/{listId}/items")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER')")
    public ReferenceDataService.ReferenceItemView createItem(@PathVariable UUID projectId, @PathVariable UUID listId,
                                                              @RequestBody ItemRequest request) {
        return service.createItem(projectId, listId, toCommand(request));
    }

    @PutMapping("/{listId}/items/{itemId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER')")
    public ReferenceDataService.ReferenceItemView updateItem(@PathVariable UUID projectId, @PathVariable UUID listId,
                                                              @PathVariable UUID itemId,
                                                              @RequestBody ItemRequest request) {
        return service.updateItem(projectId, listId, itemId, toCommand(request));
    }

    @DeleteMapping("/{listId}/items/{itemId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER')")
    public ResponseEntity<Void> deleteItem(@PathVariable UUID projectId, @PathVariable UUID listId,
                                            @PathVariable UUID itemId) {
        service.deleteItem(projectId, listId, itemId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{listId}/items/{itemId}/relationships")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER')")
    public ReferenceDataService.RelationshipView addRelationship(@PathVariable UUID projectId,
                                                                  @PathVariable UUID listId,
                                                                  @PathVariable UUID itemId,
                                                                  @RequestBody RelationshipRequest request) {
        if (request.parentItemId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "parentItemId is required");
        }
        return service.addRelationship(projectId, listId, itemId,
                new ReferenceDataService.RelationshipCommand(
                        request.parentItemId(), request.relationshipType(), request.sortOrder()));
    }

    @DeleteMapping("/relationships/{relationshipId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER')")
    public ResponseEntity<Void> deleteRelationship(@PathVariable UUID projectId, @PathVariable UUID relationshipId) {
        service.deleteRelationship(projectId, relationshipId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/{listId}/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER')")
    public ReferenceDataService.ImportResult importCsv(@PathVariable UUID projectId, @PathVariable UUID listId,
                                                        @RequestParam("file") MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV file is empty");
        if (!filename.toLowerCase().endsWith(".csv")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only .csv files are accepted");
        }
        if (file.getSize() > MAX_CSV_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "CSV file exceeds the 5 MB limit");
        }
        return service.importCsv(projectId, listId, file.getBytes());
    }

    @GetMapping(value = "/{listId}/export", produces = "text/csv")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('USER')")
    public ResponseEntity<byte[]> exportCsv(@PathVariable UUID projectId, @PathVariable UUID listId) {
        ReferenceDataService.CsvExport export = service.exportCsv(projectId, listId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment().filename(export.filename()).build());
        return ResponseEntity.ok().headers(headers).body(export.bytes());
    }

    private static ReferenceDataService.UpsertItemCommand toCommand(ItemRequest request) {
        List<ReferenceDataService.ParentLinkCommand> parents = request.parents() == null ? List.of() :
                request.parents().stream().map(p -> new ReferenceDataService.ParentLinkCommand(
                        p.parentItemId(), p.relationshipType(), p.sortOrder())).toList();
        return new ReferenceDataService.UpsertItemCommand(request.name(), request.label(), request.sortOrder(),
                request.active(), request.properties() == null ? Map.of() : request.properties(), parents);
    }

    public record ListRequest(String name, String displayName, String description, Boolean active) {}
    public record ItemRequest(String name, String label, Integer sortOrder, Boolean active,
                              Map<String, String> properties, List<ParentRequest> parents) {}
    public record ParentRequest(UUID parentItemId, String relationshipType, Integer sortOrder) {}
    public record RelationshipRequest(UUID parentItemId, String relationshipType, Integer sortOrder) {}
}
