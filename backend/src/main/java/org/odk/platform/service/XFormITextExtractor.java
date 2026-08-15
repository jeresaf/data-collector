package org.odk.platform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javarosa.core.model.FormDef;
import org.javarosa.core.model.IFormElement;
import org.javarosa.core.model.QuestionDef;
import org.javarosa.core.services.locale.Localizer;
import org.javarosa.core.util.OrderedMap;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class XFormITextExtractor {

    private final JavaRosaFormLoader formLoader;

    public ITextExtractionResult extractIText(String xmlContent) {
        return extractIText(formLoader.loadFormDef(xmlContent));
    }

    /**
     * JavaRosa 4.3.2 does not parse the <itext> element, so the Localizer is never populated.
     * We extract translations directly from the raw XML using DOM parsing.
     */
    public ITextExtractionResult extractIText(FormDef formDef) {
        if (formDef == null) {
            return ITextExtractionResult.empty();
        }

        String xmlContent = formLoader.getXmlContent(formDef);
        if (xmlContent == null || xmlContent.isBlank()) {
            return ITextExtractionResult.empty();
        }

        // Parse itext from raw XML
        Map<String, Map<String, Map<String, String>>> itextData = parseITextFromXml(xmlContent);
        if (itextData.isEmpty()) {
            return ITextExtractionResult.empty();
        }

        // Determine languages and default language
        Set<String> languages = itextData.keySet().stream()
                .filter(k -> !"__default__".equals(k))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        String defaultLanguage = determineDefaultLanguage(itextData, languages);

        // Collect hint links from form structure (using JavaRosa model)
        Map<String, String> hintIdsByLabelId = new HashMap<>();
        collectHintLinks(formDef.getChildren(), hintIdsByLabelId);

        List<ITextInfo> texts = new ArrayList<>();
        Map<String, ITextInfo> textsById = new HashMap<>();
        Map<String, List<ITextInfo>> textsByLanguage = new LinkedHashMap<>();

        for (String locale : languages) {
            Map<String, Map<String, String>> localeData = itextData.get(locale);
            List<ITextInfo> languageTexts = new ArrayList<>();

            if (localeData != null && !localeData.isEmpty()) {
                Set<String> baseIds = new LinkedHashSet<>();
                for (String key : localeData.keySet()) {
                    baseIds.add(baseId(key));
                }

                for (String textId : baseIds) {
                    List<ITextInfo> textInfos = extractITextItems(localeData, hintIdsByLabelId, textId, locale);
                    for (ITextInfo text : textInfos) {
                        texts.add(text);
                        languageTexts.add(text);
                        textsById.put(text.getId() + ":" + locale, text);
                    }
                }
            }
            textsByLanguage.put(locale, languageTexts);
        }

        String formId = formDef.getName();
        log.debug("Extracted {} IText items in {} languages from form: {}",
                texts.size(), languages.size(), formId);

        return ITextExtractionResult.builder()
                .formId(formId)
                .texts(texts)
                .textsById(textsById)
                .textsByLanguage(textsByLanguage)
                .languages(languages.toArray(new String[0]))
                .defaultLanguage(defaultLanguage)
                .build();
    }

    /**
     * Parses the <itext> element from raw XForm XML.
     * Returns a map of locale -> textId -> field -> value.
     */
    private Map<String, Map<String, Map<String, String>>> parseITextFromXml(String xmlContent) {
        Map<String, Map<String, Map<String, String>>> result = new LinkedHashMap<>();
        String defaultLanguage = null;

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            setFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            setFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
            setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            setAttribute(factory, XMLConstants.ACCESS_EXTERNAL_DTD, "");
            setAttribute(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

            Document document = factory.newDocumentBuilder().parse(
                    new ByteArrayInputStream(xmlContent.getBytes(StandardCharsets.UTF_8)));

            Element root = document.getDocumentElement();
            Element itextElement = findFirstDescendantByLocalName(root, "itext");
            if (itextElement == null) {
                return result;
            }

            // Process each translation element
            NodeList translationNodes = itextElement.getChildNodes();
            for (int i = 0; i < translationNodes.getLength(); i++) {
                Node node = translationNodes.item(i);
                if (node instanceof Element) {
                    Element translation = (Element) node;
                    String localName = translation.getLocalName() == null ? translation.getNodeName() : translation.getLocalName();
                    if ("translation".equals(localName)) {
                        String lang = translation.getAttribute("lang");
                        String isDefault = translation.getAttribute("default");
                        if (lang == null || lang.isBlank()) {
                            continue;
                        }

                        Map<String, Map<String, String>> localeData = new LinkedHashMap<>();
                        NodeList textNodes = translation.getChildNodes();
                        for (int j = 0; j < textNodes.getLength(); j++) {
                            Node textNode = textNodes.item(j);
                            if (textNode instanceof Element) {
                                Element textElement = (Element) textNode;
                                String textLocalName = textElement.getLocalName() == null ? textElement.getNodeName() : textElement.getLocalName();
                                if ("text".equals(textLocalName)) {
                                    String textId = textElement.getAttribute("id");
                                    if (textId == null || textId.isBlank()) {
                                        continue;
                                    }

                                    Map<String, String> fields = new LinkedHashMap<>();
                                    NodeList valueNodes = textElement.getChildNodes();
                                    for (int k = 0; k < valueNodes.getLength(); k++) {
                                        Node valueNode = valueNodes.item(k);
                                        if (valueNode instanceof Element) {
                                            Element valueElement = (Element) valueNode;
                                            String valueLocalName = valueElement.getLocalName() == null ? valueElement.getNodeName() : valueElement.getLocalName();
                                            if ("value".equals(valueLocalName)) {
                                                String form = valueElement.getAttribute("form");
                                                String textContent = valueElement.getTextContent();
                                                if (form == null || form.isBlank()) {
                                                    form = "label"; // default form
                                                }
                                                fields.put(form, textContent);
                                            }
                                        }
                                    }
                                    if (!fields.isEmpty()) {
                                        localeData.put(textId, fields);
                                    }
                                }
                            }
                        }

                        if (!localeData.isEmpty()) {
                            result.put(lang, localeData);
                            if ("true".equals(isDefault)) {
                                defaultLanguage = lang;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse IText from XML: {}", e.getMessage());
        }

        // Store default language in a way we can retrieve it
        if (defaultLanguage != null) {
            // We'll use a special key to store the default language
            // This is a bit of a hack but works for our use case
            Map<String, Map<String, String>> defaultMarker = new LinkedHashMap<>();
            Map<String, String> marker = new LinkedHashMap<>();
            marker.put("__default__", defaultLanguage);
            defaultMarker.put("__default__", marker);
            result.put("__default__", defaultMarker);
        }

        return result;
    }

    private String determineDefaultLanguage(Map<String, Map<String, Map<String, String>>> itextData, Set<String> languages) {
        // First, check if we stored a default language
        Map<String, Map<String, String>> defaultMarker = itextData.get("__default__");
        if (defaultMarker != null) {
            Map<String, String> marker = defaultMarker.get("__default__");
            if (marker != null) {
                String defaultLang = marker.get("__default__");
                if (defaultLang != null && languages.contains(defaultLang)) {
                    return defaultLang;
                }
            }
        }
        // Fall back to first language
        return languages.isEmpty() ? null : languages.iterator().next();
    }

    private ITextInfo extractITextItem(Map<String, Map<String, String>> localeData,
                                       Map<String, String> hintIdsByLabelId,
                                       String textId,
                                       String locale) {
        Map<String, String> fields = localeData.get(textId);
        if (fields == null) {
            return null;
        }

        // For each field (label, hint, guidance, etc.), create a separate ITextInfo
        // This preserves the full textId as the key
        List<ITextInfo> infos = new ArrayList<>();
        
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            String fieldName = entry.getKey();
            String fieldValue = entry.getValue();
            
            if (fieldValue == null || fieldValue.isBlank()) {
                continue;
            }
            
            String fullId = textId + ":" + fieldName;
            ITextInfo info = ITextInfo.builder()
                    .id(fullId)
                    .language(locale)
                    .label(fieldValue)
                    .build();
            infos.add(info);
        }
        
        // Also check for hint links from form structure
        String hintId = hintIdsByLabelId.get(textId);
        if (hintId != null) {
            Map<String, String> hintFields = localeData.get(hintId);
            if (hintFields != null) {
                for (Map.Entry<String, String> entry : hintFields.entrySet()) {
                    String fieldName = entry.getKey();
                    String fieldValue = entry.getValue();
                    if (fieldValue != null && !fieldValue.isBlank()) {
                        String fullId = hintId + ":" + fieldName;
                        ITextInfo info = ITextInfo.builder()
                                .id(fullId)
                                .language(locale)
                                .label(fieldValue)
                                .build();
                        infos.add(info);
                    }
                }
            }
        }
        
        // Check for guidance hints
        for (String suffix : new String[]{":guidance_hint", ".guidance_hint", "_guidance_hint"}) {
            String guidanceId = textId + suffix;
            Map<String, String> guidanceFields = localeData.get(guidanceId);
            if (guidanceFields != null) {
                for (Map.Entry<String, String> entry : guidanceFields.entrySet()) {
                    String fieldName = entry.getKey();
                    String fieldValue = entry.getValue();
                    if (fieldValue != null && !fieldValue.isBlank()) {
                        String fullId = guidanceId + ":" + fieldName;
                        ITextInfo info = ITextInfo.builder()
                                .id(fullId)
                                .language(locale)
                                .label(fieldValue)
                                .build();
                        infos.add(info);
                    }
                }
            }
        }
        
        // Return the first info (label) as primary, but we need to return all
        // Actually, we should return a list, but the method signature returns single ITextInfo
        // Let's return the first one and the caller will collect all
        return infos.isEmpty() ? null : infos.get(0);
    }

    /**
     * Extracts all ITextInfo items for a given textId, creating separate entries
     * for each field (label, hint, guidance, etc.).
     * The textId from XML already includes the field suffix (e.g., /data/name:label).
     */
    private List<ITextInfo> extractITextItems(Map<String, Map<String, String>> localeData,
                                              Map<String, String> hintIdsByLabelId,
                                              String textId,
                                              String locale) {
        List<ITextInfo> infos = new ArrayList<>();
        
        Map<String, String> fields = localeData.get(textId);
        if (fields != null) {
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                String fieldName = entry.getKey();
                String fieldValue = entry.getValue();
                
                if (fieldValue == null || fieldValue.isBlank()) {
                    continue;
                }
                
                // textId already includes the field suffix (e.g., /data/name:label)
                // Use it directly as the ID
                ITextInfo info = ITextInfo.builder()
                        .id(textId)
                        .language(locale)
                        .label(fieldValue)
                        .build();
                infos.add(info);
            }
        }
        
        // Also check for hint links from form structure
        String hintId = hintIdsByLabelId.get(textId);
        if (hintId != null) {
            Map<String, String> hintFields = localeData.get(hintId);
            if (hintFields != null) {
                for (Map.Entry<String, String> entry : hintFields.entrySet()) {
                    String fieldName = entry.getKey();
                    String fieldValue = entry.getValue();
                    if (fieldValue != null && !fieldValue.isBlank()) {
                        // hintId already includes the field suffix
                        ITextInfo info = ITextInfo.builder()
                                .id(hintId)
                                .language(locale)
                                .label(fieldValue)
                                .build();
                        infos.add(info);
                    }
                }
            }
        }
        
        // Check for guidance hints
        for (String suffix : new String[]{":guidance_hint", ".guidance_hint", "_guidance_hint"}) {
            String guidanceId = textId + suffix;
            Map<String, String> guidanceFields = localeData.get(guidanceId);
            if (guidanceFields != null) {
                for (Map.Entry<String, String> entry : guidanceFields.entrySet()) {
                    String fieldName = entry.getKey();
                    String fieldValue = entry.getValue();
                    if (fieldValue != null && !fieldValue.isBlank()) {
                        // guidanceId already includes the field suffix
                        ITextInfo info = ITextInfo.builder()
                                .id(guidanceId)
                                .language(locale)
                                .label(fieldValue)
                                .build();
                        infos.add(info);
                    }
                }
            }
        }
        
        return infos;
    }

    private void collectHintLinks(List<IFormElement> elements, Map<String, String> links) {
        if (elements == null) {
            return;
        }
        for (IFormElement element : elements) {
            if (element instanceof QuestionDef) {
                QuestionDef question = (QuestionDef) element;
                if (!isBlank(question.getTextID()) && !isBlank(question.getHelpTextID())) {
                    links.put(question.getTextID(), question.getHelpTextID());
                }
            }
            collectHintLinks(element.getChildren(), links);
        }
    }

    private static String baseId(String key) {
        int separator = key == null ? -1 : key.indexOf(';');
        return separator < 0 ? key : key.substring(0, separator);
    }

    public List<ITextInfo> getTextsByLanguage(ITextExtractionResult result, String language) {
        return result.getTextsByLanguage().getOrDefault(language, new ArrayList<>());
    }

    public ITextInfo getText(ITextExtractionResult result, String textId, String language) {
        return result.getTextsById().get(textId + ":" + language);
    }

    public List<String> getAllTextIds(ITextExtractionResult result) {
        return result.getTexts().stream().map(ITextInfo::getId).distinct().collect(Collectors.toList());
    }

    public List<String> getLanguages(ITextExtractionResult result) {
        return result.getLanguagesAsList();
    }

    public String[] getLanguagesArray(ITextExtractionResult result) {
        List<String> languages = result.getLanguages();
        return languages.toArray(new String[0]);
    }

    public String getDefaultLanguage(ITextExtractionResult result) {
        return result.getDefaultLanguage();
    }

    /**
     * Gets all translations for a specific language as a map of textId -> translation.
     * Returns the label field by default for each textId.
     */
    public Map<String, String> getTranslations(ITextExtractionResult result, String language) {
        Map<String, String> translations = new HashMap<>();
        if (result == null || isBlank(language)) {
            return translations;
        }
        List<ITextInfo> texts = result.getTextsByLanguage().get(language);
        if (texts == null) {
            return translations;
        }
        for (ITextInfo text : texts) {
            if (text.hasLabel()) {
                translations.put(text.getId(), text.getLabel());
            }
        }
        return translations;
    }

    public boolean hasLanguage(ITextExtractionResult result, String language) {
        return result.getTextsByLanguage().containsKey(language);
    }

    public int getTextCountForLanguage(ITextExtractionResult result, String language) {
        List<ITextInfo> texts = result.getTextsByLanguage().get(language);
        return texts == null ? 0 : texts.size();
    }

    /**
     * Gets the translation for a specific text ID in a specific language from a form definition.
     */
    public String getTranslation(FormDef formDef, String language, String textId) {
        if (formDef == null || isBlank(language) || isBlank(textId)) {
            return null;
        }
        ITextExtractionResult result = extractIText(formDef);
        ITextInfo text = result.getTextsById().get(textId + ":" + language);
        return text != null ? text.getLabel() : null;
    }

    /**
     * Gets all translations for a specific text ID across all languages from a form definition.
     */
    public Map<String, String> getAllTranslationsForKey(FormDef formDef, String textId) {
        Map<String, String> translations = new HashMap<>();
        if (formDef == null || isBlank(textId)) {
            return translations;
        }
        ITextExtractionResult result = extractIText(formDef);
        for (String language : result.getLanguages()) {
            ITextInfo text = result.getTextsById().get(textId + ":" + language);
            if (text != null && text.hasLabel()) {
                translations.put(language, text.getLabel());
            }
        }
        return translations;
    }

    /**
     * Gets the default language from a form definition.
     */
    public String getDefaultLanguage(FormDef formDef) {
        if (formDef == null) {
            return null;
        }
        ITextExtractionResult result = extractIText(formDef);
        return result.getDefaultLanguage();
    }

    /**
     * Checks if a translation exists for a specific text ID in a specific language.
     */
    public boolean hasTranslation(FormDef formDef, String language, String textId) {
        if (formDef == null || isBlank(language) || isBlank(textId)) {
            return false;
        }
        ITextExtractionResult result = extractIText(formDef);
        ITextInfo text = result.getTextsById().get(textId + ":" + language);
        return text != null && text.hasLabel();
    }

    public List<ITextInfo> getTextsWithLabels(ITextExtractionResult result) {
        return result.getTexts().stream().filter(ITextInfo::hasLabel).collect(Collectors.toList());
    }

    public List<ITextInfo> getTextsWithHints(ITextExtractionResult result) {
        return result.getTexts().stream().filter(ITextInfo::hasHint).collect(Collectors.toList());
    }

    public List<ITextInfo> getTextsWithMedia(ITextExtractionResult result) {
        return result.getTexts().stream().filter(ITextInfo::hasMedia).collect(Collectors.toList());
    }

    public List<ITextInfo> getTextsWithGuidanceHints(ITextExtractionResult result) {
        return result.getTexts().stream().filter(ITextInfo::hasGuidanceHint).collect(Collectors.toList());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean allBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return false;
            }
        }
        return true;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static void setFeature(DocumentBuilderFactory factory, String name, boolean value) {
        try {
            factory.setFeature(name, value);
        } catch (Exception ignored) {
            // The JAXP implementation may not expose every hardening flag.
        }
    }

    private static void setAttribute(DocumentBuilderFactory factory, String name, Object value) {
        try {
            factory.setAttribute(name, value);
        } catch (IllegalArgumentException ignored) {
            // Some JAXP providers do not implement these standard properties.
        }
    }

    private static Element findFirstDescendantByLocalName(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        String current = parent.getLocalName() == null ? parent.getNodeName() : parent.getLocalName();
        if (localName.equals(current)) {
            return parent;
        }
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element) {
                Element found = findFirstDescendantByLocalName((Element) child, localName);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @lombok.Data
    @lombok.Builder
    public static class ITextExtractionResult {
        private String formId;
        private List<ITextInfo> texts;
        private Map<String, ITextInfo> textsById;
        private Map<String, List<ITextInfo>> textsByLanguage;
        private String[] languages;
        private String defaultLanguage;

        public static ITextExtractionResult empty() {
            return ITextExtractionResult.builder()
                    .formId("")
                    .texts(new ArrayList<>())
                    .textsById(new HashMap<>())
                    .textsByLanguage(new HashMap<>())
                    .languages(new String[0])
                    .defaultLanguage(null)
                    .build();
        }

        public int getTextCount() { return texts != null ? texts.size() : 0; }
        public int getLanguageCount() { return languages != null ? languages.length : 0; }

        public List<String> getLanguages() {
            return languages != null ? List.of(languages) : List.of();
        }

        public List<String> getLanguagesAsList() {
            return getLanguages();
        }

        /**
         * Gets all translations for a specific language as a map of textId -> translation.
         * Returns the label field by default for each textId.
         */
        public Map<String, String> getTranslations(String language) {
            Map<String, String> translations = new HashMap<>();
            if (isBlank(language)) {
                return translations;
            }
            List<ITextInfo> languageTexts = textsByLanguage.get(language);
            if (languageTexts == null) {
                return translations;
            }
            for (ITextInfo text : languageTexts) {
                if (text.hasLabel()) {
                    translations.put(text.getId(), text.getLabel());
                }
            }
            return translations;
        }
    }

    @lombok.Data
    @lombok.Builder
    public static class ITextInfo {
        private String id;
        private String language;
        private String label;
        private String hint;
        private String image;
        private String audio;
        private String video;
        private String guidanceHint;

        public boolean hasLabel() { return !isBlank(label); }
        public boolean hasHint() { return !isBlank(hint); }
        public boolean hasImage() { return !isBlank(image); }
        public boolean hasAudio() { return !isBlank(audio); }
        public boolean hasVideo() { return !isBlank(video); }
        public boolean hasMedia() { return hasImage() || hasAudio() || hasVideo(); }
        public boolean hasGuidanceHint() { return !isBlank(guidanceHint); }
    }
}
