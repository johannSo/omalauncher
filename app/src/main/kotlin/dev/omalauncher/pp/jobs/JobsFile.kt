package dev.omalauncher.pp.jobs

import dev.omalauncher.pp.theme.Toml

/** When a job's notification goes out. */
enum class Notify(
    val key: String,
) {
    Always("always"),
    Failure("failure"),
    Never("never"),
}

/**
 * What an agent may do in a job, with nobody there to answer its questions; see [headlessInvocation] for what each
 * agent is started with.
 */
enum class Permissions(
    val key: String,
) {
    /** The agent's own non-interactive defaults: what it would ask about is refused, where it has a way to say so. */
    Safe("safe"),

    /** Every permission prompt skipped: the agent can run any command and change any file in Linux. */
    Full("full"),
}

/** A scheduled job, one `[[job]]` of jobs.toml. */
data class Job(
    /** Letters, digits, - and _: the name of its folder of results. */
    val id: String,
    val name: String = id,
    /** The agent's command (claude, codex); null for the launcher's default agent. */
    val agent: String? = null,
    val prompt: String = "",
    /** A file holding the prompt, relative to the jobs folder or from ~ or /; read as the job starts. */
    val promptFile: String? = null,
    /** A shell command run instead of an agent, with the prompt in $JOB_PROMPT: for agents without a known non-interactive mode. */
    val command: String? = null,
    /** Null runs it only by hand ("manual"). */
    val schedule: Schedule? = null,
    /** Where it runs, a guest path; ~ is the home. */
    val dir: String = "~",
    /** A time zone id for the schedule (Europe/Berlin); null follows the phone's. */
    val zone: String? = null,
    val wifi: Boolean = false,
    val charging: Boolean = false,
    val notify: Notify = Notify.Always,
    val permissions: Permissions = Permissions.Safe,
    val timeoutMinutes: Int = DEFAULT_TIMEOUT_MINUTES,
    val enabled: Boolean = true,
) {
    companion object {
        const val DEFAULT_TIMEOUT_MINUTES = 30
        const val MANUAL = "manual"
    }
}

/** jobs.toml read: the jobs that are fine, and why the others (or parts of them) were left out. */
data class JobsRead(
    val jobs: List<Job>,
    val problems: List<String>,
)

/**
 * jobs.toml, ~/.config/omalauncher/jobs/jobs.toml in Linux: the jobs as `[[job]]` tables, which agents and the jobs
 * screen both edit. Unknown keys are kept, so a newer launcher's settings survive an edit on an older one.
 */
object JobsFile {
    const val NAME = "jobs.toml"

    /** The array of tables the jobs are in. */
    const val TABLE = "job"

    val ID = Regex("[A-Za-z0-9_-]{1,64}")

    private val KNOWN =
        setOf("id", "name", "agent", "prompt", "prompt_file", "command", "schedule", "dir", "tz", "wifi", "charging", "notify", "permissions", "timeout", "enabled")

    const val HEADER =
        "# Scheduled agent jobs, run by OmaLauncher++ with nobody there to answer the agent.\n" +
            "# README.md next to this file explains every key; the jobs screen (agents › jobs) edits this file too.\n\n"

    private val DURATION = Regex("""(\d+)\s*(s|m|h)?""")

    /** Reads [text]; a file that isn't TOML throws [Toml.Error]. */
    fun read(text: String): JobsRead = read(Toml.parse(text))

    fun read(table: Map<String, Any>): JobsRead {
        val problems = ArrayList<String>()
        for (key in table.keys) if (key != TABLE) problems += "$key: unknown; jobs go in [[$TABLE]] tables"
        val entries = (table[TABLE] as? List<*>).orEmpty()
        if (table.containsKey(TABLE) && entries.any { it !is Map<*, *> }) problems += "$TABLE: write each job as a [[$TABLE]] table"
        val jobs = ArrayList<Job>()
        entries.filterIsInstance<Map<*, *>>().forEachIndexed { i, raw ->
            @Suppress("UNCHECKED_CAST")
            val entry = raw as Map<String, Any>
            val where = "job ${i + 1}" + ((entry["id"] ?: entry["name"]) as? String)?.let { " ($it)" }.orEmpty()
            try {
                val job = job(entry) { problems += "$where: $it" }
                if (jobs.any { it.id == job.id }) throw Invalid("id ${job.id} is taken by another job")
                jobs += job
            } catch (e: Invalid) {
                problems += "$where: ${e.message}; the job was left out"
            } catch (e: Schedule.Error) {
                problems += "$where: schedule: ${e.message}; the job was left out"
            }
        }
        return JobsRead(jobs, problems)
    }

    /** Why a job was left out. */
    private class Invalid(
        message: String,
    ) : Exception(message)

    /** One job; throws [Invalid] or [Schedule.Error] for what leaves it out, and tells [warn] about what it ignored. */
    private fun job(
        e: Map<String, Any>,
        warn: (String) -> Unit,
    ): Job {
        for (key in e.keys) if (key !in KNOWN) warn("$key: unknown, ignored")
        fun string(key: String): String? =
            when (val v = e[key]) {
                null -> null
                is String -> v.takeIf { it.isNotBlank() }
                else -> throw Invalid("$key must be text, in quotes")
            }
        fun flag(
            key: String,
            default: Boolean,
        ): Boolean =
            when (val v = e[key]) {
                null -> default
                is Boolean -> v
                else -> throw Invalid("$key is true or false")
            }
        val name = string("name")
        val id = string("id") ?: name?.let(::slug)?.takeIf { it.isNotEmpty() } ?: throw Invalid("give it an id")
        if (!ID.matches(id)) throw Invalid("id \"$id\" may only have letters, digits, - and _")
        val prompt = string("prompt").orEmpty()
        val promptFile = string("prompt_file")
        val command = string("command")
        if (prompt.isEmpty() && promptFile == null && command == null) throw Invalid("it needs a prompt (or prompt_file, or command)")
        val scheduleText = string("schedule") ?: throw Invalid("it needs a schedule, like \"daily at 07:00\", or \"${Job.MANUAL}\"")
        val schedule = if (scheduleText.trim().equals(Job.MANUAL, ignoreCase = true)) null else Schedule.parse(scheduleText)
        val zone = string("tz")
        if (zone != null && runCatching { java.time.ZoneId.of(zone) }.isFailure) throw Invalid("tz \"$zone\" isn't a time zone, like Europe/Berlin")
        val notify = string("notify")?.let { v -> Notify.entries.firstOrNull { it.key == v } ?: throw Invalid("notify is always, failure or never") } ?: Notify.Always
        val permissions =
            string("permissions")?.let { v -> Permissions.entries.firstOrNull { it.key == v } ?: throw Invalid("permissions is safe or full") } ?: Permissions.Safe
        val timeout =
            when (val v = e["timeout"]) {
                null -> Job.DEFAULT_TIMEOUT_MINUTES
                is Double -> v.toInt()
                is String -> minutes(v) ?: throw Invalid("timeout \"$v\" isn't a duration, like \"30m\" or \"2h\"")
                else -> throw Invalid("timeout is a duration, like \"30m\"")
            }
        if (timeout !in 1..24 * 60) throw Invalid("timeout is from 1 minute to 24 hours")
        return Job(
            id = id,
            name = name ?: id,
            agent = string("agent"),
            prompt = prompt,
            promptFile = promptFile,
            command = command,
            schedule = schedule,
            dir = string("dir") ?: "~",
            zone = zone,
            wifi = flag("wifi", false),
            charging = flag("charging", false),
            notify = notify,
            permissions = permissions,
            timeoutMinutes = timeout,
            enabled = flag("enabled", true),
        )
    }

    /** "90s", "30m", "2h" or a bare number of minutes, as whole minutes (at least one). */
    fun minutes(text: String): Int? {
        val m = DURATION.matchEntire(text.trim().lowercase()) ?: return null
        val n = m.groupValues[1].toIntOrNull() ?: return null
        return when (m.groupValues[2]) {
            "s" -> maxOf(1, (n + 59) / 60)
            "h" -> n * 60
            else -> n
        }
    }

    /** A name as an id: "Morning summary" is morning-summary. */
    fun slug(name: String) = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(64)

    /** An id like [base] that none of [taken] has. */
    fun freeId(
        base: String,
        taken: Collection<String>,
    ): String {
        val stem = slug(base).ifEmpty { "job" }.take(60)
        if (stem !in taken) return stem
        return (2..999).map { "$stem-$it" }.first { it !in taken }
    }

    /** [job] as a `[[job]]` table, over [old] (its table before), keeping the keys this launcher doesn't know. */
    fun encode(
        job: Job,
        old: Map<String, Any> = emptyMap(),
    ): LinkedHashMap<String, Any> {
        val out = LinkedHashMap<String, Any>()
        out["id"] = job.id
        if (job.name != job.id) out["name"] = job.name
        job.agent?.let { out["agent"] = it }
        out["schedule"] = job.schedule?.text ?: Job.MANUAL
        out["dir"] = job.dir
        if (job.prompt.isNotEmpty() || (job.promptFile == null && job.command == null)) out["prompt"] = job.prompt
        job.promptFile?.let { out["prompt_file"] = it }
        job.command?.let { out["command"] = it }
        job.zone?.let { out["tz"] = it }
        out["wifi"] = job.wifi
        out["charging"] = job.charging
        out["notify"] = job.notify.key
        out["permissions"] = job.permissions.key
        out["timeout"] = if (job.timeoutMinutes % 60 == 0) "${job.timeoutMinutes / 60}h" else "${job.timeoutMinutes}m"
        out["enabled"] = job.enabled
        for ((k, v) in old) if (k !in KNOWN) out[k] = v
        return out
    }

    /**
     * [table] (jobs.toml as read) with the job [id] replaced by [job], or added at the end if there's none; a null
     * [job] removes it. Other jobs, broken ones too, stay as they were.
     */
    fun update(
        table: Map<String, Any>,
        id: String,
        job: Job?,
    ): LinkedHashMap<String, Any> {
        val out = LinkedHashMap(table)
        val entries = (table[TABLE] as? List<*>).orEmpty().toMutableList()
        @Suppress("UNCHECKED_CAST")
        val at = entries.indexOfFirst { (it as? Map<String, Any>)?.let(::idOf) == id }
        when {
            job == null -> if (at >= 0) entries.removeAt(at)
            at >= 0 -> {
                @Suppress("UNCHECKED_CAST")
                entries[at] = encode(job, entries[at] as Map<String, Any>)
            }
            else -> entries += encode(job)
        }
        if (entries.isEmpty()) out.remove(TABLE) else out[TABLE] = entries
        return out
    }

    /** The id a table has, or would get from its name. */
    private fun idOf(entry: Map<String, Any>) = (entry["id"] as? String)?.takeIf { it.isNotBlank() } ?: (entry["name"] as? String)?.let(::slug)

    fun write(table: Map<String, Any>) = Toml.write(table, HEADER)
}
