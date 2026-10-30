package dev.omalauncher.pp.theme

/** style.toml's `[agents]`: coding agents reporting to Android (see [dev.omalauncher.pp.AgentStatus]). */
object AgentStatusStyle {
    val STATUS =
        Setting.Flag(
            "agents.status",
            true,
            "Coding agents in terminals report what they do (working, waiting for you, asking permission, done) to a notification per " +
                "session and to android.agents' sessions; their hooks go into ~/.claude/settings.json and ~/.codex/hooks.json. " +
                "false takes them out again.",
        )
    val APPROVE_SECONDS =
        Setting.Count(
            "agents.approve-seconds",
            120,
            10..1800,
            "How long an agent's permission request waits for Allow or Deny from its notification before the agent asks in the terminal.",
        )

    val ALL: List<Setting<*>> = listOf(STATUS, APPROVE_SECONDS)
}
