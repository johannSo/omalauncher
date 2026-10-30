package dev.omalauncher.pp.widgets.lang

/**
 * What `intent(...)` and `broadcast(...)` send, worked out from the widget's values when the handler runs. The
 * launcher makes an Android Intent of it (WidgetServices); here it's plain data, so the owl module needn't know
 * Android and the rules can be tested without it.
 */
class IntentSpec(
    /** broadcast(...); intent(...) starts an activity. */
    val broadcast: Boolean,
    val action: String?,
    val pkg: String?,
    /** "pkg/cls", or "pkg/.Cls" for a class in pkg, as ComponentName.unflattenFromString reads it. */
    val component: String?,
    val data: String?,
    val type: String?,
    val categories: List<String>,
    val extras: List<Extra>,
) {
    override fun toString() =
        listOfNotNull(
            if (broadcast) "broadcast" else "intent",
            action,
            pkg?.let { "package $it" },
            component?.let { "component $it" },
            data?.let { "data $it" },
            type?.let { "type $it" },
            categories.takeIf { it.isNotEmpty() }?.let { "categories ${it.joinToString(",")}" },
            extras.takeIf { it.isNotEmpty() }?.let { "extras ${it.joinToString(", ")}" },
        ).joinToString(" ")
}

/** One extra of an intent, typed as Android's getXxxExtra reads it. [value] is a String, Int, Long, Float, Double, Boolean, List<String> or, for a bundle, List<Extra>. */
class Extra(
    val name: String,
    val type: ExtraType,
    val value: Any,
) {
    override fun toString() = "$name:${type.word}=" + if (value is List<*>) value.joinToString(",", "[", "]") else value.toString()
}

/** The types an extra can have, by the word after a `:` in its key: `{"level:int": 3}`. */
enum class ExtraType(
    val word: String,
) {
    STRING("string"),
    INT("int"),
    LONG("long"),
    FLOAT("float"),
    DOUBLE("double"),
    BOOL("bool"),

    /** A String[], getStringArrayExtra. */
    STRINGS("strings"),

    /** An ArrayList<String>, getStringArrayListExtra. */
    STRING_LIST("stringList"),

    /** A map, as a Bundle of its own. */
    BUNDLE("bundle"),
    ;

    companion object {
        fun of(word: String) = entries.firstOrNull { it.word == word }
    }
}

object Intents {
    /** An intent action or category: dotted names, like net.dinglisch.android.tasker.ACTION_TASK. */
    val ACTION = Regex("""[A-Za-z_][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*""")

    /** An Android package name. */
    val PACKAGE = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)*""")

    /** A component, "pkg/cls" or "pkg/.Cls". */
    val COMPONENT = Regex("""${PACKAGE.pattern}/\.?[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*""")

    /**
     * The launcher's own package and actions: a widget can't start its activities, reach its receivers (which
     * take broadcasts from the app itself, widgets included) or listen for its actions, among them [SET]'s.
     */
    const val OWN = "dev.omalauncher"

    /** What other apps send to put a value into a widget's `source x = external`; extras widget, key and value. */
    const val SET = "dev.omalauncher.pp.action.SET"

    /** At most this many extras in one intent, bundles' own counted, and this deep. */
    const val MAX_EXTRAS = 64
    private const val MAX_DEPTH = 4

    /** An `external` source keeps at most this many keys per widget, and values of at most [MAX_OUTSIDE_TEXT] characters. */
    const val MAX_OUTSIDE_KEYS = 64
    const val MAX_OUTSIDE_TEXT = 16 * 1024

    /** A key other apps set: a name a widget reads as `ext.name`. */
    val OUTSIDE_KEY = Regex("""[A-Za-z_][A-Za-z0-9_]{0,63}""")

    /** Whether [name], an action, package or component, is the launcher's own; see [OWN]. */
    fun own(name: String) = name == OWN || name.startsWith("$OWN.") || name.startsWith("$OWN/")

    /**
     * Why [text] can't be an intent's [part] (its action, a category, its package or component), or null when it can:
     * the compiler asks for what's written out, the runtime for what values made.
     */
    fun problem(
        part: String,
        text: String,
    ): String? =
        when (part) {
            "action", "category" ->
                when {
                    !ACTION.matches(text) -> "$part '$text' isn't a dotted name, like android.intent.action.VIEW"
                    own(text) -> "$part '$text' is the launcher's own, which widgets can't send"
                    else -> null
                }
            "package" ->
                when {
                    !PACKAGE.matches(text) -> "package '$text' isn't a package name, like org.example.app"
                    own(text) -> "package '$text' is the launcher itself, which widgets can't reach this way"
                    else -> null
                }
            "component" ->
                when {
                    !COMPONENT.matches(text) -> "component '$text' isn't a package and a class, like org.example.app/.MainActivity"
                    own(text) -> "component '$text' is the launcher's own, which widgets can't reach this way"
                    else -> null
                }
            else -> null
        }

    /** Why [spec] can't go out, or null when it can; see [problem]. */
    fun check(spec: IntentSpec): String? =
        (listOf("action" to spec.action, "package" to spec.pkg, "component" to spec.component) + spec.categories.map { "category" to it })
            .firstNotNullOfOrNull { (part, text) -> text?.let { problem(part, it) } }
            ?: when {
                spec.broadcast && spec.action == null -> "a broadcast needs an action"
                !spec.broadcast && spec.action == null && spec.pkg == null && spec.component == null && spec.data == null -> "an intent needs an action, a package, a component or data"
                else -> null
            }

    /**
     * The extras a map gives, by its keys: `{title: "hi", "level:int": 3}`. A key without a type takes its value's:
     * text a string, true or false a bool, a whole number an int (a long past an int's range), any other number a
     * float, a list a string array and a map a bundle. Null values are left out. Throws a [WidgetError] that says
     * what doesn't fit.
     */
    fun extras(v: Value): List<Extra> {
        if (v == Null) return emptyList()
        val o = v as? Obj ?: throw WidgetError("extras are a map, like {title: \"hi\", \"level:int\": 3}")
        var count = 0
        fun of(
            o: Obj,
            depth: Int,
        ): List<Extra> =
            o.fields.mapNotNull { (key, value) ->
                if (value == Null) return@mapNotNull null
                if (++count > MAX_EXTRAS) throw WidgetError("an intent takes at most $MAX_EXTRAS extras")
                val typed = key.substringAfterLast(':', "").let(ExtraType::of)
                val name = if (typed != null) key.substringBeforeLast(':') else key
                if (name.isEmpty()) throw WidgetError("an extra needs a name before its type, like \"level:int\"")
                val type = typed ?: inferred(value)
                if (type == ExtraType.BUNDLE && depth >= MAX_DEPTH) throw WidgetError("extras nest at most $MAX_DEPTH deep")
                Extra(name, type, convert(name, type, value) { of(it, depth + 1) })
            }
        return of(o, 0)
    }

    private fun inferred(v: Value): ExtraType =
        when (v) {
            is Bool -> ExtraType.BOOL
            is Num -> if (v.v == Math.rint(v.v) && !v.v.isInfinite()) (if (v.v >= Int.MIN_VALUE && v.v <= Int.MAX_VALUE) ExtraType.INT else ExtraType.LONG) else ExtraType.FLOAT
            is Lst -> ExtraType.STRINGS
            is Obj -> ExtraType.BUNDLE
            else -> ExtraType.STRING
        }

    private fun convert(
        name: String,
        type: ExtraType,
        v: Value,
        bundle: (Obj) -> List<Extra>,
    ): Any {
        fun wrong(what: String): Nothing = throw WidgetError("extra $name:${type.word} takes $what, not ${if (v is Str) "\"$v\"" else v.toString()}")
        if (v is Col || v is Fn || v is Gradient) wrong("text, numbers, true or false, lists and maps")
        return when (type) {
            ExtraType.STRING -> v.toString()
            ExtraType.INT -> v.num()?.takeIf { it == Math.rint(it) && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.toInt() ?: wrong("a whole number")
            ExtraType.LONG -> v.num()?.takeIf { it == Math.rint(it) && !it.isInfinite() }?.toLong() ?: wrong("a whole number")
            ExtraType.FLOAT -> v.num()?.toFloat() ?: wrong("a number")
            ExtraType.DOUBLE -> v.num() ?: wrong("a number")
            ExtraType.BOOL ->
                when {
                    v is Bool -> v.v
                    v is Str && v.v == "true" -> true
                    v is Str && v.v == "false" -> false
                    else -> wrong("true or false")
                }
            ExtraType.STRINGS, ExtraType.STRING_LIST -> ((v as? Lst)?.items ?: listOf(v)).map { if (it is Lst || it is Obj) wrong("a list of text") else it.toString() }
            ExtraType.BUNDLE -> bundle(v as? Obj ?: wrong("a map"))
        }
    }

    /**
     * What a broadcast's extras, made plain by the launcher (a Bundle as a Map, arrays as lists), are as owl values:
     * numbers, text, true or false, lists and maps; anything else as its text, and null left out.
     */
    fun value(
        x: Any?,
        depth: Int = 0,
    ): Value =
        when (x) {
            null -> Null
            is Value -> x
            is String -> Str(x)
            is CharSequence -> Str(x.toString())
            is Boolean -> bool(x)
            is Char -> Str(x.toString())
            is Number -> Num(x.toDouble())
            is Map<*, *> -> if (depth >= MAX_DEPTH) Null else Obj(x.entries.filter { it.value != null }.associate { (k, v) -> k.toString() to value(v, depth + 1) })
            is Collection<*> -> if (depth >= MAX_DEPTH) Null else Lst(x.take(Builtins.MAX_LIST).map { value(it, depth + 1) })
            is Array<*> -> value(x.asList(), depth)
            is IntArray -> value(x.asList(), depth)
            is LongArray -> value(x.asList(), depth)
            is FloatArray -> value(x.asList(), depth)
            is DoubleArray -> value(x.asList(), depth)
            is BooleanArray -> value(x.asList(), depth)
            is ShortArray -> value(x.asList(), depth)
            is ByteArray -> value(x.asList(), depth)
            is CharArray -> Str(String(x))
            else -> Str(x.toString())
        }

    /** A received broadcast as a source's value: `{action, data, extras}`. */
    fun received(
        action: String?,
        data: String?,
        extras: Map<String, Any?>,
    ): Value = Obj(mapOf("action" to (action?.let(::Str) ?: Null), "data" to (data?.let(::Str) ?: Null), "extras" to value(extras)))

    /**
     * The values of a widget's `external` source after another app set [key] to [value] (null removes it), or why it
     * was turned down. Text that reads as JSON is JSON, as `owl-send … set` takes it, so Tasker's "42" is a number.
     */
    fun setOutside(
        current: Map<String, Value>,
        key: String?,
        value: Any?,
    ): Result<Map<String, Value>> {
        if (key == null || !OUTSIDE_KEY.matches(key)) return Result.failure(WidgetError("the key is a name of letters, digits and _, up to 64 long, not ${key?.let { "\"$it\"" } ?: "missing"}"))
        if (value == null) return Result.success(current - key)
        val v =
            when (value) {
                is String -> if (value.length > MAX_OUTSIDE_TEXT) return Result.failure(WidgetError("the value is over $MAX_OUTSIDE_TEXT characters")) else Json.parseOrText(value)
                else -> value(value)
            }
        if (key !in current && current.size >= MAX_OUTSIDE_KEYS) return Result.failure(WidgetError("the widget already has $MAX_OUTSIDE_KEYS values from other apps"))
        return Result.success(current + (key to v))
    }
}
