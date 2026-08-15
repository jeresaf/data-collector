# JavaRosa v4.3.2 — Model Reference for an ODK Spring Boot Application

> **Reference boundary:** This document describes `org.getodk:javarosa:4.3.2` only.  
> It is intended to stop an AI coding model from inventing methods, mixing JavaRosa versions, or replacing JavaRosa behavior with custom logic.
>
> **Project rule:** The original ODK XForm XML is the canonical form definition. JavaRosa is the runtime standards engine. Database entities and frontend DTOs are application-owned representations, not replacements for the XForm or JavaRosa runtime.

---

## 1. What JavaRosa is

JavaRosa is a Java library that parses and runs forms based on the ODK XForms specification. In an ODK-compatible Spring Boot system, it should be used for:

- parsing XForm XML;
- building the form definition and instance trees;
- evaluating XPath expressions;
- applying `relevant`, `required`, `readonly`, `constraint`, and `calculate` behavior;
- managing groups and repeats;
- resolving static and dynamic choices;
- handling translations and text forms;
- navigating form-entry events;
- accepting typed answers;
- recalculating dependent nodes;
- validating a form instance;
- serializing the completed instance back to XML.

JavaRosa is **not**:

- a Spring Boot framework;
- a JPA persistence model;
- an XLSForm-to-XForm converter;
- a complete HTML renderer;
- an OpenRosa server implementation;
- a reporting engine;
- a database schema generator;
- a safe singleton form session shared by many users.

The Spring Boot application should wrap JavaRosa behind application services and expose stable DTOs to the web frontend.

---

## 2. Artifact and platform

### Maven dependency

```xml
<dependency>
    <groupId>org.getodk</groupId>
    <artifactId>javarosa</artifactId>
    <version>4.3.2</version>
</dependency>
```

The artifact is a JAR compiled for Java 8. Its published Maven metadata includes dependencies such as kXML2, Joda-Time, Jackson, Commons CSV, Commons IO, Kotlin standard library, SLF4J, and Bouncy Castle.

### Important dependency note

The repository's `pom.xml` and `build.gradle` are not perfectly identical in every dependency version. A Maven-based Spring Boot project resolves the published POM, so dependency convergence should be checked with:

```bash
mvn dependency:tree
```

Do not copy all JavaRosa transitive dependency versions into the application unless a concrete conflict requires dependency management.

---

## 3. Correct mental model

JavaRosa separates the form into several related models.

### 3.1 Form definition tree

`FormDef` is the top-level parsed form definition.

Its children are mainly:

- `GroupDef` — a group or repeat;
- `QuestionDef` — a question/control;
- `RangeQuestion` — a specialized question type.

The definition tree describes the form's presentation and binding structure.

### 3.2 Instance data tree

`FormInstance` represents an XForm instance.

Its nodes are `TreeElement` objects. A `TreeElement` can contain:

- a typed `IAnswerData` value;
- child elements;
- attributes;
- bind/runtime state such as relevance, requiredness, enabled state, constraint, and data type.

`FormInstance.getBase()` returns JavaRosa's hidden root node.

`FormInstance.getRoot()` returns the actual XML document root, which is the first child of the hidden root.

Do not confuse the definition tree with the instance tree:

- `QuestionDef` describes a question.
- `TreeElement` stores that question's current runtime state and answer.
- `FormIndex` points to a location in form-entry navigation.
- `TreeReference` points to a location in an instance tree.

### 3.3 Form-entry layer

The principal form-entry classes are:

- `FormEntryModel` — current form-entry state and navigation position;
- `FormEntryController` — navigation, answer submission, repeats, finalization;
- `FormEntryPrompt` — question information needed by a renderer;
- `FormEntryCaption` — group/repeat caption information.

### 3.4 Expression and dependency layer

JavaRosa parses XPath expressions and builds a dependency graph for:

- calculations;
- relevance;
- readonly/enabled state;
- required state;
- constraints;
- repeat-dependent expressions;
- actions.

A successful answer change can trigger a cascade of recalculation and condition evaluation.

---

## 4. Package map

| Package | Responsibility |
|---|---|
| `org.javarosa.xform.parse` | XForm parsing, bind processors, form processors, parse errors and warnings |
| `org.javarosa.xform.util` | Parsing/serializing answer data, XForm utility methods, XML serialization |
| `org.javarosa.core.model` | `FormDef`, `QuestionDef`, `GroupDef`, `FormIndex`, bindings, choices, validation |
| `org.javarosa.core.model.instance` | `FormInstance`, `TreeElement`, `TreeReference`, secondary instances |
| `org.javarosa.core.model.data` | `IAnswerData` and concrete typed answer classes |
| `org.javarosa.form.api` | Form navigation, prompts, captions, answer commits |
| `org.javarosa.xpath` and `.expr` | XPath parsing, expression types, evaluation |
| `org.javarosa.core.model.condition` | constraints, conditions, recalculates, evaluation context |
| `org.javarosa.core.services.locale` | `Localizer`, locales, itext resources and fallbacks |
| `org.javarosa.model.xform` | `XPathReference`, XForm instance serialization, module registration |
| `org.javarosa.core.model.actions` | XForm/ODK actions such as setvalue and event-triggered behavior |

---

## 5. Parsing an XForm

### Exact constructors in v4.3.2

`XFormParser` has these public constructor forms:

```java
new XFormParser(Reader form)
new XFormParser(Document form)
new XFormParser(Reader form, Reader instance)
new XFormParser(Document form, Document instance)
```

It does **not** have an arbitrary `InputStream` constructor or a constructor with invented boolean/configuration arguments.

### Basic parsing

```java
import java.io.StringReader;
import org.javarosa.core.model.FormDef;
import org.javarosa.xform.parse.XFormParser;

public FormDef parse(String xformXml) throws XFormParser.ParseException {
    XFormParser parser = new XFormParser(new StringReader(xformXml));
    return parser.parse();
}
```

Available parse methods include:

```java
parser.parse()
parser.parse(String lastSavedSrc)
parser.parse(String formXmlSrc, String lastSavedSrc)
```

### Utility parsing

`XFormUtils` includes:

```java
XFormUtils.getFormFromInputStream(inputStream)
XFormUtils.getFormFromInputStream(inputStream, lastSavedSrc)
XFormUtils.getFormFromFormXml(formXmlPath, lastSavedSrc)
```

`XFormUtils.getFormFromInputStream` reads UTF-8 and closes its reader.

### Parsing with a saved instance

For an existing instance, use the two-reader parser:

```java
XFormParser parser = new XFormParser(
    new StringReader(xformXml),
    new StringReader(instanceXml)
);
FormDef form = parser.parse();
```

Do not manually inject answers into a freshly parsed tree when JavaRosa can restore an instance through its parser.

---

## 6. Critical concurrency rule

`XFormParser` contains a **static global `ReentrantLock`**. Its parse method uses `tryLock()` and throws:

```text
Another XForm is being parsed!
```

when another parse is already active in the same JVM.

Therefore:

1. Do not call JavaRosa parsing concurrently from unrelated Spring request threads.
2. Put all parsing behind one application-owned parse gateway.
3. Serialize parse operations in each application instance.
4. Keep the gateway stateless apart from its lock.
5. Add concurrency tests.

Example:

```java
import java.io.StringReader;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.javarosa.core.model.FormDef;
import org.javarosa.xform.parse.XFormParser;
import org.springframework.stereotype.Service;

@Service
public final class JavaRosaParseGateway {
    private final Lock gatewayLock = new ReentrantLock(true);

    public FormDef parse(String xformXml) {
        gatewayLock.lock();
        try {
            return new XFormParser(new StringReader(xformXml)).parse();
        } catch (XFormParser.ParseException ex) {
            throw new IllegalArgumentException("Invalid ODK XForm", ex);
        } finally {
            gatewayLock.unlock();
        }
    }
}
```

The lock protects parsing only. It does not make a returned `FormDef` safe for concurrent use.

---

## 7. Form session scope

A live `FormDef` contains mutable state, including:

- the main `FormInstance`;
- non-main instances;
- current locale;
- evaluation context;
- triggerable dependency state;
- answers and recalculations;
- repeat instances;
- actions.

**Never register a live `FormDef` as a Spring singleton and never share one form session between users.**

Use this ownership model:

```text
Stored form version
    raw XForm XML
        |
        +-- parse a fresh runtime session
                |
                +-- one FormDef
                +-- one FormEntryModel
                +-- one FormEntryController
                +-- one user/draft/submission
```

A practical initial architecture is:

- cache raw XML and extracted application metadata;
- create a fresh JavaRosa runtime for each edit session or server-side operation;
- serialize access to parsing;
- persist draft/completed instance XML rather than a shared Java object graph.

Do not persist a JavaRosa `FormDef`, `TreeElement`, or `FormEntryModel` directly as JPA entities.

---

## 8. Initialization and modules

### Form initialization

`FormDef.initialize(boolean newInstance, InstanceInitializationFactory factory)`:

- initializes secondary instances;
- preloads new instances;
- sets the default locale when needed;
- fires instance-load actions;
- evaluates initial triggerables.

For a new form-entry session:

```java
import org.javarosa.core.model.instance.InstanceInitializationFactory;

FormDef form = parseGateway.parse(xformXml);
form.initialize(true, new InstanceInitializationFactory());
```

For a restored instance, use `newInstance = false` when your restoration path requires explicit initialization.

### Module registration

JavaRosa includes:

```java
new CoreModelModule().registerModule();
new XFormsModule().registerModule();
```

These register prototypes, storage types, XPath reference factories, answer parsing, and instance serialization used by JavaRosa's externalization/restoration infrastructure.

Register them once during application startup when using those facilities. Do not repeatedly register them per request.

---

## 9. Starting a form-entry session

```java
import org.javarosa.form.api.FormEntryController;
import org.javarosa.form.api.FormEntryModel;

FormEntryModel model = new FormEntryModel(form);
FormEntryController controller = new FormEntryController(model);
```

`FormEntryModel` defaults to linear repeat navigation.

It also supports:

```java
new FormEntryModel(form, FormEntryModel.REPEAT_STRUCTURE_LINEAR)
new FormEntryModel(form, FormEntryModel.REPEAT_STRUCTURE_NON_LINEAR)
```

Non-linear mode can fall back to linear mode for forms whose repeat structure requires it.

---

## 10. Navigation events

`FormEntryController` returns integer event codes:

| Constant | Value | Meaning |
|---|---:|---|
| `EVENT_BEGINNING_OF_FORM` | 0 | Before the first element |
| `EVENT_END_OF_FORM` | 1 | After the last element |
| `EVENT_PROMPT_NEW_REPEAT` | 2 | Prompt to add a repeat |
| `EVENT_QUESTION` | 4 | Question |
| `EVENT_GROUP` | 8 | Group |
| `EVENT_REPEAT` | 16 | Existing repeat |
| `EVENT_REPEAT_JUNCTURE` | 32 | Non-linear repeat juncture |

Typical traversal:

```java
int event = controller.stepToNextEvent();

switch (event) {
    case FormEntryController.EVENT_QUESTION:
        FormEntryPrompt prompt = model.getQuestionPrompt();
        break;

    case FormEntryController.EVENT_GROUP:
    case FormEntryController.EVENT_REPEAT:
    case FormEntryController.EVENT_PROMPT_NEW_REPEAT:
    case FormEntryController.EVENT_REPEAT_JUNCTURE:
        FormEntryCaption caption = model.getCaptionPrompt();
        break;

    case FormEntryController.EVENT_END_OF_FORM:
        break;

    default:
        break;
}
```

`stepToNextEvent()` and `stepToPreviousEvent()` skip indexes that JavaRosa determines are irrelevant.

Do not build a separate custom relevance-navigation algorithm for the web frontend.

---

## 11. Rendering questions

For a question event, use `FormEntryPrompt`.

Important methods include:

```java
prompt.getQuestion()
prompt.getControlType()
prompt.getDataType()
prompt.getQuestionText()
prompt.getLongText()
prompt.getShortText()
prompt.getHelpText()
prompt.getAudioText()
prompt.getImageText()
prompt.getAnswerValue()
prompt.getAnswerText()
prompt.getSelectChoices()
prompt.getConstraintText()
prompt.isRequired()
prompt.isReadOnly()
prompt.getBindAttributes()
prompt.getAppearanceHint()
prompt.getIndex()
```

For groups and repeats, use `FormEntryCaption` methods such as:

```java
caption.getLongText()
caption.getShortText()
caption.getAppearanceHint()
caption.repeats()
caption.getMultiplicity()
caption.getNumRepetitions()
caption.getRepeatText(...)
caption.getRepetitionsText()
```

### Known incomplete API

`FormEntryPrompt.getPromptAttributes()` is a TODO and returns `null` in v4.3.2.

Do not rely on it. Use:

- `prompt.getBindAttributes()`;
- `QuestionDef.getAdditionalAttributes()`;
- `GroupDef.getAdditionalAttributes()`;
- the canonical XForm XML for metadata JavaRosa does not expose cleanly.

---

## 12. Definition classes

### `QuestionDef`

Represents a question/control definition.

Useful methods:

```java
getBind()
getControlType()
getAppearanceAttr()
getTextID()
getLabelInnerText()
getHelpTextID()
getHelpText()
getHelpInnerText()
getChoices()
getChoice(int)
getChoiceForValue(String)
getDynamicChoices()
isComplex()
getAdditionalAttribute(namespace, name)
getAdditionalAttributes()
getActionController()
```

A `QuestionDef` is a leaf in the definition tree:

- `getChildren()` returns `null`;
- `setChildren(...)` throws;
- `addChild(...)` throws.

### `GroupDef`

Represents a group or repeat.

Useful methods and fields:

```java
getBind()
getChildren()
getChild(int)
getRepeat()
getTextID()
getLabelInnerText()
getAppearanceAttr()
getCountReference()
getConextualizedCountReference(TreeReference context) // spelling is exactly "Conextualized"
noAddRemove
count
```

There are no public v4.3.2 methods named:

```text
getRepeatCount()
getRepeatMin()
getRepeatMax()
getCurrentRepeatCount()
minRepeats()
maxRepeats()
```

Current repeat multiplicity is obtained through:

```java
formDef.getNumRepetitions(repeatFormIndex)
```

or by resolving the repeat's parent `TreeElement` and examining child multiplicity.

A repeat `count` expression is available through `GroupDef.getCountReference()`. Generic “minimum repeat” and “maximum repeat” properties are not standard `GroupDef` getters in this version; preserve any custom metadata from the source XML instead of inventing APIs.

---

## 13. Instance classes

### `FormInstance`

Useful methods:

```java
getBase()
getRoot()
resolveReference(TreeReference)
resolveReference(IDataReference)
getTemplate(TreeReference)
getTemplatePath(TreeReference)
hasTemplatePath(TreeReference)
copyNode(...)
addNode(...)
clone()
getName()
getInstanceId()
```

Remember:

```text
getBase() = hidden JavaRosa root
getRoot() = XML document root
```

### `TreeElement`

Represents one instance XML node and runtime bind state.

Useful methods include:

```java
getName()
getValue()
setAnswer(IAnswerData)
getDataType()
isLeaf()
isRelevant()
isEnabled()
isRequired()
isRepeatable()
getRef()
getParent()
getChild(name, multiplicity)
getFirstChild(name)
getChildAt(index)
getNumChildren()
getChildrenWithName(name)
getChildMultiplicity(name)
getConstraint()
getBindAttributes()
getBindAttributeValue(namespace, name)
getAttributeValue(namespace, name)
```

Important behavior:

- a node with children cannot also be assigned a simple value;
- adding children to a valued node throws;
- setting relevance or enabled state propagates inherited state to descendants;
- repeat template nodes use special multiplicity constants and are not normal answer rows.

### `TreeReference`

A `TreeReference` addresses nodes in an instance tree.

Reference rules:

- `DataInstance.resolveReference` requires an absolute reference;
- an unbound multiplicity resolves only when exactly one matching node exists;
- resolving `/` does not return the hidden root;
- use `contextualize`, `genericize`, `anchor`, and multiplicities correctly around repeats.

For a string path:

```java
XPathReference reference = new XPathReference("/data/person/name");
TreeReference treeRef =
    FormInstance.unpackReference(reference);
```

Do not implement XPath paths by splitting strings on `/`.

---

## 14. Typed answer data

Every answer is an `IAnswerData`.

Key rule from the interface:

> An `IAnswerData` object must not contain a null value. An absent or empty answer is represented by a null `IAnswerData` reference.

Common concrete types include:

| Data type | Answer class |
|---|---|
| text/barcode | `StringData` |
| integer | `IntegerData` |
| long | `LongData` |
| decimal | `DecimalData` |
| boolean | `BooleanData` |
| date | `DateData` |
| time | `TimeData` |
| dateTime | `DateTimeData` |
| select one | `SelectOneData` |
| select multiple/rank | `MultipleItemsData` |
| geopoint | `GeoPointData` |
| geoshape | `GeoShapeData` |
| geotrace | `GeoTraceData` |
| not-yet-cast input | `UncastData` |

### Parse a frontend string into an answer

Use `XFormAnswerDataParser`:

```java
String normalized =
    rawValue == null || rawValue.trim().isEmpty()
        ? null
        : rawValue;

IAnswerData answer = normalized == null
    ? null
    : XFormAnswerDataParser.getAnswerData(
        normalized,
        prompt.getDataType(),
        prompt.getQuestion()
    );
```

Normalize blank frontend input to `null` before answering, especially for required questions. A non-null empty `StringData` is still a non-null answer object.

For select questions, pass the `QuestionDef` so static choice values can be resolved correctly.

---

## 15. Submitting an answer

Use:

```java
int result = controller.answerQuestion(
    prompt.getIndex(),
    answer,
    true
);
```

Result constants are:

| Constant | Value |
|---|---:|
| `ANSWER_OK` | 0 |
| `ANSWER_REQUIRED_BUT_EMPTY` | 1 |
| `ANSWER_CONSTRAINT_VIOLATED` | 2 |

Example:

```java
switch (result) {
    case FormEntryController.ANSWER_OK:
        break;

    case FormEntryController.ANSWER_REQUIRED_BUT_EMPTY:
        // Return a required-answer validation error.
        break;

    case FormEntryController.ANSWER_CONSTRAINT_VIOLATED:
        String message = prompt.getConstraintText(answer);
        // Return the localized constraint message.
        break;

    default:
        throw new IllegalStateException("Unknown JavaRosa answer result: " + result);
}
```

### Do not use `saveAnswer` for normal form filling

`saveAnswer(...)` explicitly bypasses constraint checking.

Use `answerQuestion(...)` for ordinary web entry. Reserve `saveAnswer(...)` for controlled internal operations where bypassing validation is intentional and tested.

### Complex itemset caveat

JavaRosa's controller handles complex/copy-mode itemsets differently from simple questions. Constraints on complex itemsets are not handled like ordinary constraints in this code path. Test such forms explicitly rather than assuming identical behavior.

---

## 16. Recalculations, relevance, constraints and actions

When `FormDef.setValue(...)` commits an answer, JavaRosa:

1. updates the `TreeElement`;
2. triggers dependent calculations and conditions;
3. can run question-value-changed actions;
4. publishes dependency-graph events.

Use JavaRosa's updated runtime state after an answer:

```java
prompt.isRequired()
prompt.isReadOnly()
model.isIndexRelevant(index)
treeElement.isRelevant()
treeElement.isEnabled()
```

Do not cache relevance, readonly, required, choice lists, or calculated values independently in the frontend without refreshing them after an accepted answer.

### Full validation

```java
ValidateOutcome validation = form.validate();
```

`ValidateOutcome` contains:

```java
public final FormIndex failedPrompt;
public final int outcome;
```

Treat a non-success outcome as a pointer to the failed prompt and map it to your API validation response. Do not invent methods such as `isValid()`, `getErrors()`, or `getMessages()` on `ValidateOutcome`.

---

## 17. Repeats

Controller repeat operations include:

```java
controller.descendIntoRepeat(int n)
controller.descendIntoNewRepeat()
controller.newRepeat()
controller.newRepeat(FormIndex)
controller.deleteRepeat()
controller.deleteRepeat(FormIndex)
controller.jumpToNewRepeatPrompt()
```

`FormDef` also contains:

```java
getNumRepetitions(FormIndex)
descendIntoRepeat(FormIndex, int)
createNewRepeat(FormIndex)
deleteRepeat(FormIndex)
canCreateRepeat(TreeReference, FormIndex)
isRepeatRelevant(TreeReference)
```

New repeats are created from the repeat template node. JavaRosa then fires repeat-related actions and recalculates dependent expressions.

Do not represent repeats only as a flat frontend array detached from `FormIndex` and `TreeReference`. The same repeat definition can have different contextual multiplicities when nested.

---

## 18. Localization and itext

`FormDef` stores a `Localizer`.

Correct access:

```java
Localizer localizer = formDef.getLocalizer();
```

Useful methods:

```java
localizer.getAvailableLocales()
localizer.getLocale()
localizer.getDefaultLocale()
localizer.setLocale(locale)
localizer.setToDefault()
localizer.getText(textId)
localizer.getText(textId, locale)
localizer.getLocalizedText(textId)
localizer.getRawText(locale, textId)
localizer.hasLocale(locale)
localizer.hasMapping(locale, textId)
```

Text forms are represented by suffixes such as:

```text
text-id;long
text-id;short
text-id;audio
text-id;image
text-id;video
```

For normal rendering, prefer `FormEntryPrompt` and `FormEntryCaption`, because they apply fallbacks and `<output>` substitutions.

### Nonexistent API

`FormDef.getIText()` does **not** exist in v4.3.2.

Use:

```java
formDef.getLocalizer()
prompt.getQuestionText()
prompt.getLongText()
prompt.getShortText()
prompt.getHelpText()
caption.getLongText()
```

---

## 19. XPath

Parse an XPath expression with:

```java
XPathExpression expression =
    XPathParseTool.parseXPath(xpath);
```

Create a path reference with:

```java
XPathReference reference =
    new XPathReference("/data/household/member/name");
```

`XPathReference` requires the expression to be an XPath path. It throws a type-mismatch or XPath exception when given a non-path expression.

Evaluation depends on:

- a `FormInstance`;
- an `EvaluationContext`;
- a context `TreeReference`;
- registered function handlers;
- secondary instances.

Do not use a generic Java XPath engine as a drop-in replacement for JavaRosa expression semantics. ODK forms use JavaRosa/ODK functions and contextual behavior beyond basic XPath.

---

## 20. Secondary instances and dynamic choices

`FormDef` can hold named non-main instances.

Useful methods:

```java
addNonMainInstance(DataInstance)
getNonMainInstance(String)
getNonMainInstances()
getFormInstances()
```

During parsing, JavaRosa supports secondary instance sources including:

```text
jr://file/...
jr://file-csv/...
jr://instance/last-saved
```

Only secondary instances referenced through `instance()` in the primary form are loaded in the parser path reviewed here.

Dynamic itemsets are represented by `ItemsetBinding`. For rendering choices, prefer:

```java
prompt.getSelectChoices()
```

This evaluates the dynamic choice list in the current question context.

Do not extract a dynamic itemset once at form-upload time and assume it is static. Its choices can depend on current answers, repeat context, filters, and secondary instances.

---

## 21. Serializing submission XML

Use `XFormSerializingVisitor`:

```java
import java.nio.charset.StandardCharsets;
import org.javarosa.model.xform.XFormSerializingVisitor;

controller.finalizeFormEntry();

byte[] xmlBytes =
    new XFormSerializingVisitor()
        .serializeInstance(form.getMainInstance());

String instanceXml =
    new String(xmlBytes, StandardCharsets.UTF_8);
```

Default serialization respects relevance and excludes:

- non-relevant nodes;
- repeat template nodes.

An alternative constructor allows:

```java
new XFormSerializingVisitor(false)
```

which does not filter by relevance. Use this only with a deliberate submission policy.

For attachments/media, `createSerializedPayload(...)` can produce a payload containing XML and external data pointers.

Persist the resulting raw instance XML as the authoritative submission/draft representation.

---

## 22. Parse warnings and errors

After parsing:

```java
formDef.getParseWarnings()
formDef.getParseErrors()
```

The parser can warn about:

- unrecognized elements;
- unrecognized attributes;
- invalid secondary instance `src` values;
- multiple models;
- other compatibility issues.

Do not treat “parse returned a FormDef” as the only quality check. Store and expose warnings in form-version validation results.

Recommended upload result:

```json
{
  "accepted": true,
  "fatalErrors": [],
  "parseErrors": [],
  "warnings": [],
  "metadata": {
    "xmlFormId": "...",
    "version": "...",
    "title": "...",
    "instanceRoot": "..."
  }
}
```

Metadata such as the canonical `xmlFormId`, version, and instance root should also be extracted from the raw XML. Do not assume every item has a convenient `FormDef` getter.

---

## 23. Spring Boot boundary design

Recommended modules:

```text
form-definition
    Stores raw XForm XML, hash, title, xmlFormId, version and upload validation.

javarosa-adapter
    Owns JavaRosa startup registration, serialized parsing, runtime creation,
    answer conversion, navigation mapping, validation and XML serialization.

form-session
    Owns user draft/session identity, optimistic locking and saved instance XML.

openrosa
    Implements ODK form list, form download, submission and authentication endpoints.

web-form-api
    Converts JavaRosa prompts/captions/events into stable frontend DTOs.

reporting
    Reads normalized application/reporting data and raw submissions;
    it does not mutate JavaRosa runtime state.
```

### Suggested service boundaries

```java
interface XFormParseService {
    ParsedFormMetadata validateAndDescribe(String xformXml);
    FormDef createNewRuntime(String xformXml);
    FormDef restoreRuntime(String xformXml, String instanceXml);
}

interface FormSessionService {
    FormScreenDto currentScreen(UUID sessionId);
    AnswerResultDto answer(UUID sessionId, AnswerCommand command);
    FormScreenDto next(UUID sessionId);
    FormScreenDto previous(UUID sessionId);
    SubmissionResultDto finalizeSession(UUID sessionId);
}
```

Application DTOs should contain only stable JSON data such as:

```text
event type
form index token
instance reference
question text
help text
control type
data type
appearance
required
readonly
current answer
choices
constraint message
repeat information
```

Do not serialize JavaRosa object graphs directly through Jackson.

---

## 24. Persistence rules

Persist:

- original XForm XML;
- content hash;
- `xmlFormId`;
- version;
- title;
- instance root name;
- parse warnings/errors;
- raw draft instance XML;
- raw final submission XML;
- submission metadata;
- media attachments;
- application audit history.

Do not treat these as canonical substitutes for the XML:

- flattened question rows;
- generated JSON form schemas;
- cached labels;
- cached relevance;
- cached choice lists;
- JPA versions of `QuestionDef` or `TreeElement`.

Flattened metadata can be stored for search/reporting, but it must be regenerable from the authoritative XForm XML.

---

## 25. Important v4.3.2 anti-hallucination table

| Hallucinated or unsafe assumption | Correct v4.3.2 behavior |
|---|---|
| `new XFormParser(inputStream, null, false)` | Use a supported `Reader` or `Document` constructor. |
| `formDef.getIText()` | Does not exist. Use `formDef.getLocalizer()` or prompt/caption text methods. |
| `formDef.getLanguages()` | Use `formDef.getLocalizer().getAvailableLocales()` or `FormEntryModel.getLanguages()`. |
| `formDef.getDefaultLanguage()` | Use `formDef.getLocalizer().getDefaultLocale()`. |
| `group.getRepeatCount()` | Does not exist. Use `formDef.getNumRepetitions(formIndex)`. |
| `group.getRepeatMin()` / `getRepeatMax()` | Not public `GroupDef` APIs in this version. Preserve custom XML metadata if needed. |
| `builder.repeatCount(...)`, `minRepeats(...)`, `maxRepeats(...)` | These are application builder methods only if your own DTO defines them; they are not JavaRosa APIs. |
| `ValidateOutcome.isValid()` | Does not exist. Inspect its `failedPrompt` and `outcome` fields. |
| `FormEntryPrompt.getPromptAttributes()` returns metadata | It is a TODO and returns `null`. |
| `FormEntryModel.getCompletedRelevantQuestionCount()` works | It is a TODO and returns `0`. |
| `FormEntryModel.getTotalRelevantQuestionCount()` works | It is a TODO and returns `0`. |
| `saveAnswer(...)` validates | It bypasses constraint checking. Use `answerQuestion(...)`. |
| A parsed `FormDef` is immutable | It contains the mutable runtime instance and session state. |
| A singleton `FormDef` can serve all users | Never share it across user sessions. |
| XForm parsing is naturally concurrent | It is guarded by a static lock and concurrent parse attempts fail. |
| JavaRosa objects should be JPA entities | Persist raw XML and application-owned metadata instead. |
| JavaRosa converts XLSForm spreadsheets | It consumes XForm XML; XLSForm conversion is a separate concern. |
| JavaRosa implements OpenRosa endpoints | Spring Boot must implement the server protocol around JavaRosa. |
| `FormDef.getID()` is the XForm's `xmlFormId` | It is an internal numeric storage identifier. Extract canonical form IDs from raw XML. |
| `FormDef.getVersion()` or `getNameSpace()` can be assumed | Do not assume such getters. Read canonical metadata from raw XML and inspect `FormInstance` fields only where explicitly supported. |
| Dynamic choices are fixed at upload time | They can depend on answers, context and secondary instances. |
| Blank text is always the same as no answer | Normalize blank frontend input to a null `IAnswerData` reference. |

---

## 26. Data type constants

Prefer `DataType` over old integer constants where possible.

| `DataType` | Integer value |
|---|---:|
| `UNSUPPORTED` | -1 |
| `NULL` | 0 |
| `TEXT` | 1 |
| `INTEGER` | 2 |
| `DECIMAL` | 3 |
| `DATE` | 4 |
| `TIME` | 5 |
| `DATE_TIME` | 6 |
| `CHOICE` | 7 |
| `MULTIPLE_ITEMS` | 8 |
| `BOOLEAN` | 9 |
| `GEOPOINT` | 10 |
| `BARCODE` | 11 |
| `BINARY` | 12 |
| `LONG` | 13 |
| `GEOSHAPE` | 14 |
| `GEOTRACE` | 15 |

Convert an integer with:

```java
DataType dataType = DataType.from(intValue);
```

---

## 27. Control type constants

| Constant | Value |
|---|---:|
| `CONTROL_UNTYPED` | -1 |
| `CONTROL_INPUT` | 1 |
| `CONTROL_SELECT_ONE` | 2 |
| `CONTROL_SELECT_MULTI` | 3 |
| `CONTROL_TEXTAREA` | 4 |
| `CONTROL_SECRET` | 5 |
| `CONTROL_RANGE` | 6 |
| `CONTROL_UPLOAD` | 7 |
| `CONTROL_SUBMIT` | 8 |
| `CONTROL_TRIGGER` | 9 |
| `CONTROL_IMAGE_CHOOSE` | 10 |
| `CONTROL_LABEL` | 11 |
| `CONTROL_AUDIO_CAPTURE` | 12 |
| `CONTROL_VIDEO_CAPTURE` | 13 |
| `CONTROL_OSM_CAPTURE` | 14 |
| `CONTROL_FILE_CAPTURE` | 15 |
| `CONTROL_RANK` | 16 |

The web renderer should map these constants through one application-owned registry. Do not scatter `switch` statements throughout controllers and components.

---

## 28. Testing requirements

At minimum, maintain tests for:

### Parser

- valid minimal XForm;
- invalid XML;
- missing model/instance structures;
- parse warnings;
- concurrent parse requests;
- internal and secondary instances;
- restore from saved instance.

### Runtime

- required questions;
- constraint pass/fail;
- calculations;
- chained calculations;
- relevance changes;
- readonly changes;
- language switching;
- static choices;
- dynamic itemsets;
- nested groups;
- repeats;
- nested repeats;
- repeat count expressions;
- actions;
- final validation.

### Serialization

- namespace preservation;
- relevant-node filtering;
- repeat output;
- select-one and select-multiple output;
- dates and times;
- geo values;
- blank answers;
- attachments;
- parse → answer → serialize → restore round trip.

### Spring integration

- session isolation;
- optimistic locking;
- two users editing the same form version;
- draft resume;
- form-version immutability after publication;
- OpenRosa submission compatibility;
- raw XML retained byte-for-byte where required.

Prefer real XForm fixtures and integration tests over extensive mocking of JavaRosa internals.

---

## 29. Instructions for an AI coding model

Use the following rules whenever generating code for this project:

1. Target JavaRosa **v4.3.2**, not another version.
2. Before using a JavaRosa method, verify that it appears in this reference or in the v4.3.2 source.
3. Never invent convenience getters.
4. Never replace JavaRosa relevance, constraints, calculations, repeats, itemsets or XPath behavior with custom approximations.
5. Raw XForm XML is the source of truth.
6. Raw instance XML is the source of truth for drafts and submissions.
7. `FormDef` is a mutable per-session runtime object.
8. Serialize XForm parsing because `XFormParser` rejects concurrent parses.
9. Use `FormEntryModel`, `FormEntryController`, `FormEntryPrompt` and `FormEntryCaption` for form-entry behavior.
10. Use `XFormAnswerDataParser` and `IAnswerData` types for answers.
11. Use `answerQuestion`, not `saveAnswer`, for ordinary user input.
12. Use `XFormSerializingVisitor` to produce instance XML.
13. Use `Localizer` and prompt/caption methods for itext.
14. Extract canonical IDs/version/root metadata from the raw XML when no verified JavaRosa API exposes it.
15. Do not persist JavaRosa object graphs as JPA entities.
16. Do not expose JavaRosa objects directly through REST.
17. Treat incomplete methods as incomplete:
    - `FormEntryPrompt.getPromptAttributes()` returns `null`;
    - completed/total relevant question counters return `0`.
18. When uncertain, stop and cite the exact class, method signature and source path instead of guessing.

---

## 30. High-value v4.3.2 source files

A model should consult these files first when the reference is insufficient:

```text
pom.xml
build.gradle
README.md

src/main/java/org/javarosa/xform/parse/XFormParser.java
src/main/java/org/javarosa/xform/parse/XFormParserFactory.java
src/main/java/org/javarosa/xform/util/XFormUtils.java
src/main/java/org/javarosa/xform/util/XFormAnswerDataParser.java
src/main/java/org/javarosa/xform/util/XFormAnswerDataSerializer.java

src/main/java/org/javarosa/core/model/FormDef.java
src/main/java/org/javarosa/core/model/QuestionDef.java
src/main/java/org/javarosa/core/model/GroupDef.java
src/main/java/org/javarosa/core/model/FormIndex.java
src/main/java/org/javarosa/core/model/ValidateOutcome.java
src/main/java/org/javarosa/core/model/DataType.java
src/main/java/org/javarosa/core/model/Constants.java

src/main/java/org/javarosa/core/model/instance/FormInstance.java
src/main/java/org/javarosa/core/model/instance/DataInstance.java
src/main/java/org/javarosa/core/model/instance/TreeElement.java
src/main/java/org/javarosa/core/model/instance/TreeReference.java

src/main/java/org/javarosa/core/model/data/IAnswerData.java

src/main/java/org/javarosa/form/api/FormEntryModel.java
src/main/java/org/javarosa/form/api/FormEntryController.java
src/main/java/org/javarosa/form/api/FormEntryPrompt.java
src/main/java/org/javarosa/form/api/FormEntryCaption.java

src/main/java/org/javarosa/core/services/locale/Localizer.java

src/main/java/org/javarosa/xpath/XPathParseTool.java
src/main/java/org/javarosa/model/xform/XPathReference.java
src/main/java/org/javarosa/model/xform/XFormSerializingVisitor.java
src/main/java/org/javarosa/model/xform/XFormsModule.java
src/main/java/org/javarosa/core/model/CoreModelModule.java
```

---

## 31. Compact context block

Paste this near the top of the model's project instructions:

```text
JAVA ROSA CONTRACT

Use org.getodk:javarosa:4.3.2 only.

The original ODK XForm XML is canonical. JavaRosa is the runtime engine for
parsing, XPath, calculations, relevance, readonly, required, constraints,
itemsets, repeats, localization, navigation, validation and instance XML
serialization.

A FormDef is mutable and belongs to one form-entry session. Never make it a
Spring singleton or share it across users. Persist raw XForm XML and raw
instance XML, not JavaRosa object graphs.

XFormParser accepts Reader or kXML Document constructors. It has a static
global parse lock and concurrent parses in one JVM can throw
"Another XForm is being parsed!", so all parsing must go through one
application-owned serialized gateway.

Normal lifecycle:
XFormParser -> FormDef -> initialize -> FormEntryModel ->
FormEntryController -> FormEntryPrompt/FormEntryCaption ->
XFormAnswerDataParser/IAnswerData -> answerQuestion ->
validate/finalize -> XFormSerializingVisitor.

Verified corrections:
- FormDef.getIText() does not exist; use FormDef.getLocalizer() or prompt text.
- GroupDef repeat-count/min/max getters do not exist.
- Use FormDef.getNumRepetitions(FormIndex) for current repeat count.
- FormEntryPrompt.getPromptAttributes() returns null.
- FormEntryModel completed/total relevant question counts return 0.
- saveAnswer bypasses constraints; ordinary input uses answerQuestion.
- FormDef.getID() is not the canonical xmlFormId.
- Do not invent FormDef version, namespace, language or instance-reference getters.
- Dynamic choices must be evaluated in current runtime context.
- Blank frontend answers should be normalized to null IAnswerData.

When an API is not listed in the project reference, inspect the exact
v4.3.2 source before generating code. Never guess a JavaRosa method.
```

---

## 32. Source baseline

This reference was prepared against the `getodk/javarosa` repository's exact `v4.3.2` tag, with emphasis on the public APIs and runtime behavior most relevant to the ODK Spring Boot application.
