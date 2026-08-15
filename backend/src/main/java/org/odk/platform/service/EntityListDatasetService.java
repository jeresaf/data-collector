package org.odk.platform.service;

import org.odk.platform.domain.EntityItem;
import org.odk.platform.domain.EntityItemProperty;
import org.odk.platform.domain.EntityList;
import org.odk.platform.domain.FormVersion;
import org.odk.platform.repository.EntityListRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Exposes project-scoped Entity Lists to the JavaRosa runtime using the standard
 * ODK external-dataset shape: {@code name,label,<properties...>}.
 *
 * <p>This service only provides the read/lookup bridge required by web forms.
 * Full Entity List administration/import/export remains part of the generic
 * reference-data management epic.</p>
 */
@Service
@Transactional(readOnly = true)
public class EntityListDatasetService {

    private static final Pattern SAFE_PROPERTY_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");
    private static final Set<String> RESERVED_COLUMNS = Set.of("name", "label");

    private final EntityListRepository entityListRepository;

    public EntityListDatasetService(EntityListRepository entityListRepository) {
        this.entityListRepository = entityListRepository;
    }

    public record DatasetBundle(Map<String, byte[]> datasets, List<String> warnings) {
        public DatasetBundle {
            datasets = Map.copyOf(datasets);
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * Materialize only Entity Lists referenced by this XForm.
     *
     * <p>Unresolved references are intentionally not warned here because an
     * explicitly uploaded form-version attachment may satisfy the same dataset
     * reference. The runtime preprocessor emits the final missing-dataset warning
     * after uploaded attachments and Entity Lists have been merged.</p>
     */
    public DatasetBundle loadReferencedEntityDatasets(FormVersion formVersion) {
        if (formVersion == null || formVersion.getXmlContent() == null || formVersion.getForm() == null
                || formVersion.getForm().getProject() == null) {
            return new DatasetBundle(Map.of(), List.of());
        }

        Set<String> referencedNames = XFormRuntimePreprocessor.referencedExternalDatasetNames(
                formVersion.getXmlContent());
        if (referencedNames.isEmpty()) {
            return new DatasetBundle(Map.of(), List.of());
        }

        UUID projectId = formVersion.getForm().getProject().getId();
        Map<String, byte[]> datasets = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();

        for (String name : referencedNames) {
            Optional<EntityList> entityList = entityListRepository
                    .findByProjectIdAndNameAndIsActiveTrue(projectId, name);
            if (entityList.isEmpty()) {
                continue;
            }

            CsvResult csv = toCsv(entityList.get());
            byte[] bytes = csv.csv().getBytes(StandardCharsets.UTF_8);
            datasets.put(name, bytes);
            datasets.put(name + ".csv", bytes);
            warnings.addAll(csv.warnings());
        }

        return new DatasetBundle(datasets, warnings);
    }

    CsvResult toCsv(EntityList entityList) {
        List<String> warnings = new ArrayList<>();
        TreeSet<String> propertyNames = new TreeSet<>();

        for (EntityItem item : safeItems(entityList)) {
            if (!Boolean.TRUE.equals(item.getIsActive())) {
                continue;
            }
            for (EntityItemProperty property : safeProperties(item)) {
                String name = property.getPropertyName();
                if (name == null || name.isBlank()) {
                    warnings.add("Entity List '" + entityList.getName()
                            + "' contains an empty property name; that property is ignored by the web runtime.");
                    continue;
                }
                if (RESERVED_COLUMNS.contains(name)) {
                    warnings.add("Entity List '" + entityList.getName() + "' property '" + name
                            + "' conflicts with the reserved ODK dataset column '" + name + "' and is ignored.");
                    continue;
                }
                if (!SAFE_PROPERTY_NAME.matcher(name).matches()) {
                    warnings.add("Entity List '" + entityList.getName() + "' property '" + name
                            + "' is not a valid XForm dataset column name and is ignored by the web runtime.");
                    continue;
                }
                propertyNames.add(name);
            }
        }

        List<String> headers = new ArrayList<>();
        headers.add("name");
        headers.add("label");
        headers.addAll(propertyNames);

        StringBuilder csv = new StringBuilder();
        appendCsvRow(csv, headers);

        safeItems(entityList).stream()
                .filter(item -> Boolean.TRUE.equals(item.getIsActive()))
                .sorted(Comparator.comparing(EntityItem::getEntityId, Comparator.nullsLast(String::compareTo)))
                .forEach(item -> {
                    Map<String, String> values = new LinkedHashMap<>();
                    for (EntityItemProperty property : safeProperties(item)) {
                        if (propertyNames.contains(property.getPropertyName())) {
                            values.put(property.getPropertyName(), property.getPropertyValue());
                        }
                    }

                    List<String> row = new ArrayList<>();
                    row.add(item.getEntityId() == null ? "" : item.getEntityId());
                    row.add(item.getLabel() == null ? "" : item.getLabel());
                    for (String propertyName : propertyNames) {
                        row.add(values.getOrDefault(propertyName, ""));
                    }
                    appendCsvRow(csv, row);
                });

        return new CsvResult(csv.toString(), distinct(warnings));
    }

    record CsvResult(String csv, List<String> warnings) {
    }

    private static Set<EntityItem> safeItems(EntityList list) {
        return list.getItems() == null ? Set.of() : list.getItems();
    }

    private static Set<EntityItemProperty> safeProperties(EntityItem item) {
        return item.getProperties() == null ? Set.of() : item.getProperties();
    }

    private static List<String> distinct(List<String> warnings) {
        return new ArrayList<>(new LinkedHashSet<>(warnings));
    }

    private static void appendCsvRow(StringBuilder out, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(csvEscape(values.get(i)));
        }
        out.append('\n');
    }

    private static String csvEscape(String value) {
        String text = value == null ? "" : value;
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }
}
