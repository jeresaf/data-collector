package org.odk.platform.service;

import lombok.RequiredArgsConstructor;
import org.odk.platform.audit.AuditActions;
import org.odk.platform.audit.AuditService;
import org.odk.platform.domain.Project;
import org.odk.platform.domain.ReferenceItem;
import org.odk.platform.domain.ReferenceItemProperty;
import org.odk.platform.domain.ReferenceItemRelationship;
import org.odk.platform.domain.ReferenceList;
import org.odk.platform.repository.ReferenceItemPropertyRepository;
import org.odk.platform.repository.ReferenceItemRelationshipRepository;
import org.odk.platform.repository.ReferenceItemRepository;
import org.odk.platform.repository.ReferenceListRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReferenceDataService {

    private static final Pattern LIST_NAME_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]{0,254}");
    private static final Set<String> RESERVED_CSV_COLUMNS = Set.of(
            "name", "label", "active", "sort_order", "parent", "parent_list", "relationship_type");

    private final ReferenceListRepository listRepository;
    private final ReferenceItemRepository itemRepository;
    private final ReferenceItemPropertyRepository propertyRepository;
    private final ReferenceItemRelationshipRepository relationshipRepository;
    private final ProjectService projectService;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<ReferenceListView> listLists(UUID projectId) {
        requireProject(projectId);
        return listRepository.findByProjectIdOrderByNameAsc(projectId).stream()
                .map(this::toListView)
                .toList();
    }

    @Transactional
    public ReferenceListView createList(UUID projectId, CreateListCommand command) {
        Project project = requireProject(projectId);
        String name = normalizeListName(command.name());
        if (listRepository.existsByProjectIdAndNameIgnoreCase(projectId, name)) {
            throw conflict("Reference list '" + name + "' already exists");
        }
        ReferenceList list = ReferenceList.builder()
                .project(project)
                .name(name)
                .displayName(blankToNull(command.displayName()))
                .description(blankToNull(command.description()))
                .isActive(command.active() == null || command.active())
                .build();
        list = listRepository.save(list);
        auditReferenceChange(projectId, list.getId(), list.getName(), "LIST_CREATED", null);
        return toListView(list);
    }

    @Transactional
    public ReferenceListView updateList(UUID projectId, UUID listId, UpdateListCommand command) {
        ReferenceList list = requireList(projectId, listId);
        if (command.name() != null) {
            String name = normalizeListName(command.name());
            listRepository.findByProjectIdAndNameIgnoreCase(projectId, name)
                    .filter(other -> !other.getId().equals(listId))
                    .ifPresent(other -> { throw conflict("Reference list '" + name + "' already exists"); });
            list.setName(name);
        }
        if (command.displayName() != null) list.setDisplayName(blankToNull(command.displayName()));
        if (command.description() != null) list.setDescription(blankToNull(command.description()));
        if (command.active() != null) list.setIsActive(command.active());
        list = listRepository.save(list);
        auditReferenceChange(projectId, list.getId(), list.getName(), "LIST_UPDATED", null);
        return toListView(list);
    }

    @Transactional
    public void deleteList(UUID projectId, UUID listId) {
        ReferenceList list = requireList(projectId, listId);
        String name = list.getName();
        listRepository.delete(list);
        auditReferenceChange(projectId, listId, name, "LIST_DELETED", null);
    }

    @Transactional(readOnly = true)
    public List<ReferenceItemView> listItems(UUID projectId, UUID listId) {
        requireList(projectId, listId);
        return itemRepository.findByReferenceListIdOrderBySortOrderAscLabelAsc(listId).stream()
                .map(this::toItemView)
                .toList();
    }

    @Transactional
    public ReferenceItemView createItem(UUID projectId, UUID listId, UpsertItemCommand command) {
        ReferenceList list = requireList(projectId, listId);
        String itemKey = requireItemKey(command.name());
        if (itemRepository.existsByReferenceListIdAndItemKey(listId, itemKey)) {
            throw conflict("Reference item '" + itemKey + "' already exists");
        }
        ReferenceItem item = ReferenceItem.builder()
                .referenceList(list)
                .itemKey(itemKey)
                .label(requireLabel(command.label()))
                .sortOrder(command.sortOrder() == null ? 0 : command.sortOrder())
                .isActive(command.active() == null || command.active())
                .build();
        item = itemRepository.save(item);
        replaceProperties(item, command.properties());
        replaceParents(projectId, item, command.parents());
        auditReferenceChange(projectId, listId, list.getName(), "ITEM_CREATED", item.getItemKey());
        return toItemView(item);
    }

    @Transactional
    public ReferenceItemView updateItem(UUID projectId, UUID listId, UUID itemId, UpsertItemCommand command) {
        requireList(projectId, listId);
        ReferenceItem item = requireItem(listId, itemId);
        String itemKey = requireItemKey(command.name());
        itemRepository.findByReferenceListIdAndItemKey(listId, itemKey)
                .filter(other -> !other.getId().equals(itemId))
                .ifPresent(other -> { throw conflict("Reference item '" + itemKey + "' already exists"); });
        item.setItemKey(itemKey);
        item.setLabel(requireLabel(command.label()));
        item.setSortOrder(command.sortOrder() == null ? 0 : command.sortOrder());
        item.setIsActive(command.active() == null || command.active());
        itemRepository.save(item);
        replaceProperties(item, command.properties());
        replaceParents(projectId, item, command.parents());
        auditReferenceChange(projectId, listId, item.getReferenceList().getName(), "ITEM_UPDATED", item.getItemKey());
        return toItemView(item);
    }

    @Transactional
    public void deleteItem(UUID projectId, UUID listId, UUID itemId) {
        ReferenceList list = requireList(projectId, listId);
        ReferenceItem item = requireItem(listId, itemId);
        String itemKey = item.getItemKey();
        itemRepository.delete(item);
        auditReferenceChange(projectId, listId, list.getName(), "ITEM_DELETED", itemKey);
    }

    @Transactional
    public RelationshipView addRelationship(UUID projectId, UUID listId, UUID childId, RelationshipCommand command) {
        requireList(projectId, listId);
        ReferenceItem child = requireItem(listId, childId);
        ReferenceItem parent = requireProjectItem(projectId, command.parentItemId());
        if (parent.getId().equals(child.getId())) {
            throw badRequest("A reference item cannot be its own parent");
        }
        String type = normalizeRelationshipType(command.relationshipType());
        Optional<ReferenceItemRelationship> existing = relationshipRepository
                .findByParentItemIdAndChildItemIdAndRelationshipType(parent.getId(), child.getId(), type);
        if (existing.isPresent()) return toRelationshipView(existing.get());
        ReferenceItemRelationship relationship = ReferenceItemRelationship.builder()
                .parentItem(parent)
                .childItem(child)
                .relationshipType(type)
                .sortOrder(command.sortOrder() == null ? 0 : command.sortOrder())
                .build();
        relationship = relationshipRepository.save(relationship);
        auditReferenceChange(projectId, listId, child.getReferenceList().getName(), "RELATIONSHIP_CREATED", child.getItemKey());
        return toRelationshipView(relationship);
    }

    @Transactional
    public void deleteRelationship(UUID projectId, UUID relationshipId) {
        ReferenceItemRelationship relationship = relationshipRepository.findById(relationshipId)
                .orElseThrow(() -> notFound("Reference relationship not found"));
        UUID childProjectId = relationship.getChildItem().getReferenceList().getProject().getId();
        if (!projectId.equals(childProjectId)) throw notFound("Reference relationship not found");
        UUID listId = relationship.getChildItem().getReferenceList().getId();
        String listName = relationship.getChildItem().getReferenceList().getName();
        String itemKey = relationship.getChildItem().getItemKey();
        relationshipRepository.delete(relationship);
        auditReferenceChange(projectId, listId, listName, "RELATIONSHIP_DELETED", itemKey);
    }

    /**
     * Imports UTF-8 CSV. Required columns are name,label. Extra columns become
     * flexible item properties. Optional parent/parent_list/relationship_type
     * columns create hierarchy links after all rows have been upserted.
     */
    @Transactional
    public ImportResult importCsv(UUID projectId, UUID listId, byte[] bytes) {
        ReferenceList list = requireList(projectId, listId);
        if (bytes == null || bytes.length == 0) throw badRequest("CSV file is empty");
        String csv = new String(bytes, StandardCharsets.UTF_8);
        if (csv.startsWith("\uFEFF")) csv = csv.substring(1);
        List<List<String>> rows = parseCsv(csv);
        if (rows.isEmpty()) throw badRequest("CSV file has no rows");

        List<String> rawHeaders = rows.get(0).stream().map(String::trim).toList();
        Map<String, Integer> headerIndex = new LinkedHashMap<>();
        for (int i = 0; i < rawHeaders.size(); i++) {
            String normalized = rawHeaders.get(i).toLowerCase(Locale.ROOT);
            if (normalized.isBlank()) continue;
            if (headerIndex.putIfAbsent(normalized, i) != null) {
                throw badRequest("CSV contains duplicate column '" + rawHeaders.get(i) + "'");
            }
        }
        if (!headerIndex.containsKey("name") || !headerIndex.containsKey("label")) {
            throw badRequest("CSV must contain 'name' and 'label' columns");
        }

        int created = 0;
        int updated = 0;
        List<String> warnings = new ArrayList<>();
        List<DeferredParent> deferredParents = new ArrayList<>();

        for (int rowNumber = 1; rowNumber < rows.size(); rowNumber++) {
            List<String> row = rows.get(rowNumber);
            if (row.stream().allMatch(String::isBlank)) continue;
            String key = cell(row, headerIndex.get("name")).trim();
            String label = cell(row, headerIndex.get("label")).trim();
            if (key.isBlank() || label.isBlank()) {
                warnings.add("Row " + (rowNumber + 1) + " skipped because name or label is blank");
                continue;
            }
            ReferenceItem item = itemRepository.findByReferenceListIdAndItemKey(listId, key).orElse(null);
            boolean isNew = item == null;
            if (isNew) {
                item = ReferenceItem.builder().referenceList(list).itemKey(key).build();
            }
            item.setLabel(label);
            item.setIsActive(parseBoolean(cell(row, headerIndex.get("active")), true));
            item.setSortOrder(parseInteger(cell(row, headerIndex.get("sort_order")), 0, rowNumber + 1, warnings));
            item = itemRepository.save(item);
            if (isNew) created++; else updated++;

            Map<String, String> properties = new LinkedHashMap<>();
            for (int i = 0; i < rawHeaders.size(); i++) {
                String header = rawHeaders.get(i);
                String normalized = header.toLowerCase(Locale.ROOT);
                if (header.isBlank() || RESERVED_CSV_COLUMNS.contains(normalized)) continue;
                properties.put(header, cell(row, i));
            }
            replaceProperties(item, properties);

            String parentKeys = cell(row, headerIndex.get("parent")).trim();
            if (!parentKeys.isBlank()) {
                deferredParents.add(new DeferredParent(item.getId(), parentKeys,
                        cell(row, headerIndex.get("parent_list")).trim(),
                        cell(row, headerIndex.get("relationship_type")).trim(), rowNumber + 1));
            }
        }

        int relationships = 0;
        for (DeferredParent deferred : deferredParents) {
            ReferenceItem child = itemRepository.findById(deferred.childId()).orElseThrow();
            relationshipRepository.deleteByChildItemId(child.getId());
            String[] parentKeys = deferred.parentKeys().split("\\|", -1);
            String[] parentLists = deferred.parentListName().isBlank()
                    ? new String[]{list.getName()}
                    : deferred.parentListName().split("\\|", -1);
            String[] relationshipTypes = deferred.relationshipType().isBlank()
                    ? new String[]{"parent"}
                    : deferred.relationshipType().split("\\|", -1);

            for (int parentIndex = 0; parentIndex < parentKeys.length; parentIndex++) {
                String trimmed = parentKeys[parentIndex].trim();
                if (trimmed.isBlank()) continue;
                String parentListName = parentLists.length == 1
                        ? parentLists[0].trim()
                        : (parentIndex < parentLists.length ? parentLists[parentIndex].trim() : "");
                if (parentListName.isBlank()) parentListName = list.getName();
                ReferenceList parentList = listRepository.findByProjectIdAndNameIgnoreCase(projectId, parentListName).orElse(null);
                if (parentList == null) {
                    warnings.add("Row " + deferred.rowNumber() + ": parent_list '" + parentListName + "' was not found");
                    continue;
                }
                ReferenceItem parent = itemRepository.findByReferenceListIdAndItemKey(parentList.getId(), trimmed).orElse(null);
                if (parent == null) {
                    warnings.add("Row " + deferred.rowNumber() + ": parent '" + trimmed + "' was not found in '" + parentListName + "'");
                    continue;
                }
                if (parent.getId().equals(child.getId())) {
                    warnings.add("Row " + deferred.rowNumber() + ": self-parent relationship ignored");
                    continue;
                }
                String typeValue = relationshipTypes.length == 1
                        ? relationshipTypes[0]
                        : (parentIndex < relationshipTypes.length ? relationshipTypes[parentIndex] : "parent");
                String type = normalizeRelationshipType(typeValue);
                if (relationshipRepository.findByParentItemIdAndChildItemIdAndRelationshipType(
                        parent.getId(), child.getId(), type).isEmpty()) {
                    relationshipRepository.save(ReferenceItemRelationship.builder()
                            .parentItem(parent).childItem(child).relationshipType(type).sortOrder(parentIndex).build());
                    relationships++;
                }
            }
        }

        auditService.log(AuditActions.REFERENCE_DATA_CHANGED, "REFERENCE_LIST", listId, list.getName(), Map.of(
                "projectId", projectId.toString(),
                "changeType", "CSV_IMPORTED",
                "created", created,
                "updated", updated,
                "relationships", relationships,
                "warnings", warnings.size()));
        return new ImportResult(created, updated, relationships, warnings);
    }

    @Transactional(readOnly = true)
    public CsvExport exportCsv(UUID projectId, UUID listId) {
        ReferenceList list = requireList(projectId, listId);
        List<ReferenceItem> items = itemRepository.findByReferenceListIdOrderBySortOrderAscLabelAsc(listId);
        Set<String> propertyNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Map<UUID, Map<String, String>> propertiesByItem = new LinkedHashMap<>();
        for (ReferenceItem item : items) {
            Map<String, String> properties = propertyRepository.findByReferenceItemIdOrderByPropertyNameAsc(item.getId()).stream()
                    .collect(Collectors.toMap(ReferenceItemProperty::getPropertyName,
                            p -> Optional.ofNullable(p.getPropertyValue()).orElse(""),
                            (a, b) -> b, LinkedHashMap::new));
            propertiesByItem.put(item.getId(), properties);
            propertyNames.addAll(properties.keySet());
        }

        List<String> headers = new ArrayList<>(List.of(
                "name", "label", "active", "sort_order", "parent", "parent_list", "relationship_type"));
        headers.addAll(propertyNames);
        StringBuilder out = new StringBuilder();
        appendCsvRow(out, headers);
        for (ReferenceItem item : items) {
            List<ReferenceItemRelationship> parents = relationshipRepository.findByChildItemIdOrderBySortOrderAsc(item.getId());
            String parentKeys = parents.stream().map(r -> r.getParentItem().getItemKey()).collect(Collectors.joining("|"));
            String parentLists = parents.stream().map(r -> r.getParentItem().getReferenceList().getName())
                    .collect(Collectors.joining("|"));
            String relationshipTypes = parents.stream().map(ReferenceItemRelationship::getRelationshipType)
                    .collect(Collectors.joining("|"));
            List<String> row = new ArrayList<>(List.of(
                    item.getItemKey(), item.getLabel(), String.valueOf(Boolean.TRUE.equals(item.getIsActive())),
                    String.valueOf(item.getSortOrder()), parentKeys, parentLists, relationshipTypes));
            Map<String, String> props = propertiesByItem.get(item.getId());
            for (String name : propertyNames) row.add(props.getOrDefault(name, ""));
            appendCsvRow(out, row);
        }
        String filename = sanitizeFilename(list.getName()) + ".csv";
        return new CsvExport(filename, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void auditReferenceChange(UUID projectId, UUID listId, String listName,
                                      String changeType, String itemKey) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("projectId", projectId.toString());
        details.put("changeType", changeType);
        if (itemKey != null && !itemKey.isBlank()) details.put("itemKey", itemKey);
        auditService.log(AuditActions.REFERENCE_DATA_CHANGED, "REFERENCE_LIST", listId, listName, details);
    }

    private void replaceProperties(ReferenceItem item, Map<String, String> properties) {
        propertyRepository.deleteByReferenceItemId(item.getId());
        if (properties == null || properties.isEmpty()) return;
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<ReferenceItemProperty> values = new ArrayList<>();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            String name = entry.getKey() == null ? "" : entry.getKey().trim();
            if (name.isBlank()) continue;
            String folded = name.toLowerCase(Locale.ROOT);
            if (!seen.add(folded)) throw badRequest("Duplicate property name '" + name + "'");
            values.add(ReferenceItemProperty.builder()
                    .referenceItem(item).propertyName(name).propertyValue(entry.getValue()).build());
        }
        propertyRepository.saveAll(values);
    }

    private void replaceParents(UUID projectId, ReferenceItem child, List<ParentLinkCommand> parents) {
        relationshipRepository.deleteByChildItemId(child.getId());
        if (parents == null) return;
        for (int i = 0; i < parents.size(); i++) {
            ParentLinkCommand link = parents.get(i);
            ReferenceItem parent = requireProjectItem(projectId, link.parentItemId());
            if (parent.getId().equals(child.getId())) throw badRequest("A reference item cannot be its own parent");
            relationshipRepository.save(ReferenceItemRelationship.builder()
                    .parentItem(parent)
                    .childItem(child)
                    .relationshipType(normalizeRelationshipType(link.relationshipType()))
                    .sortOrder(link.sortOrder() == null ? i : link.sortOrder())
                    .build());
        }
    }

    private ReferenceItemView toItemView(ReferenceItem item) {
        Map<String, String> properties = propertyRepository.findByReferenceItemIdOrderByPropertyNameAsc(item.getId()).stream()
                .collect(Collectors.toMap(ReferenceItemProperty::getPropertyName,
                        p -> Optional.ofNullable(p.getPropertyValue()).orElse(""),
                        (a, b) -> b, LinkedHashMap::new));
        List<RelationshipView> parents = relationshipRepository.findByChildItemIdOrderBySortOrderAsc(item.getId()).stream()
                .map(this::toRelationshipView).toList();
        return new ReferenceItemView(item.getId(), item.getItemKey(), item.getLabel(), item.getSortOrder(),
                Boolean.TRUE.equals(item.getIsActive()), properties, parents, item.getCreatedAt(), item.getUpdatedAt());
    }

    private ReferenceListView toListView(ReferenceList list) {
        long itemCount = list.getId() == null ? 0 : itemRepository.findByReferenceListIdOrderBySortOrderAscLabelAsc(list.getId()).size();
        return new ReferenceListView(list.getId(), list.getName(), list.getDisplayName(), list.getDescription(),
                Boolean.TRUE.equals(list.getIsActive()), itemCount, list.getCreatedAt(), list.getUpdatedAt());
    }

    private RelationshipView toRelationshipView(ReferenceItemRelationship relationship) {
        ReferenceItem parent = relationship.getParentItem();
        return new RelationshipView(relationship.getId(), parent.getId(), parent.getReferenceList().getId(),
                parent.getReferenceList().getName(), parent.getItemKey(), parent.getLabel(),
                relationship.getRelationshipType(), relationship.getSortOrder());
    }

    private Project requireProject(UUID projectId) {
        return projectService.findById(projectId).orElseThrow(() -> notFound("Project not found"));
    }

    private ReferenceList requireList(UUID projectId, UUID listId) {
        return listRepository.findByIdAndProjectId(listId, projectId)
                .orElseThrow(() -> notFound("Reference list not found"));
    }

    private ReferenceItem requireItem(UUID listId, UUID itemId) {
        return itemRepository.findByIdAndReferenceListId(itemId, listId)
                .orElseThrow(() -> notFound("Reference item not found"));
    }

    private ReferenceItem requireProjectItem(UUID projectId, UUID itemId) {
        ReferenceItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> notFound("Reference item not found"));
        if (!projectId.equals(item.getReferenceList().getProject().getId())) throw notFound("Reference item not found");
        return item;
    }

    private String normalizeListName(String value) {
        String name = value == null ? "" : value.trim();
        if (!LIST_NAME_PATTERN.matcher(name).matches()) {
            throw badRequest("List name must start with a letter/underscore and contain only letters, numbers, '.', '_' or '-'");
        }
        return name;
    }

    private String requireItemKey(String value) {
        String key = value == null ? "" : value.trim();
        if (key.isBlank() || key.length() > 255) throw badRequest("Reference item name is required and must be at most 255 characters");
        return key;
    }

    private String requireLabel(String value) {
        String label = value == null ? "" : value.trim();
        if (label.isBlank()) throw badRequest("Reference item label is required");
        return label;
    }

    private String normalizeRelationshipType(String value) {
        String type = value == null || value.isBlank() ? "parent" : value.trim().toLowerCase(Locale.ROOT);
        if (type.length() > 100) throw badRequest("Relationship type must be at most 100 characters");
        return type;
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private static String cell(List<String> row, Integer index) {
        if (index == null || index < 0 || index >= row.size()) return "";
        return row.get(index);
    }

    private static boolean parseBoolean(String value, boolean defaultValue) {
        if (value == null || value.isBlank()) return defaultValue;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "y" -> true;
            case "false", "0", "no", "n" -> false;
            default -> defaultValue;
        };
    }

    private static int parseInteger(String value, int defaultValue, int row, List<String> warnings) {
        if (value == null || value.isBlank()) return defaultValue;
        try { return Integer.parseInt(value.trim()); }
        catch (NumberFormatException ex) {
            warnings.add("Row " + row + ": invalid sort_order '" + value + "'; using " + defaultValue);
            return defaultValue;
        }
    }

    static List<List<String>> parseCsv(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                        field.append('"'); i++;
                    } else quoted = false;
                } else field.append(c);
            } else {
                if (c == '"' && field.length() == 0) quoted = true;
                else if (c == ',') { row.add(field.toString()); field.setLength(0); }
                else if (c == '\n') { row.add(field.toString()); field.setLength(0); rows.add(row); row = new ArrayList<>(); }
                else if (c != '\r') field.append(c);
            }
        }
        if (quoted) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV contains an unclosed quoted field");
        if (field.length() > 0 || !row.isEmpty()) { row.add(field.toString()); rows.add(row); }
        return rows;
    }

    private static void appendCsvRow(StringBuilder out, Collection<String> values) {
        boolean first = true;
        for (String value : values) {
            if (!first) out.append(',');
            first = false;
            String text = value == null ? "" : value;
            if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
                out.append('"').append(text.replace("\"", "\"\"")).append('"');
            } else out.append(text);
        }
        out.append('\n');
    }

    private static String sanitizeFilename(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public record CreateListCommand(String name, String displayName, String description, Boolean active) {}
    public record UpdateListCommand(String name, String displayName, String description, Boolean active) {}
    public record ParentLinkCommand(UUID parentItemId, String relationshipType, Integer sortOrder) {}
    public record UpsertItemCommand(String name, String label, Integer sortOrder, Boolean active,
                                    Map<String, String> properties, List<ParentLinkCommand> parents) {}
    public record RelationshipCommand(UUID parentItemId, String relationshipType, Integer sortOrder) {}
    public record ReferenceListView(UUID id, String name, String displayName, String description, boolean active,
                                    long itemCount, Instant createdAt, Instant updatedAt) {}
    public record ReferenceItemView(UUID id, String name, String label, int sortOrder, boolean active,
                                    Map<String, String> properties, List<RelationshipView> parents,
                                    Instant createdAt, Instant updatedAt) {}
    public record RelationshipView(UUID id, UUID parentItemId, UUID parentListId, String parentListName,
                                   String parentName, String parentLabel, String relationshipType, int sortOrder) {}
    public record ImportResult(int created, int updated, int relationshipsCreated, List<String> warnings) {}
    public record CsvExport(String filename, byte[] bytes) {}
    private record DeferredParent(UUID childId, String parentKeys, String parentListName,
                                  String relationshipType, int rowNumber) {}
}
