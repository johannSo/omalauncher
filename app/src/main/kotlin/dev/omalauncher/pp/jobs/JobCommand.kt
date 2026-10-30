package dev.omalauncher.pp.jobs

import dev.omalauncher.pp.linux.shellQuote

/** The agents a job can run without a terminal, by their command; the others need a job's `command`. */
val HEADLESS_AGENTS = listOf("claude", "codex", "opencode", "copilot", "cursor-agent", "kimi", "pi", "omp")

/**
 * The command that runs [agentBin] on [prompt] with nobody to answer it, printing its answer and exiting; null for an
 * agent with no non-interactive mode the launcher knows. [permissions] picks between the agent's own non-interactive
 * defaults and skipping every permission prompt:
 *
 * - Claude Code: `-p` with `--permission-mode dontAsk`, which refuses whatever isn't allowed in its settings; full is
 *   `--dangerously-skip-permissions`, which Claude only takes as root (the guest's user) with IS_SANDBOX=1.
 * - Codex: `exec`, which never asks, outside a git repository too. Safe is its read-only sandbox, which can't start
 *   under proot, so its commands fail and it answers from what it knows; full bypasses approvals and the sandbox,
 *   as Codex runs in the launcher's terminals anyway (see codexConfig in Agents.kt).
 * - GitHub Copilot: `-p`, refusing tools it would ask about; full adds `--allow-all-tools`.
 * - Cursor CLI: `-p`, proposing but not running commands; full adds `--force`.
 * - Kimi: `--prompt`, its print mode; full adds `--yolo`.
 * - OpenCode (`run`), Pi and Oh My Pi (`-p`): the same either way; OpenCode follows the permissions in its own
 *   config, and the Pis ask about nothing.
 */
fun headlessInvocation(
    agentBin: String,
    prompt: String,
    permissions: Permissions,
): String? {
    // A prompt starting with a dash would be read as an option; a space in front isn't (as in agentInvocation).
    val q = shellQuote(if (prompt.startsWith("-")) " $prompt" else prompt)
    val full = permissions == Permissions.Full
    return when (agentBin) {
        "claude" -> if (full) "IS_SANDBOX=1 claude -p --dangerously-skip-permissions $q" else "claude -p --permission-mode dontAsk $q"
        "codex" -> "codex exec --skip-git-repo-check " + (if (full) "--dangerously-bypass-approvals-and-sandbox" else "--sandbox read-only") + " $q"
        "opencode" -> "opencode run $q"
        "copilot" -> "copilot -p $q" + if (full) " --allow-all-tools" else ""
        "cursor-agent" -> "cursor-agent -p --output-format text" + (if (full) " --force" else "") + " $q"
        "kimi" -> "kimi" + (if (full) " --yolo" else "") + " --prompt $q"
        "pi", "omp" -> "$agentBin -p $q"
        else -> null
    }
}

/**
 * The guest script a run of [job] is: into its folder, its variables set, then [invocation] under `timeout`, with
 * no input (an agent waiting on stdin would wait out the timeout) and no colors in the output it keeps.
 * [results] is the guest path of the job's folder of results, [prompt] the prompt as read from prompt_file if it has one.
 */
fun jobScript(
    job: Job,
    invocation: String,
    prompt: String,
    results: String,
): String {
    val seconds = job.timeoutMinutes * 60
    return buildString {
        append("export JOB_ID=").append(shellQuote(job.id))
        append(" JOB_NAME=").append(shellQuote(job.name))
        append(" JOB_PROMPT=").append(shellQuote(prompt))
        append(" JOB_RESULTS=").append(shellQuote(results))
        append(" TERM=dumb NO_COLOR=1\n")
        append("cd ").append(guestDirWord(job.dir)).append(" || exit 1\n")
        // -k: an agent that ignores the TERM goes 10 s later; 124 is timeout's exit for one that ran out of time.
        append("exec timeout -k 10 ").append(seconds).append(" sh -c ").append(shellQuote(invocation)).append(" </dev/null\n")
    }
}

/** [dir] as one shell word, a leading ~ as the home. */
internal fun guestDirWord(dir: String): String =
    when {
        dir == "~" || dir.isBlank() -> "\"\$HOME\""
        dir.startsWith("~/") -> "\"\$HOME\"/" + shellQuote(dir.removePrefix("~/"))
        else -> shellQuote(dir)
    }

/** ANSI escapes that slip past NO_COLOR: colors, cursor moves, OSC titles. */
private val ANSI = Regex("""\u001B(?:\[[0-?]*[ -/]*[@-~]|\][^\u0007\u001B]*(?:\u0007|\u001B\\)|[@-Z\\-_])""")

/** A line of output as it's kept: without escapes, and as a progress bar's last redraw ended. */
fun cleanLine(raw: String): String = ANSI.replace(raw.trimEnd('\r').substringAfterLast('\r'), "")

/** The end of [output] that says how it went: its last non-blank lines, up to [max] characters. */
fun summaryOf(
    output: String,
    lines: Int = 3,
    max: Int = 300,
): String {
    val last = output.lines().map { it.trim() }.filter { it.isNotEmpty() }.takeLast(lines).joinToString("\n")
    return if (last.length > max) "…" + last.takeLast(max - 1) else last
}
