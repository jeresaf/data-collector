package org.odk.platform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javarosa.core.model.FormDef;
import org.javarosa.core.model.instance.TreeElement;
import org.odk.platform.domain.FormVersion;
import org.odk.platform.exception.FormValidationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Combines the JavaRosa-backed extraction services into one metadata result. */
@Service
@RequiredArgsConstructor
@Slf4j
public class FormMetadataExtractor {

    private final JavaRosaFormLoader formLoader;
    private final XFormBindExtractor bindExtractor;
    private final XFormBodyExtractor bodyExtractor;
    private final XFormChoiceExtractor choiceExtractor;
    private final XFormITextExtractor itextExtractor;

    public FormMetadataResult extractMetadata(String xmlContent) {
        FormDef formDef = formLoader.loadFormDef(xmlContent);
        return extractMetadata(formDef, xmlContent);
    }

    public FormMetadataResult extractMetadataFromXml(String xmlContent) {
        try {
            FormDef formDef = formLoader.loadFormDef(xmlContent);
            return extractMetadata(formDef, xmlContent);
        } catch (FormValidationException e) {
            throw new IllegalArgumentException("Invalid XForm XML", e);
        }
    }

    public FormMetadataResult extractMetadata(FormDef formDef, String xmlContent) {
        if (formDef == null) {
            throw new IllegalArgumentException("FormDef cannot be null");
        }
        if (xmlContent == null || xmlContent.isBlank()) {
            xmlContent = formLoader.getXmlContent(formDef);
        }
        if (xmlContent == null || xmlContent.isBlank()) {
            throw new IllegalArgumentException("Original XForm XML is required to generate metadata");
        }

        XFormBindExtractor.BindExtractionResult binds = bindExtractor.extractBinds(formDef);
        XFormBodyExtractor.BodyExtractionResult body = bodyExtractor.extractBody(formDef);
        XFormChoiceExtractor.ChoiceExtractionResult choices = choiceExtractor.extractChoices(formDef);
        XFormITextExtractor.ITextExtractionResult itext = itextExtractor.extractIText(formDef);

        MediaExtractionResult media = extractMediaReferences(formDef, binds, choices, itext);
        ExternalDatasetExtractionResult externalDatasets = extractExternalDatasetReferences(choices);
        EntityExtractionResult entities = extractEntityReferences(binds, choices);

        FormMetadataResult result = FormMetadataResult.builder()
                .formId(formLoader.getFormId(formDef))
                .title(formDef.getTitle())
                .version(formLoader.getFormVersion(formDef))
                .xmlns(formLoader.getFormXmlns(formDef))
                .instanceRootNode(formLoader.getInstanceRootNode(formDef))
                .formHash(formLoader.generateFormHash(xmlContent))
                .binds(binds)
                .body(body)
                .choices(choices)
                .itext(itext)
                .media(media)
                .externalDatasets(externalDatasets)
                .entities(entities)
                .build();

        log.debug("Extracted metadata for form {}: binds={}, controls={}, choiceLists={}, languages={}, media={}, datasets={}, entities={}",
                result.getFormId(), binds.getBindCount(), body.getControlCount(), choices.getChoiceListCount(),
                itext.getLanguageCount(), media.getMediaCount(), externalDatasets.getDatasetCount(),
                entities.getEntityCount());
        return result;
    }

    /**
     * Extracts simplified metadata for backward compatibility with tests.
     */
    public FormMetadata extractMetadata(FormDef formDef) {
        if (formDef == null) {
            return FormMetadata.builder()
                    .formId(null)
                    .title(null)
                    .version(null)
                    .languages(List.of())
                    .defaultLanguage(null)
                    .fieldCount(0)
                    .choiceListCount(0)
                    .repeatCount(0)
                    .groupCount(0)
                    .mediaFieldCount(0)
                    .fields(List.of())
                    .choiceLists(List.of())
                    .build();
        }

        XFormBindExtractor.BindExtractionResult binds = bindExtractor.extractBinds(formDef);
        XFormBodyExtractor.BodyExtractionResult body = bodyExtractor.extractBody(formDef);
        XFormChoiceExtractor.ChoiceExtractionResult choices = choiceExtractor.extractChoices(formDef);
        XFormITextExtractor.ITextExtractionResult itext = itextExtractor.extractIText(formDef);

        List<XFormBodyExtractor.ControlInfo> bodyControls = safeList(body.getControls());

        // Controls describe presentation (label, hint, appearance, media type, etc.),
        // while binds are the authoritative source for data-backed fields. Building
        // fields from binds also keeps calculated/output values that JavaRosa may not
        // expose as normal QuestionDef controls.
        Map<String, XFormBodyExtractor.ControlInfo> controlsByXpath = bodyControls.stream()
                .filter(c -> c != null && !isBlank(c.getXpathReference()))
                .collect(Collectors.toMap(
                        c -> canonicalReference(c.getXpathReference()),
                        c -> c,
                        (first, ignored) -> first));

        Map<String, String> mediaTypes = formLoader.extractMediaTypes(formDef);

        // JavaRosa can expose group/repeat container nodes as BindInfo entries
        // because those nodes participate in the instance tree. They are not
        // answer fields, so exclude binds whose XPath belongs to a structural
        // group or repeat control. Hidden/calculated binds with no body control
        // remain valid fields and are therefore preserved.
        Set<String> structuralXpaths = new java.util.HashSet<>(structuralControlXpaths(bodyControls));
        structuralXpaths.addAll(structuralInstanceXpaths(formDef));
        Set<String> explicitBindXpaths = explicitBindXpaths(formDef);

        List<FieldInfo> fields = uniqueDataFieldBinds(
                        safeList(binds.getBinds()),
                        explicitBindXpaths,
                        structuralXpaths).stream()
                .map(bind -> {
                    String xpath = normalizedBindReference(bind);
                    XFormBodyExtractor.ControlInfo control = controlsByXpath.get(xpath);
                    String mediaType = firstNonBlank(
                            xpath == null ? null : mediaTypes.get(xpath),
                            control == null ? null : control.getMediaType(),
                            bind.getAttachmentMediaType(),
                            bind.isAttachment() ? "binary" : null);
                    return bindToFieldInfo(bind, control, mediaType);
                })
                .collect(Collectors.toCollection(ArrayList::new));

        List<ChoiceListInfo> choiceLists = safeList(choices.getChoiceLists()).stream()
                .map(this::choiceListToChoiceListInfo)
                .collect(Collectors.toList());

        int repeatCount = (int) bodyControls.stream()
                .filter(c -> c != null && "repeat".equals(c.getControlType()))
                .count();

        long userDefinedGroupCount = bodyControls.stream()
                .filter(c -> c != null && "group".equals(c.getControlType()))
                .filter(c -> !isTopLevelDataGroup(c))
                .count();

        long rootDataGroupCount = bodyControls.stream()
                .filter(this::isTopLevelDataGroup)
                .count();

        // A root /data group is meaningful when it is the form's only group, but
        // acts as a wrapper when explicit nested groups are present.
        int groupCount = (int) (userDefinedGroupCount > 0
                ? userDefinedGroupCount
                : rootDataGroupCount);

        int mediaFieldCount = (int) fields.stream()
                .filter(FieldInfo::isMedia)
                .count();

        return FormMetadata.builder()
                .formId(formLoader.getFormId(formDef))
                .title(formDef.getTitle())
                .version(formLoader.getFormVersion(formDef))
                .languages(itext.getLanguagesAsList())
                .defaultLanguage(itext.getDefaultLanguage())
                .fieldCount(fields.size())
                .choiceListCount(choices.getChoiceListCount())
                .repeatCount(repeatCount)
                .groupCount(groupCount)
                .mediaFieldCount(mediaFieldCount)
                .fields(fields)
                .choiceLists(choiceLists)
                .build();
    }

    private FieldInfo bindToFieldInfo(
            XFormBindExtractor.BindInfo bind,
            XFormBodyExtractor.ControlInfo control,
            String mediaType) {
        if (bind == null) {
            return null;
        }

        return FieldInfo.builder()
                .xpath(bind.getXpathReference())
                .name(firstNonBlank(
                        bind.getNodeName(),
                        control == null ? null : control.getNodeName()))
                .type(firstNonBlank(
                        bind.getDataType(),
                        control == null ? null : mapControlType(control.getControlType())))
                .label(control == null ? null : control.getLabel())
                .hint(control == null ? null : control.getHint())
                .appearance(control == null ? null : control.getAppearance())
                .relevant(bind.getRelevant())
                .calculate(bind.getCalculate())
                .constraint(bind.getConstraint())
                .constraintMessage(bind.getConstraintMessage())
                .required(isActiveExpression(bind.getRequired()))
                .readOnly(isActiveExpression(bind.getReadOnly()))
                .calculated(!isBlank(bind.getCalculate()))
                .selectionMode(control == null ? null : control.getSelectionMode())
                .mediaType(mediaType)
                .choices(control == null
                        ? List.of()
                        : safeList(control.getChoices()).stream()
                                .map(c -> ChoiceInfo.builder()
                                        .value(c.getValue())
                                        .label(c.getLabel())
                                        .build())
                                .collect(Collectors.toList()))
                .build();
    }

    /**
     * Fallback mapping for controls that do not have an explicit bind data type.
     * Bind data types remain authoritative whenever they are available.
     */
    private String mapControlType(String controlType) {
        if (controlType == null) return null;
        return switch (controlType.toLowerCase()) {
            case "text" -> "string";
            case "int" -> "int";
            case "decimal" -> "decimal";
            case "boolean" -> "boolean";
            case "date" -> "date";
            case "time" -> "time";
            case "datetime" -> "dateTime";
            case "select1" -> "select1";
            case "select" -> "select";
            case "upload" -> "binary";
            case "output" -> "calculate";
            case "trigger" -> "trigger";
            case "group" -> "group";
            case "repeat" -> "repeat";
            case "input" -> "string";
            default -> controlType;
        };
    }

    /**
     * Checks if a control is the top-level data group (the root /data group).
     * This group is not considered a user-defined group for counting purposes.
     */
    private boolean isTopLevelDataGroup(XFormBodyExtractor.ControlInfo control) {
        if (control == null) return false;
        return "group".equals(control.getControlType())
                && "/data".equals(normalizeReference(control.getXpathReference()));
    }

    private ChoiceListInfo choiceListToChoiceListInfo(XFormChoiceExtractor.ChoiceList list) {
        if (list == null) return null;
        return ChoiceListInfo.builder()
                .listName(list.getListName())
                .xpath(list.getXpathReference())
                .selectType(list.getSelectType())
                .choices(safeList(list.getChoices()).stream()
                        .map(c -> ChoiceInfo.builder()
                                .value(c.getValue())
                                .label(c.getLabel())
                                .image(c.getImage())
                                .audio(c.getAudio())
                                .video(c.getVideo())
                                .build())
                        .collect(Collectors.toList()))
                .build();
    }

    public MediaExtractionResult extractMediaReferences(FormDef formDef) {
        return extractMediaReferences(
                formDef,
                bindExtractor.extractBinds(formDef),
                choiceExtractor.extractChoices(formDef),
                itextExtractor.extractIText(formDef));
    }

    private MediaExtractionResult extractMediaReferences(
            FormDef formDef,
            XFormBindExtractor.BindExtractionResult binds,
            XFormChoiceExtractor.ChoiceExtractionResult choices,
            XFormITextExtractor.ITextExtractionResult itext) {
        List<MediaReference> mediaReferences = new ArrayList<>();
        Map<String, List<MediaReference>> mediaByType = new HashMap<>();

        for (XFormITextExtractor.ITextInfo text : safeList(itext.getTexts())) {
            addMedia(mediaReferences, mediaByType, MediaReference.builder()
                    .source("itext").textId(text.getId()).language(text.getLanguage())
                    .mediaType("image").filename(text.getImage()).build());
            addMedia(mediaReferences, mediaByType, MediaReference.builder()
                    .source("itext").textId(text.getId()).language(text.getLanguage())
                    .mediaType("audio").filename(text.getAudio()).build());
            addMedia(mediaReferences, mediaByType, MediaReference.builder()
                    .source("itext").textId(text.getId()).language(text.getLanguage())
                    .mediaType("video").filename(text.getVideo()).build());
        }

        for (XFormChoiceExtractor.ChoiceList list : safeList(choices.getChoiceLists())) {
            for (XFormChoiceExtractor.Choice choice : safeList(list.getChoices())) {
                addMedia(mediaReferences, mediaByType, MediaReference.builder()
                        .source("choice").nodeName(list.getNodeName())
                        .xpathReference(list.getXpathReference()).choiceValue(choice.getValue())
                        .mediaType("image").filename(choice.getImage()).build());
                addMedia(mediaReferences, mediaByType, MediaReference.builder()
                        .source("choice").nodeName(list.getNodeName())
                        .xpathReference(list.getXpathReference()).choiceValue(choice.getValue())
                        .mediaType("audio").filename(choice.getAudio()).build());
                addMedia(mediaReferences, mediaByType, MediaReference.builder()
                        .source("choice").nodeName(list.getNodeName())
                        .xpathReference(list.getXpathReference()).choiceValue(choice.getValue())
                        .mediaType("video").filename(choice.getVideo()).build());
            }
        }

        for (XFormBindExtractor.BindInfo bind : safeList(binds.getBinds())) {
            if (bind.isAttachment()) {
                String mediaType = firstNonBlank(bind.getAttachmentMediaType(), "binary");
                addMedia(mediaReferences, mediaByType, MediaReference.builder()
                        .source("bind").nodeName(bind.getNodeName())
                        .xpathReference(bind.getXpathReference()).mediaType(mediaType).build());
            }
        }

        return MediaExtractionResult.builder()
                .mediaReferences(mediaReferences)
                .mediaByType(mediaByType)
                .build();
    }

    private void addMedia(List<MediaReference> references,
                          Map<String, List<MediaReference>> byType,
                          MediaReference reference) {
        // A bind attachment can be useful without a literal file name. IText and
        // choice media are only useful when they have a non-blank file reference.
        if (reference == null || isBlank(reference.getMediaType())) {
            return;
        }
        if (!"bind".equals(reference.getSource()) && isBlank(reference.getFilename())) {
            return;
        }
        references.add(reference);
        byType.computeIfAbsent(reference.getMediaType(), ignored -> new ArrayList<>()).add(reference);
    }

    public ExternalDatasetExtractionResult extractExternalDatasetReferences(FormDef formDef) {
        return extractExternalDatasetReferences(choiceExtractor.extractChoices(formDef));
    }

    /**
     * Checks if a form has any media fields (image, audio, video).
     */
    public boolean hasMediaFields(FormDef formDef) {
        if (formDef == null) {
            return false;
        }
        MediaExtractionResult media = extractMediaReferences(formDef);
        return media.getMediaCount() > 0;
    }

    /**
     * Gets all available languages from a form definition.
     */
    public List<String> getLanguages(FormDef formDef) {
        if (formDef == null) {
            return List.of();
        }
        XFormITextExtractor.ITextExtractionResult itext = itextExtractor.extractIText(formDef);
        return itext.getLanguagesAsList();
    }

    /**
     * Gets the default language from a form definition.
     */
    public String getDefaultLanguage(FormDef formDef) {
        if (formDef == null) {
            return null;
        }
        XFormITextExtractor.ITextExtractionResult itext = itextExtractor.extractIText(formDef);
        return itext.getDefaultLanguage();
    }

    /**
     * Gets the form title from a form definition.
     */
    public String getFormTitle(FormDef formDef) {
        if (formDef == null) {
            return null;
        }
        return formDef.getTitle();
    }

    /**
     * Gets the form ID from a form definition.
     */
    public String getFormId(FormDef formDef) {
        if (formDef == null) {
            return null;
        }
        return formLoader.getFormId(formDef);
    }

    /**
     * Gets the form version from a form definition.
     */
    public String getFormVersion(FormDef formDef) {
        if (formDef == null) {
            return null;
        }
        return formLoader.getFormVersion(formDef);
    }

    /**
     * Gets the total field count from a form definition.
     */
    public int getFieldCount(FormDef formDef) {
        if (formDef == null) {
            return 0;
        }

        XFormBindExtractor.BindExtractionResult binds = bindExtractor.extractBinds(formDef);
        XFormBodyExtractor.BodyExtractionResult body = bodyExtractor.extractBody(formDef);
        Set<String> structuralXpaths = new java.util.HashSet<>(
                structuralControlXpaths(safeList(body.getControls())));
        structuralXpaths.addAll(structuralInstanceXpaths(formDef));
        Set<String> explicitBindXpaths = explicitBindXpaths(formDef);

        return uniqueDataFieldBinds(
                safeList(binds.getBinds()),
                explicitBindXpaths,
                structuralXpaths).size();
    }

    /**
     * Gets the media field count from a form definition.
     */
    public int getMediaFieldCount(FormDef formDef) {
        if (formDef == null) {
            return 0;
        }
        MediaExtractionResult media = extractMediaReferences(formDef);
        return media.getMediaCount();
    }

    private ExternalDatasetExtractionResult extractExternalDatasetReferences(
            XFormChoiceExtractor.ChoiceExtractionResult choices) {
        List<ExternalDatasetReference> datasets = new ArrayList<>();
        Map<String, List<ExternalDatasetReference>> datasetsByType = new HashMap<>();

        for (XFormChoiceExtractor.ChoiceList list : safeList(choices.getChoiceLists())) {
            XFormChoiceExtractor.ItemsetInfo itemset = list.getItemset();
            if (itemset == null) {
                continue;
            }
            String source = detectDatasetSource(itemset.getNodeset());
            ExternalDatasetReference reference = ExternalDatasetReference.builder()
                    .source(source)
                    .nodeName(list.getNodeName())
                    .xpathReference(list.getXpathReference())
                    .nodeset(itemset.getNodeset())
                    .valueRef(itemset.getValueRef())
                    .labelRef(itemset.getLabelRef())
                    .imageRef(itemset.getImageRef())
                    .audioRef(itemset.getAudioRef())
                    .videoRef(itemset.getVideoRef())
                    .build();
            datasets.add(reference);
            datasetsByType.computeIfAbsent(source, ignored -> new ArrayList<>()).add(reference);
        }

        return ExternalDatasetExtractionResult.builder()
                .datasets(datasets)
                .datasetsByType(datasetsByType)
                .build();
    }

    public EntityExtractionResult extractEntityReferences(FormDef formDef) {
        return extractEntityReferences(bindExtractor.extractBinds(formDef), choiceExtractor.extractChoices(formDef));
    }

    private EntityExtractionResult extractEntityReferences(
            XFormBindExtractor.BindExtractionResult binds,
            XFormChoiceExtractor.ChoiceExtractionResult choices) {
        List<EntityReference> entities = new ArrayList<>();
        Map<String, List<EntityReference>> entitiesByType = new HashMap<>();

        for (XFormBindExtractor.BindInfo bind : safeList(binds.getBinds())) {
            addEntityExpression(entities, entitiesByType, "calculate", bind, bind.getCalculate());
            addEntityExpression(entities, entitiesByType, "relevant", bind, bind.getRelevant());
            addEntityExpression(entities, entitiesByType, "constraint", bind, bind.getConstraint());
            addEntityExpression(entities, entitiesByType, "required", bind, bind.getRequired());
            addEntityExpression(entities, entitiesByType, "readonly", bind, bind.getReadOnly());
        }

        for (XFormChoiceExtractor.ChoiceList list : safeList(choices.getChoiceLists())) {
            if (list.getItemset() == null || isBlank(list.getItemset().getNodeset())) {
                continue;
            }
            String expression = list.getItemset().getNodeset();
            if (containsExternalLookup(expression)) {
                EntityReference entity = EntityReference.builder()
                        .source("itemset")
                        .nodeName(list.getNodeName())
                        .xpathReference(list.getXpathReference())
                        .expression(expression)
                        .type(expression.contains("instance(") ? "entity-list" : detectEntityType(expression))
                        .build();
                entities.add(entity);
                entitiesByType.computeIfAbsent(entity.getType(), ignored -> new ArrayList<>()).add(entity);
            }
        }

        return EntityExtractionResult.builder()
                .entities(entities)
                .entitiesByType(entitiesByType)
                .build();
    }

    private void addEntityExpression(List<EntityReference> entities,
                                     Map<String, List<EntityReference>> byType,
                                     String source,
                                     XFormBindExtractor.BindInfo bind,
                                     String expression) {
        if (!containsExternalLookup(expression)) {
            return;
        }
        EntityReference reference = EntityReference.builder()
                .source(source)
                .nodeName(bind.getNodeName())
                .xpathReference(bind.getXpathReference())
                .expression(expression)
                .type(detectEntityType(expression))
                .build();
        entities.add(reference);
        byType.computeIfAbsent(reference.getType(), ignored -> new ArrayList<>()).add(reference);
    }

    private boolean containsExternalLookup(String expression) {
        return !isBlank(expression)
                && (expression.contains("instance(") || expression.contains("pulldata("));
    }

    private String detectEntityType(String expression) {
        if (expression != null && expression.contains("pulldata(")) return "pulldata";
        if (expression != null && expression.contains("instance(")) return "instance-lookup";
        return "unknown";
    }

    private String detectDatasetSource(String nodeset) {
        if (isBlank(nodeset)) return "itemset";
        String lower = nodeset.toLowerCase();
        if (lower.contains("pulldata(")) return "pulldata";
        if (lower.contains(".csv") || lower.contains("csv-external")) return "csv";
        if (lower.contains("instance(")) return "itemset";
        return "itemset";
    }

    public void storeMetadata(FormVersion formVersion, FormMetadataResult metadata) {
        if (formVersion == null) {
            throw new IllegalArgumentException("FormVersion cannot be null");
        }
        if (metadata == null) {
            throw new IllegalArgumentException("Form metadata cannot be null");
        }

        Map<String, Object> metadataMap = new HashMap<>();
        metadataMap.put("formId", metadata.getFormId());
        metadataMap.put("title", metadata.getTitle());
        metadataMap.put("version", metadata.getVersion());
        metadataMap.put("xmlns", metadata.getXmlns());
        metadataMap.put("instanceRootNode", metadata.getInstanceRootNode());
        metadataMap.put("formHash", metadata.getFormHash());
        metadataMap.put("binds", safeList(metadata.getBinds().getBinds()).stream()
                .map(this::bindInfoToMap).collect(Collectors.toList()));
        metadataMap.put("controls", safeList(metadata.getBody().getControls()).stream()
                .map(this::controlInfoToMap).collect(Collectors.toList()));
        metadataMap.put("choiceLists", safeList(metadata.getChoices().getChoiceLists()).stream()
                .map(this::choiceListInfoToMap).collect(Collectors.toList()));
        metadataMap.put("itext", safeList(metadata.getItext().getTexts()).stream()
                .map(this::itextInfoToMap).collect(Collectors.toList()));
        metadataMap.put("media", safeList(metadata.getMedia().getMediaReferences()).stream()
                .map(this::mediaReferenceToMap).collect(Collectors.toList()));
        metadataMap.put("externalDatasets", safeList(metadata.getExternalDatasets().getDatasets()).stream()
                .map(this::externalDatasetReferenceToMap).collect(Collectors.toList()));
        metadataMap.put("entities", safeList(metadata.getEntities().getEntities()).stream()
                .map(this::entityReferenceToMap).collect(Collectors.toList()));
        metadataMap.put("extractedAt", Instant.now().toString());
        formVersion.setMetadata(metadataMap);
    }

    private Map<String, Object> bindInfoToMap(XFormBindExtractor.BindInfo bind) {
        Map<String, Object> map = new HashMap<>();
        map.put("nodePath", bind.getNodePath());
        map.put("nodeName", bind.getNodeName());
        map.put("dataType", bind.getDataType());
        map.put("constraint", bind.getConstraint());
        map.put("constraintMessage", bind.getConstraintMessage());
        map.put("relevant", bind.getRelevant());
        map.put("calculate", bind.getCalculate());
        map.put("required", bind.getRequired());
        map.put("readOnly", bind.getReadOnly());
        map.put("attachment", bind.isAttachment());
        map.put("attachmentMediaType", bind.getAttachmentMediaType());
        map.put("validation", bind.getValidation());
        map.put("validationMessage", bind.getValidationMessage());
        map.put("xpathReference", bind.getXpathReference());
        return map;
    }

    private Map<String, Object> bindInfoToMap(XFormBodyExtractor.BindInfo bind) {
        Map<String, Object> map = new HashMap<>();
        map.put("dataType", bind.getDataType());
        map.put("constraint", bind.getConstraint());
        map.put("constraintMessage", bind.getConstraintMessage());
        map.put("relevant", bind.getRelevant());
        map.put("calculate", bind.getCalculate());
        map.put("required", bind.getRequired());
        map.put("readOnly", bind.getReadOnly());
        map.put("attachment", bind.isAttachment());
        map.put("attachmentMediaType", bind.getAttachmentMediaType());
        map.put("validation", bind.getValidation());
        map.put("validationMessage", bind.getValidationMessage());
        return map;
    }

    private Map<String, Object> controlInfoToMap(XFormBodyExtractor.ControlInfo control) {
        Map<String, Object> map = new HashMap<>();
        map.put("nodeName", control.getNodeName());
        map.put("xpathReference", control.getXpathReference());
        map.put("controlType", control.getControlType());
        map.put("depth", control.getDepth());
        map.put("parentPath", control.getParentPath());
        map.put("label", control.getLabel());
        map.put("hint", control.getHint());
        map.put("appearance", control.getAppearance());
        map.put("inputMode", control.getInputMode());
        map.put("mediaType", control.getMediaType());
        map.put("rangeStart", control.getRangeStart());
        map.put("rangeEnd", control.getRangeEnd());
        map.put("rangeStep", control.getRangeStep());
        map.put("selectionMode", control.getSelectionMode());
        if (control.getBind() != null) map.put("bind", bindInfoToMap(control.getBind()));
        if (control.getChoices() != null) {
            map.put("choices", control.getChoices().stream().map(choice -> {
                Map<String, Object> value = new HashMap<>();
                value.put("value", choice.getValue());
                value.put("label", choice.getLabel());
                return value;
            }).collect(Collectors.toList()));
        }
        if (control.getChildren() != null) {
            map.put("children", control.getChildren().stream()
                    .map(this::controlInfoToMap).collect(Collectors.toList()));
        }
        return map;
    }

    private Map<String, Object> choiceListInfoToMap(XFormChoiceExtractor.ChoiceList list) {
        Map<String, Object> map = new HashMap<>();
        map.put("listName", list.getListName());
        map.put("nodeName", list.getNodeName());
        map.put("xpathReference", list.getXpathReference());
        map.put("selectionMode", list.getSelectionMode());
        map.put("dynamic", list.isDynamic());
        map.put("choices", safeList(list.getChoices()).stream().map(choice -> {
            Map<String, Object> value = new HashMap<>();
            value.put("value", choice.getValue());
            value.put("label", choice.getLabel());
            value.put("image", choice.getImage());
            value.put("audio", choice.getAudio());
            value.put("video", choice.getVideo());
            return value;
        }).collect(Collectors.toList()));
        if (list.getItemset() != null) {
            Map<String, Object> itemset = new HashMap<>();
            itemset.put("nodeset", list.getItemset().getNodeset());
            itemset.put("valueRef", list.getItemset().getValueRef());
            itemset.put("labelRef", list.getItemset().getLabelRef());
            itemset.put("imageRef", list.getItemset().getImageRef());
            itemset.put("audioRef", list.getItemset().getAudioRef());
            itemset.put("videoRef", list.getItemset().getVideoRef());
            map.put("itemset", itemset);
        }
        return map;
    }

    private Map<String, Object> itextInfoToMap(XFormITextExtractor.ITextInfo text) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", text.getId());
        map.put("language", text.getLanguage());
        map.put("label", text.getLabel());
        map.put("hint", text.getHint());
        map.put("image", text.getImage());
        map.put("audio", text.getAudio());
        map.put("video", text.getVideo());
        map.put("guidanceHint", text.getGuidanceHint());
        return map;
    }

    private Map<String, Object> mediaReferenceToMap(MediaReference media) {
        Map<String, Object> map = new HashMap<>();
        map.put("source", media.getSource());
        map.put("textId", media.getTextId());
        map.put("language", media.getLanguage());
        map.put("nodeName", media.getNodeName());
        map.put("xpathReference", media.getXpathReference());
        map.put("choiceValue", media.getChoiceValue());
        map.put("mediaType", media.getMediaType());
        map.put("filename", media.getFilename());
        return map;
    }

    private Map<String, Object> externalDatasetReferenceToMap(ExternalDatasetReference dataset) {
        Map<String, Object> map = new HashMap<>();
        map.put("source", dataset.getSource());
        map.put("nodeName", dataset.getNodeName());
        map.put("xpathReference", dataset.getXpathReference());
        map.put("nodeset", dataset.getNodeset());
        map.put("valueRef", dataset.getValueRef());
        map.put("labelRef", dataset.getLabelRef());
        map.put("imageRef", dataset.getImageRef());
        map.put("audioRef", dataset.getAudioRef());
        map.put("videoRef", dataset.getVideoRef());
        return map;
    }

    private Map<String, Object> entityReferenceToMap(EntityReference entity) {
        Map<String, Object> map = new HashMap<>();
        map.put("source", entity.getSource());
        map.put("nodeName", entity.getNodeName());
        map.put("xpathReference", entity.getXpathReference());
        map.put("expression", entity.getExpression());
        map.put("type", entity.getType());
        return map;
    }

    /**
     * Returns the XPath references of bind elements explicitly declared in the
     * source XForm. JavaRosa can expose additional instance-tree container nodes
     * as BindInfo entries even when the XML does not contain a corresponding
     * <bind>. Those structural nodes must not inflate field counts.
     */
    private Set<String> explicitBindXpaths(FormDef formDef) {
        if (formDef == null) {
            return Set.of();
        }

        Map<String, Map<String, String>> bindAttributes = formLoader.extractBindAttributes(formDef);
        if (bindAttributes == null || bindAttributes.isEmpty()) {
            return Set.of();
        }

        return bindAttributes.keySet().stream()
                .map(FormMetadataExtractor::canonicalReference)
                .filter(reference -> !isBlank(reference))
                .collect(Collectors.toSet());
    }

    /**
     * Explicit XForm binds are authoritative whenever they are available.
     * This preserves hidden/calculated fields while excluding JavaRosa-created
     * structural container binds. For bind-less forms, retain the previous
     * group/repeat structural filtering behavior as a fallback.
     */
    /**
     * JavaRosa repeat trees may expose both template and concrete repeat leaf
     * nodes. After XPath normalization those entries represent the same logical
     * XForm field, so retain only the first BindInfo for each canonical path.
     */
    private List<XFormBindExtractor.BindInfo> uniqueDataFieldBinds(
            List<XFormBindExtractor.BindInfo> binds,
            Set<String> explicitBindXpaths,
            Set<String> structuralXpaths) {

        Map<String, XFormBindExtractor.BindInfo> unique = new java.util.LinkedHashMap<>();

        for (XFormBindExtractor.BindInfo bind : safeList(binds)) {
            if (!isDataFieldBind(bind, explicitBindXpaths, structuralXpaths)) {
                continue;
            }

            String reference = canonicalBindReference(bind);
            if (reference != null) {
                unique.putIfAbsent(reference, bind);
            }
        }

        return new ArrayList<>(unique.values());
    }

    private boolean isDataFieldBind(
            XFormBindExtractor.BindInfo bind,
            Set<String> explicitBindXpaths,
            Set<String> structuralControlXpaths) {

        if (bind == null) {
            return false;
        }

        String reference = canonicalBindReference(bind);
        if (reference == null) {
            return false;
        }

        if (structuralControlXpaths != null && structuralControlXpaths.contains(reference)) {
            return false;
        }

        if (explicitBindXpaths != null && !explicitBindXpaths.isEmpty()) {
            return explicitBindXpaths.contains(reference);
        }

        return true;
    }

    /**
     * Collects non-leaf nodes from JavaRosa's main instance tree. These are
     * structural containers (root/group/repeat/intermediate wrappers), not
     * answer fields, even when JavaRosa exposes datatype/bind state for them.
     */
    private Set<String> structuralInstanceXpaths(FormDef formDef) {
        Set<String> references = new java.util.HashSet<>();
        if (formDef == null || formDef.getMainInstance() == null
                || formDef.getMainInstance().getRoot() == null) {
            return references;
        }

        collectStructuralInstanceXpaths(formDef.getMainInstance().getRoot(), references);
        return references;
    }

    private void collectStructuralInstanceXpaths(TreeElement element, Set<String> references) {
        if (element == null) {
            return;
        }

        if (!element.isLeaf() && element.getRef() != null) {
            String reference = canonicalReference(element.getRef().toString());
            if (!isBlank(reference)) {
                references.add(reference);
            }
        }

        for (int i = 0; i < element.getNumChildren(); i++) {
            collectStructuralInstanceXpaths(element.getChildAt(i), references);
        }
    }

    private Set<String> structuralControlXpaths(List<XFormBodyExtractor.ControlInfo> controls) {
        return safeList(controls).stream()
                .filter(control -> control != null
                        && ("group".equals(control.getControlType())
                        || "repeat".equals(control.getControlType())))
                .map(XFormBodyExtractor.ControlInfo::getXpathReference)
                .map(FormMetadataExtractor::canonicalReference)
                .filter(reference -> !isBlank(reference))
                .collect(Collectors.toSet());
    }

    private boolean isStructuralContainerBind(
            XFormBindExtractor.BindInfo bind,
            Set<String> structuralControlXpaths) {

        if (bind == null || structuralControlXpaths == null || structuralControlXpaths.isEmpty()) {
            return false;
        }

        String reference = normalizedBindReference(bind);
        return reference != null && structuralControlXpaths.contains(reference);
    }

    private static String normalizedBindReference(XFormBindExtractor.BindInfo bind) {
        return canonicalBindReference(bind);
    }

    private static String canonicalBindReference(XFormBindExtractor.BindInfo bind) {
        if (bind == null) {
            return null;
        }
        return canonicalReference(firstNonBlank(bind.getXpathReference(), bind.getNodePath()));
    }

    private static String canonicalReference(String reference) {
        String normalized = normalizeReference(reference);
        if (isBlank(normalized)) {
            return null;
        }
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }

    private static String normalizeReference(String reference) {
        return reference == null ? null : reference.trim()
                .replaceAll("\\[(?:-?\\d+|@template)\\]", "")
                .replaceAll("/{2,}", "/");
    }

    private static boolean isActiveExpression(String value) {
        if (isBlank(value)) {
            return false;
        }
        String normalized = value.trim().toLowerCase();
        return !"false".equals(normalized)
                && !"false()".equals(normalized)
                && !"0".equals(normalized);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (!isBlank(value)) return value;
        return null;
    }

    private static <T> List<T> safeList(List<T> list) {
        return list == null ? List.of() : list;
    }

    @lombok.Data
    @lombok.Builder
    public static class FormMetadataResult {
        private String formId;
        private String title;
        private String version;
        private String xmlns;
        private String instanceRootNode;
        private String formHash;
        private XFormBindExtractor.BindExtractionResult binds;
        private XFormBodyExtractor.BodyExtractionResult body;
        private XFormChoiceExtractor.ChoiceExtractionResult choices;
        private XFormITextExtractor.ITextExtractionResult itext;
        private MediaExtractionResult media;
        private ExternalDatasetExtractionResult externalDatasets;
        private EntityExtractionResult entities;
    }

    @lombok.Data
    @lombok.Builder
    public static class MediaExtractionResult {
        private List<MediaReference> mediaReferences;
        private Map<String, List<MediaReference>> mediaByType;
        public int getMediaCount() { return mediaReferences != null ? mediaReferences.size() : 0; }
    }

    @lombok.Data
    @lombok.Builder
    public static class MediaReference {
        private String source;
        private String textId;
        private String language;
        private String nodeName;
        private String xpathReference;
        private String choiceValue;
        private String mediaType;
        private String filename;
    }

    @lombok.Data
    @lombok.Builder
    public static class ExternalDatasetExtractionResult {
        private List<ExternalDatasetReference> datasets;
        private Map<String, List<ExternalDatasetReference>> datasetsByType;
        public int getDatasetCount() { return datasets != null ? datasets.size() : 0; }
    }

    @lombok.Data
    @lombok.Builder
    public static class ExternalDatasetReference {
        private String source;
        private String nodeName;
        private String xpathReference;
        private String nodeset;
        private String valueRef;
        private String labelRef;
        private String imageRef;
        private String audioRef;
        private String videoRef;
    }

    @lombok.Data
    @lombok.Builder
    public static class EntityExtractionResult {
        private List<EntityReference> entities;
        private Map<String, List<EntityReference>> entitiesByType;
        public int getEntityCount() { return entities != null ? entities.size() : 0; }
    }

    @lombok.Data
    @lombok.Builder
    public static class EntityReference {
        private String source;
        private String nodeName;
        private String xpathReference;
        private String expression;
        private String type;
    }

    /**
     * Simplified metadata result for backward compatibility with tests.
     */
    @lombok.Data
    @lombok.Builder
    public static class FormMetadata {
        private String formId;
        private String title;
        private String version;
        private List<String> languages;
        private String defaultLanguage;
        private int fieldCount;
        private int choiceListCount;
        private int repeatCount;
        private int groupCount;
        private int mediaFieldCount;
        private List<FieldInfo> fields;
        private List<ChoiceListInfo> choiceLists;

        public int getLanguageCount() { return languages != null ? languages.size() : 0; }
        public int getFieldCount() { return fields != null ? fields.size() : 0; }
        public int getChoiceListCount() { return choiceLists != null ? choiceLists.size() : 0; }
        public int getGroupCount() { return groupCount; }
        public int getMediaFieldCount() { return mediaFieldCount; }
    }

    @lombok.Data
    @lombok.Builder
    public static class FieldInfo {
        private String xpath;
        private String name;
        private String type;
        private String label;
        private String hint;
        private String appearance;
        private String relevant;
        private String calculate;
        private String constraint;
        private String constraintMessage;
        private boolean required;
        private boolean readOnly;
        private boolean calculated;
        private String selectionMode;
        private String mediaType;
        private List<ChoiceInfo> choices;

        public boolean isCalculated() { return !isBlank(calculate); }
        public boolean isMedia() { return !isBlank(mediaType); }
        public String getMediaType() { return mediaType; }
    }

    @lombok.Data
    @lombok.Builder
    public static class ChoiceInfo {
        private String value;
        private String label;
        private String image;
        private String audio;
        private String video;
    }

    @lombok.Data
    @lombok.Builder
    public static class ChoiceListInfo {
        private String listName;
        private String xpath;
        private String selectType;
        private List<ChoiceInfo> choices;

        public int getChoiceCount() { return choices != null ? choices.size() : 0; }
    }
}
