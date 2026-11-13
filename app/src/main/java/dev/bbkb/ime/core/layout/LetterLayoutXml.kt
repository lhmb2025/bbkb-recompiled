package dev.bbkb.ime.core.layout

import android.util.Xml
import android.view.KeyEvent
import dev.bbkb.ime.core.layout.LetterLayout.Key
import dev.bbkb.ime.core.layout.LetterLayout.Origin
import dev.bbkb.ime.core.layout.LetterLayout.Target
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.lang.reflect.Modifier
import java.util.Locale

/**
 * Reads and writes `letter-layout` documents (version 1), the format of every letter layout.
 *
 * ```xml
 * <?xml version="1.0" encoding="utf-8"?>
 * <letter-layout version="1" id="mine" name="My layout" targets="pkb,vkb">
 *     <bind languages="" keypad="any" keypadType="any" layoutSet="any"/>
 *     <rows template="spanish"/>
 *     <key code="KEYCODE_Q" keySpec="q"/>
 *     <key code="KEYCODE_E" keySpec="f" characterMapKeys="F" alt="#" multitapKeys="ƒ" moreKeys="ƒ,ḟ"/>
 *     <key code="KEYCODE_SEMICOLON" scanCode="39" keySpec="o"/>
 * </letter-layout>
 * ```
 *
 * Legends are one character; lists are comma-separated entries of 1 to [MAX_ENTRY_CODE_POINTS]
 * characters (ligatures like لا), `\,` and `\\` escaping. The XML declaration is optional (generated
 * files start with a comment). Must accept exactly what `letter_layouts_gen.py` writes. Reading is
 * strict: a silent typo is worse than an [InvalidLetterLayoutException] naming the key and attribute.
 */
object LetterLayoutXml {

    const val ROOT: String = "letter-layout"
    const val VERSION: Int = 1
    const val MIME_TYPE: String = "application/xml"
    const val FILE_SUFFIX: String = ".xml"

    /** Largest document [parse] reads (a full keypad is a few KB). */
    const val MAX_BYTES: Int = 256 * 1024

    /** The engine's own ceiling on keys in one layout. */
    const val MAX_KEYS: Int = 80

    /** Entries in one `multitapKeys` or `moreKeys` list (Vietnamese uses 17). */
    const val MAX_SEQUENCE: Int = 32

    /** Characters in one list entry: a ligature or a letter with its marks. */
    const val MAX_ENTRY_CODE_POINTS: Int = 8
    const val MAX_NAME_LENGTH: Int = 64
    const val MAX_LANGUAGES: Int = 32

    /**
     * `<rows template>` values, each with a matching `assets/kdb/<template>_vkb.xml`: `qwerty` 10/9/7,
     * `spanish` 10/10/7 (semicolon key ends the middle row: Colemak o, Dvorak s).
     */
    val ROWS_TEMPLATES: List<String> = listOf("qwerty", "spanish")

    private const val BIND = "bind"
    private const val ROWS = "rows"
    private const val KEY = "key"

    private val ROOT_ATTRIBUTES = setOf("version", "id", "name", "targets", "optIn")
    private val BIND_ATTRIBUTES = setOf("languages", "keypad", "keypadType", "layoutSet")
    private val ROWS_ATTRIBUTES = setOf("template")
    private val KEY_ATTRIBUTES = setOf("code", "scanCode", "keySpec", "characterMapKeys", "alt", "multitapKeys", "moreKeys", "engineKeys")

    private val LANGUAGE = Regex("^[A-Za-z]{2,3}([_-][A-Za-z0-9]{1,8}){0,2}$")

    /** Layout set name shape only: the reader has no resources, and a user file may name one this build lacks. */
    private val LAYOUT_SET = Regex("^[a-z0-9_]{1,64}$")
    private val SCAN_CODE = Regex("^[0-9]{1,10}$")
    private const val KEY_CODE_PREFIX = "KEYCODE_"

    /** Shared empty list; empty, so sharing cannot leak a change between keys. */
    private val NO_ENTRIES: Array<String> = emptyArray()

    /** Every `KEYCODE_*` in [KeyEvent], by reflection: `keyCodeFromString` is native and returns nothing in JVM tests. */
    private val KEY_CODES: Map<String, Int> by lazy {
        val names = LinkedHashMap<String, Int>()
        KeyEvent::class.java.fields
            .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType && it.name.startsWith(KEY_CODE_PREFIX) }
            .sortedBy { it.name }
            .forEach { field ->
                val value = field.getInt(null)
                if (value != KeyEvent.KEYCODE_UNKNOWN) names[field.name] = value
            }
        names
    }

    private val KEY_NAMES: Map<Int, String> by lazy {
        HashMap<Int, String>().apply { KEY_CODES.forEach { (name, code) -> putIfAbsent(code, name) } }
    }

    /** The key code for a name like `KEYCODE_Q`, or null. */
    @JvmStatic
    fun keyCodeOf(name: String): Int? = if (name.startsWith(KEY_CODE_PREFIX)) KEY_CODES[name] else null

    @JvmStatic
    fun nameOf(keyCode: Int): String = KEY_NAMES[keyCode] ?: "$KEY_CODE_PREFIX$keyCode"

    /**
     * Reads one document (at most [MAX_BYTES]); the caller closes [input]. [generateId] names a
     * document without `id` ([Origin.USER] only).
     */
    @JvmStatic
    @JvmOverloads
    @Throws(InvalidLetterLayoutException::class, IOException::class)
    fun parse(
        input: InputStream,
        origin: Origin = Origin.USER,
        generateId: () -> String = LetterLayout::newId,
    ): LetterLayout {
        val bytes = readCapped(input)
        if (bytes.size > MAX_BYTES) tooLarge()
        val parser = newParser()
        parser.setInput(ByteArrayInputStream(bytes), null)
        return read(parser, origin, generateId)
    }

    @JvmStatic
    @JvmOverloads
    @Throws(InvalidLetterLayoutException::class)
    fun parse(
        xml: String,
        origin: Origin = Origin.USER,
        generateId: () -> String = LetterLayout::newId,
    ): LetterLayout {
        if (xml.toByteArray(Charsets.UTF_8).size > MAX_BYTES) tooLarge()
        val parser = newParser()
        parser.setInput(StringReader(xml.removePrefix("\uFEFF")))
        return read(parser, origin, generateId)
    }

    /** Only the `<bind>` (nothing else checked), to choose among documents without full parses; null when unreadable. */
    @JvmStatic
    fun peekBinding(input: InputStream): LetterLayout.Binding? = try {
        val bytes = readCapped(input)
        if (bytes.size > MAX_BYTES) {
            null
        } else {
            val parser = newParser()
            parser.setInput(ByteArrayInputStream(bytes), null)
            var depth = 0
            var binding: LetterLayout.Binding? = null
            while (binding == null) {
                when (parser.nextToken()) {
                    XmlPullParser.END_DOCUMENT -> break
                    XmlPullParser.START_TAG -> {
                        depth++
                        // A child of the root: readBinding also consumes its end tag.
                        if (depth == 2 && parser.name == BIND) binding = readBinding(parser)
                    }
                    XmlPullParser.END_TAG -> depth--
                    else -> {}
                }
            }
            binding
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Namespace and DOCTYPE processing off (platform default is on): `xmlns` and prefixed names are
     * refused as unknown, and a DOCTYPE is refused outright so entities never expand.
     */
    private fun newParser(): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
    }

    /** Whether [text] is XML rather than JSON (first non-space after any BOM is `<`): layout file vs bundle. */
    @JvmStatic
    fun looksLikeXml(text: String): Boolean = text.removePrefix("\uFEFF").trimStart().startsWith("<")

    private fun readCapped(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() <= MAX_BYTES) {
            val read = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun tooLarge(): Nothing = fail("the file is larger than ${MAX_BYTES / 1024} KB")

    private fun read(parser: XmlPullParser, origin: Origin, generateId: () -> String): LetterLayout = try {
        readDocument(parser, origin, generateId)
    } catch (e: XmlPullParserException) {
        // Drop the parser's internal position suffix; keep the line number.
        val reason = e.message.orEmpty().substringBefore(" (position:")
        fail(if (e.lineNumber > 0) "not well-formed XML on line ${e.lineNumber}: $reason" else "not well-formed XML: $reason")
    } catch (e: IOException) {
        fail("not well-formed XML: ${e.message}")
    } catch (e: RuntimeException) {
        // The pull parser reports some malformed input this way rather than as a parse error.
        fail("not well-formed XML: ${e.message}")
    }

    private fun readDocument(parser: XmlPullParser, origin: Origin, generateId: () -> String): LetterLayout {
        var layout: LetterLayout? = null
        while (true) {
            when (parser.nextToken()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.DOCDECL -> fail("a DOCTYPE declaration is not allowed")
                XmlPullParser.START_TAG -> {
                    if (layout != null) fail("a second root element <${parser.name}> on line ${parser.lineNumber}")
                    if (parser.name != ROOT) fail("the root element is <${parser.name}>, expected <$ROOT>")
                    layout = readLayout(parser, origin, generateId)
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> rejectText(parser)
                else -> {} // comments, processing instructions, the XML declaration, white space
            }
        }
        return layout ?: fail("no <$ROOT> element")
    }

    private fun readLayout(parser: XmlPullParser, origin: Origin, generateId: () -> String): LetterLayout {
        rejectUnknownAttributes(parser, ROOT_ATTRIBUTES, ROOT)
        val version = parser.getAttributeValue(null, "version") ?: fail("$ROOT has no version")
        if (version != VERSION.toString()) fail("version \"$version\", this build reads version $VERSION")

        val id = when (val raw = parser.getAttributeValue(null, "id")) {
            null -> if (origin == Origin.BUILT_IN) fail("$ROOT has no id; a built-in layout must name itself") else generateId()
            else -> raw.also {
                if (!LetterLayout.isValidId(it)) {
                    fail("id \"$it\" may only use lower-case letters, digits, '.', '-' and '_' (at most 64)")
                }
            }
        }

        val name = parser.getAttributeValue(null, "name")?.trim() ?: fail("$ROOT has no name")
        if (name.isEmpty()) fail("name is empty")
        if (name.length > MAX_NAME_LENGTH) fail("name is longer than $MAX_NAME_LENGTH characters")
        if (name.any { Character.isISOControl(it) }) fail("name contains a control character")

        val targets = parser.getAttributeValue(null, "targets")?.let(::targets) ?: setOf(Target.PKB)
        val optIn = when (val raw = parser.getAttributeValue(null, "optIn")) {
            null, "false" -> false
            "true" -> true
            else -> fail("optIn is \"$raw\", expected true or false")
        }

        var binding: LetterLayout.Binding? = null
        var rowsTemplate: String? = null
        var sawRows = false
        val keys = LinkedHashMap<Int, Key>()
        while (true) {
            when (parser.nextToken()) {
                XmlPullParser.END_TAG -> break
                XmlPullParser.END_DOCUMENT -> fail("the document ends inside <$ROOT>")
                XmlPullParser.DOCDECL -> fail("a DOCTYPE declaration is not allowed")
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> rejectText(parser)
                XmlPullParser.START_TAG -> when (parser.name) {
                    BIND -> {
                        if (binding != null) fail("<$BIND> appears twice")
                        binding = readBinding(parser)
                    }
                    ROWS -> {
                        if (sawRows) fail("<$ROWS> appears twice")
                        sawRows = true
                        rowsTemplate = readRows(parser)
                    }
                    KEY -> {
                        if (keys.size == MAX_KEYS) fail("more than $MAX_KEYS keys")
                        val key = readKey(parser)
                        if (keys.containsKey(key.keyCode)) fail("${nameOf(key.keyCode)} appears twice")
                        keys[key.keyCode] = key
                    }
                    else -> fail("unknown element <${parser.name}> on line ${parser.lineNumber}")
                }
                else -> {}
            }
        }
        return LetterLayout(
            id = id,
            name = name,
            origin = origin,
            targets = targets,
            binding = binding ?: fail("<$BIND> is missing"),
            rowsTemplate = rowsTemplate,
            keys = keys,
            optIn = optIn,
        )
    }

    private fun targets(raw: String): Set<Target> {
        val tokens = raw.split(',').map { it.trim() }
        if (tokens.all { it.isEmpty() }) fail("targets is empty")
        val targets = LinkedHashSet<Target>()
        tokens.forEach { token ->
            val target = Target.of(token) ?: fail("targets names \"$token\", expected pkb or vkb")
            if (!targets.add(target)) fail("targets names $token twice")
        }
        return targets
    }

    private fun readBinding(parser: XmlPullParser): LetterLayout.Binding {
        rejectUnknownAttributes(parser, BIND_ATTRIBUTES, BIND)
        val languages = parser.getAttributeValue(null, "languages")?.let { raw ->
            if (raw.isBlank()) return@let emptyList()
            val entries = raw.split(',').map { it.trim() }
            if (entries.size > MAX_LANGUAGES) fail("$BIND languages has more than $MAX_LANGUAGES entries")
            entries.mapIndexed { i, language ->
                if (language.isEmpty()) fail("$BIND languages[$i] is empty")
                if (!LANGUAGE.matches(language)) {
                    fail("$BIND languages[$i] \"$language\" is not a language code such as \"en\" or \"pt_BR\"")
                }
                language
            }
        } ?: emptyList()
        val keypad = parser.getAttributeValue(null, "keypad")?.lowercase(Locale.ROOT) ?: LetterLayout.ANY
        if (keypad != LetterLayout.ANY && keypad !in LetterLayout.KEYPADS) {
            fail("$BIND keypad is \"$keypad\", expected one of ${(LetterLayout.KEYPADS + LetterLayout.ANY).joinToString(", ")}")
        }
        val keypadType = parser.getAttributeValue(null, "keypadType")?.lowercase(Locale.ROOT) ?: LetterLayout.ANY
        if (keypadType != LetterLayout.ANY && keypadType != LetterLayout.KEYPAD_TYPE_THREE_ROW) {
            fail("$BIND keypadType is \"$keypadType\", expected ${LetterLayout.ANY} or ${LetterLayout.KEYPAD_TYPE_THREE_ROW}")
        }
        val layoutSet = parser.getAttributeValue(null, "layoutSet")?.lowercase(Locale.ROOT) ?: LetterLayout.ANY
        if (layoutSet != LetterLayout.ANY && !LAYOUT_SET.matches(layoutSet)) {
            fail("$BIND layoutSet \"$layoutSet\" is not a keyboard layout set name such as \"qwerty\" or \"translit\"")
        }
        rejectChildren(parser, "<$BIND>")
        return LetterLayout.Binding(languages, keypad, keypadType, layoutSet)
    }

    private fun readRows(parser: XmlPullParser): String {
        rejectUnknownAttributes(parser, ROWS_ATTRIBUTES, ROWS)
        val template = parser.getAttributeValue(null, "template") ?: fail("<$ROWS> has no template")
        if (template !in ROWS_TEMPLATES) fail("$ROWS template is \"$template\", expected ${ROWS_TEMPLATES.joinToString(", ")}")
        rejectChildren(parser, "<$ROWS>")
        return template
    }

    private fun readKey(parser: XmlPullParser): Key {
        val code = parser.getAttributeValue(null, "code")
            ?: fail("the <$KEY> on line ${parser.lineNumber} has no code")
        val keyCode = keyCodeOf(code)
            ?: fail("$code is not an Android key code name (named like KEYCODE_Q or KEYCODE_4)")
        rejectUnknownAttributes(parser, KEY_ATTRIBUTES, code)

        val scanCode = parser.getAttributeValue(null, "scanCode")?.let { raw ->
            val value = if (SCAN_CODE.matches(raw)) raw.toIntOrNull() else null
            value ?: fail("$code.scanCode \"$raw\" is not a whole number of 0 or more")
        } ?: -1
        val keySpec = codePoint(parser, "keySpec", code) ?: fail("$code.keySpec is missing")
        val key = Key(
            keyCode = keyCode,
            scanCode = scanCode,
            keySpec = keySpec,
            shift = codePoint(parser, "characterMapKeys", code) ?: 0,
            alt = codePoint(parser, "alt", code) ?: 0,
            multitap = sequence(parser, "multitapKeys", code),
            moreKeys = sequence(parser, "moreKeys", code),
            engineKeys = codePoints(parser, "engineKeys", code),
        )
        rejectChildren(parser, code)
        return key
    }

    private fun codePoint(parser: XmlPullParser, field: String, code: String): Int? =
        parser.getAttributeValue(null, field)?.let { singleCodePoint(it, "$code.$field") }

    /** A [sequence] of single characters: the engine holds one code per letter. */
    private fun codePoints(parser: XmlPullParser, field: String, code: String): IntArray {
        if (parser.getAttributeValue(null, field) == null) return LetterLayout.NO_CODE_POINTS
        return sequence(parser, field, code).map { singleCodePoint(it, "$code.$field") }.toIntArray()
    }

    /** A comma list; only `\,` and `\\` may follow a backslash, so a list has one reading. */
    private fun sequence(parser: XmlPullParser, field: String, code: String): Array<String> {
        val raw = parser.getAttributeValue(null, field) ?: return NO_ENTRIES
        val where = "$code.$field"
        if (raw.isEmpty()) fail("$where is empty")
        val entries = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\\' -> {
                    val next = raw.getOrNull(i + 1)
                    if (next != ',' && next != '\\') fail("$where has a backslash that is not part of \\, or \\\\")
                    current.append(next)
                    i += 2
                }
                c == ',' -> {
                    entries += current.toString()
                    current.setLength(0)
                    i++
                }
                else -> {
                    current.append(c)
                    i++
                }
            }
        }
        entries += current.toString()
        if (entries.size > MAX_SEQUENCE) fail("$where has more than $MAX_SEQUENCE entries")
        entries.forEachIndexed { index, entry -> checkEntry(entry, "$where[$index]") }
        return entries.toTypedArray()
    }

    /**
     * Checks one list entry. A multi-character entry may not begin or end with white space: that is
     * a list with spaces after commas (`ŵ, ẁ`), not a ligature. A lone space is allowed.
     */
    private fun checkEntry(entry: String, where: String) {
        if (entry.isEmpty()) fail("$where is empty")
        val length = entry.codePointCount(0, entry.length)
        if (length > MAX_ENTRY_CODE_POINTS) {
            fail("$where \"$entry\" is longer than $MAX_ENTRY_CODE_POINTS characters")
        }
        var i = 0
        while (i < entry.length) {
            val codePoint = entry.codePointAt(i)
            checkCharacter(codePoint, "$where holds")
            i += Character.charCount(codePoint)
        }
        if (length > 1 && (Character.isWhitespace(entry.codePointAt(0)) || Character.isWhitespace(entry.codePointBefore(entry.length)))) {
            fail("$where \"$entry\" begins or ends with white space; list entries are separated by a comma alone")
        }
    }

    private fun singleCodePoint(value: String, where: String): Int {
        if (value.isEmpty()) fail("$where is empty")
        val codePoint = value.codePointAt(0)
        if (Character.charCount(codePoint) != value.length) {
            fail("$where must be a single character, not \"$value\"")
        }
        checkCharacter(codePoint, "$where is")
        return codePoint
    }

    /** Refuses a control character or a lone surrogate; [subject] is "<field> is" or "<field> holds". */
    private fun checkCharacter(codePoint: Int, subject: String) {
        if (Character.isISOControl(codePoint)) fail("$subject a control character")
        if (Character.getType(codePoint) == Character.SURROGATE.toInt()) fail("$subject half of a character")
    }

    private fun rejectUnknownAttributes(parser: XmlPullParser, known: Set<String>, where: String) {
        for (i in 0 until parser.attributeCount) {
            val name = parser.getAttributeName(i)
            if (name !in known) fail("$where has an unknown attribute \"$name\"")
        }
    }

    /** Reads to the end of the current empty element, refusing child elements and text. */
    private fun rejectChildren(parser: XmlPullParser, where: String) {
        while (true) {
            when (parser.nextToken()) {
                XmlPullParser.END_TAG -> return
                XmlPullParser.START_TAG -> fail("$where may not contain <${parser.name}>")
                XmlPullParser.END_DOCUMENT -> fail("the document ends inside $where")
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> rejectText(parser)
                else -> {}
            }
        }
    }

    /** Refuses non-blank text between elements. */
    private fun rejectText(parser: XmlPullParser) {
        val text = parser.text.orEmpty()
        if (text.isNotBlank()) fail("unexpected text \"${text.trim().take(40)}\" on line ${parser.lineNumber}")
    }

    private fun fail(message: String): Nothing = throw InvalidLetterLayoutException(message)

    /** The document for [layout]; attributes at their default are left out. */
    @JvmStatic
    fun serialize(layout: LetterLayout): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<").append(ROOT)
        attribute("version", VERSION.toString())
        attribute("id", layout.id)
        attribute("name", layout.name)
        if (layout.targets != setOf(Target.PKB)) {
            attribute("targets", Target.entries.filter { it in layout.targets }.joinToString(",") { it.token })
        }
        if (layout.optIn) attribute("optIn", "true")
        append(">\n")

        val binding = layout.binding
        append("    <").append(BIND)
        if (binding.languages.isNotEmpty()) attribute("languages", binding.languages.joinToString(","))
        if (binding.keypad != LetterLayout.ANY) attribute("keypad", binding.keypad)
        if (binding.keypadType != LetterLayout.ANY) attribute("keypadType", binding.keypadType)
        if (binding.layoutSet != LetterLayout.ANY) attribute("layoutSet", binding.layoutSet)
        append("/>\n")

        layout.rowsTemplate?.let { template ->
            append("    <").append(ROWS)
            attribute("template", template)
            append("/>\n")
        }

        layout.keys.values.forEach { key ->
            append("    <").append(KEY)
            attribute("code", nameOf(key.keyCode))
            if (key.scanCode >= 0) attribute("scanCode", key.scanCode.toString())
            attribute("keySpec", LetterLayout.string(key.keySpec))
            if (key.shift != 0) attribute("characterMapKeys", LetterLayout.string(key.shift))
            if (key.alt != 0) attribute("alt", LetterLayout.string(key.alt))
            if (key.multitap.isNotEmpty()) attribute("multitapKeys", list(key.multitap))
            if (key.moreKeys.isNotEmpty()) attribute("moreKeys", list(key.moreKeys))
            if (key.engineKeys.isNotEmpty()) attribute("engineKeys", list(key.engineKeys.map { LetterLayout.string(it) }.toTypedArray()))
            append("/>\n")
        }
        append("</").append(ROOT).append(">\n")
    }

    private fun StringBuilder.attribute(name: String, value: String) {
        append(' ').append(name).append("=\"").append(escape(value)).append('"')
    }

    private fun list(entries: Array<String>): String = entries.joinToString(",") { entry ->
        entry.replace("\\", "\\\\").replace(",", "\\,")
    }

    private fun escape(value: String): String = buildString(value.length) {
        value.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(c)
            }
        }
    }
}
