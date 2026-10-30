package dev.omalauncher.pp.jobs

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** How a run ended. */
enum class Outcome(
    val key: String,
) {
    Ok("ok"),
    Failed("failed"),

    /** Ran out of its timeout. */
    TimedOut("timeout"),

    /** Stopped from the jobs screen. */
    Stopped("stopped"),
    ;

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }

        /** What [exit] means, for a run that wasn't [stopped]: timeout's 124 is a timeout. */
        fun of(
            exit: Int,
            stopped: Boolean,
        ) = when {
            stopped -> Stopped
            exit == 0 -> Ok
            exit == 124 || exit == 137 -> TimedOut
            else -> Failed
        }
    }
}

/** One finished run, as history.json lists them. Times are Unix seconds, as owl's. */
data class JobRun(
    val started: Long,
    val finished: Long,
    val outcome: Outcome,
    /** Null when it never started (the agent isn't installed). */
    val exit: Int?,
    val summary: String,
    /** Started by hand rather than by its schedule. */
    val manual: Boolean = false,
) {
    val duration get() = (finished - started).coerceAtLeast(0)

    fun toJson(): JSONObject =
        JSONObject()
            .put("started", started)
            .put("finished", finished)
            .put("duration", duration)
            .put("result", outcome.key)
            .put("exit", exit ?: JSONObject.NULL)
            .put("summary", summary)
            .put("manual", manual)

    companion object {
        fun of(o: JSONObject): JobRun? {
            val outcome = Outcome.of(o.optString("result")) ?: return null
            return JobRun(
                started = o.optLong("started"),
                finished = o.optLong("finished"),
                outcome = outcome,
                exit = if (o.isNull("exit")) null else o.optInt("exit"),
                summary = o.optString("summary"),
                manual = o.optBoolean("manual"),
            )
        }

        /** history.json, newest first; entries that don't read are skipped. */
        fun list(text: String?): List<JobRun> {
            val array = runCatching { JSONArray(text ?: return emptyList()) }.getOrNull() ?: return emptyList()
            return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(::of) }
        }

        fun listJson(runs: List<JobRun>) = JSONArray().apply { runs.forEach { put(it.toJson()) } }
    }
}

/** What a job is doing now. */
enum class Activity(
    val key: String,
) {
    Idle("idle"),

    /** Due, and waiting for its turn: one job runs at a time. */
    Queued("queued"),
    Running("running"),

    /** Due, and waiting for Wi-Fi, a charger or Linux; [JobStatus.reason] says which. */
    Waiting("waiting"),
}

/**
 * A job's status.json, also one entry of the jobs folder's status.json that widgets read: the job, what it's doing,
 * when it runs next, and its last run with the end of its output.
 */
data class JobStatus(
    val activity: Activity = Activity.Idle,
    val reason: String? = null,
    val next: Long? = null,
    val last: JobRun? = null,
    /** The end of the last run's output. */
    val output: String = "",
) {
    fun toJson(job: Job): JSONObject =
        JSONObject()
            .put("id", job.id)
            .put("name", job.name)
            .put("agent", job.agent ?: JSONObject.NULL)
            .put("schedule", job.schedule?.text ?: Job.MANUAL)
            .put("enabled", job.enabled)
            .put("state", activity.key)
            .put("reason", reason ?: JSONObject.NULL)
            .put("next", next ?: JSONObject.NULL)
            .put("result", last?.outcome?.key ?: JSONObject.NULL)
            .put("exit", last?.exit ?: JSONObject.NULL)
            .put("started", last?.started ?: JSONObject.NULL)
            .put("finished", last?.finished ?: JSONObject.NULL)
            .put("duration", last?.duration ?: JSONObject.NULL)
            .put("summary", last?.summary ?: "")
            .put("output", output)

    companion object {
        /** What widgets get of a run's output, from its end: a morning summary fits, a whole build log doesn't. */
        const val OUTPUT_CHARS = 4000

        /** A status.json as written by [toJson]; the last run and its output, which is what outlives the process. */
        fun of(text: String?): JobStatus? {
            val o = runCatching { JSONObject(text ?: return null) }.getOrNull() ?: return null
            val last =
                Outcome.of(o.optString("result"))?.let {
                    JobRun(
                        started = o.optLong("started"),
                        finished = o.optLong("finished"),
                        outcome = it,
                        exit = if (o.isNull("exit")) null else o.optInt("exit"),
                        summary = o.optString("summary"),
                    )
                }
            return JobStatus(last = last, output = o.optString("output"))
        }
    }
}

/** When jobs are due: pure, so the rules can be tested. */
object JobPlan {
    /** The zone [job]'s schedule is in: its own tz, else the phone's. */
    fun zoneOf(
        job: Job,
        phone: ZoneId,
    ): ZoneId = job.zone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: phone

    /** Its next scheduled run after [after]; null for a job that's off or only run by hand. */
    fun next(
        job: Job,
        after: Instant,
        phone: ZoneId,
    ): Instant? = if (!job.enabled) null else job.schedule?.next(after, zoneOf(job, phone))

    /**
     * Whether [job] is due at [now], having last been handled at [after]: a run came between. Runs missed while the
     * phone was off, Linux was shut down or a condition wasn't met are made up once, not once each.
     */
    fun due(
        job: Job,
        after: Instant,
        now: Instant,
        phone: ZoneId,
    ): Boolean = next(job, after, phone)?.let { it <= now } == true

    /**
     * When to look at [job] again: its next run, or for one that's [due] and waiting, the run after now, so a run
     * that is still waiting then is looked at again (and is still one run).
     */
    fun wake(
        job: Job,
        after: Instant,
        now: Instant,
        phone: ZoneId,
    ): Instant? {
        val next = next(job, after, phone) ?: return null
        return if (next > now) next else next(job, now, phone)
    }

    /** Where the last run was handled, for a job seen first at [now], or after the clock was put back. */
    fun after(
        saved: Instant?,
        now: Instant,
    ): Instant = if (saved == null || saved > now) now else saved
}
