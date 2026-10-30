package dev.omalauncher.pp.mcp

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import dev.omalauncher.pp.BuildConfig
import dev.omalauncher.pp.Runtime
import dev.omalauncher.pp.linux.Agent
import dev.omalauncher.pp.linux.Proot
import dev.omalauncher.pp.upgrade.Managed
import dev.omalauncher.pp.upgrade.Policy
import dev.omalauncher.pp.upgrade.writeAtomically
import dev.omalauncher.pp.widgets.HomeStore
import dev.omalauncher.pp.widgets.WidgetEngine
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.concurrent.thread

private const val TAG = "mcp"

/**
 * The launcher as an MCP server for the coding agents in Linux, so they can change home, widgets, the theme and the
 * style through tools that check what they write ([LauncherTools]) rather than only by editing files.
 *
 * The launcher serves it; `omalauncher-mcp` in the guest is the stdio end agents start. It makes a folder with two
 * FIFOs in the runtime dir and names it on xdg-open's FIFO ([OpenBridge][dev.omalauncher.pp.linux.OpenBridge]); the
 * launcher then reads requests from one and answers on the other until the agent closes its end. Nothing to install
 * in the guest, and nothing listens on the network.
 */
class McpBridge(
    private val context: Context,
    private val proot: Proot,
    private val runtime: Runtime,
) {
    private val server by lazy { McpServer(LauncherTools(context, runtime).all(), BuildConfig.VERSION_NAME, INSTRUCTIONS) }

    /** Serves the session whose FIFOs are in [dir] (a host path), on a thread of its own; see the shim in [managed]. */
    fun serve(dir: File) {
        // Only the shim's folders: the guest names the path, and it may name anything.
        val runtimeDir = proot.runtimeDir.canonicalFile
        if (dir.canonicalFile.parentFile != runtimeDir || !dir.name.startsWith(DIR_PREFIX)) {
            Log.w(TAG, "ignoring session in $dir")
            return
        }
        thread(name = "mcp-session", isDaemon = true) {
            val requests = File(dir, "in")
            val answers = File(dir, "out")
            if (!isFifo(requests) || !isFifo(answers)) {
                Log.w(TAG, "no FIFOs in $dir")
                return@thread
            }
            try {
                // The shim opens both ends at once, so the order here can't deadlock.
                FileInputStream(requests).use { input -> FileOutputStream(answers).use { output -> server.serve(input, output) } }
            } catch (e: Exception) {
                Log.w(TAG, "session in $dir ended", e)
            }
        }
    }

    private fun isFifo(file: File) = runCatching { OsConstants.S_ISFIFO(Os.lstat(file.path).st_mode) }.getOrDefault(false)

    /**
     * `omalauncher-mcp`. Both FIFOs are opened together (cat in the background reads answers), so the launcher can
     * open them in either order; stdin closing ends the session, and the launcher closing its end ends the shim.
     */
    fun managed(guestFifo: String): List<Managed> =
        listOf(
            Managed.text(File(proot.rootfs, "usr/local/bin/$COMMAND"), Policy.Owned, executable = true) {
                """
                #!/bin/bash
                # omalauncher: $COMMAND, OmaLauncher++'s MCP server for coding agents, on stdin and stdout. The launcher
                # serves it through two FIFOs it's told about on xdg-open's FIFO (see McpBridge.kt).
                set -u
                if [ -t 0 ] || [ "${'$'}{1:-}" = -h ] || [ "${'$'}{1:-}" = --help ]; then
                  echo "usage: $COMMAND (started by coding agents: the Model Context Protocol on stdin and stdout)" >&2
                  exit 2
                fi
                dir=${'$'}(mktemp -d ${Proot.GUEST_RUNTIME_DIR}/${DIR_PREFIX}XXXXXX) || exit 1
                reader=
                trap '[ -n "${'$'}reader" ] && kill "${'$'}reader" 2>/dev/null; rm -rf "${'$'}dir"' EXIT
                trap 'exit 143' INT TERM HUP
                mkfifo -m 600 "${'$'}dir/in" "${'$'}dir/out" || exit 1
                # No FIFO there: the write would make a file of it and the launcher would never answer.
                if [ ! -p $guestFifo ] || ! timeout 2 sh -c 'printf "%s\n" "${'$'}1" > $guestFifo' sh "$PREFIX${'$'}dir"; then
                  echo "$COMMAND: the launcher didn't answer; is it running?" >&2
                  exit 1
                fi
                cat "${'$'}dir/out" &
                reader=${'$'}!
                cat >"${'$'}dir/in"
                wait "${'$'}reader"
                reader=
                """.trimIndent() + "\n"
            },
        )

    /** Agents we've added the server to, kept in the guest so a new Linux starts over; one we did is never added again. */
    private val registered get() = File(proot.launcherConfig, "mcp-registered")

    /**
     * Adds the server to the configs of [installed] agents that take MCP servers, once per agent: if the user takes it
     * out again, it stays out. Their own settings in those files stay as they are. IO thread; agents aren't running.
     */
    fun register(installed: List<Agent>) {
        val done = runCatching { registered.readLines().map(String::trim).toMutableSet() }.getOrDefault(mutableSetOf())
        val before = done.size
        for (agent in installed) {
            if (agent.bin in done) continue
            val target = REGISTRATIONS.firstOrNull { it.bin == agent.bin } ?: continue
            val file = target.file(proot.home)
            val text = runCatching { file.takeIf { it.isFile }?.readText() }.getOrElse { continue }
            when (val result = target.edit(text)) {
                is McpConfigEdit.Result.Write -> {
                    val ok =
                        runCatching {
                            val mode = runCatching { Os.stat(file.path).st_mode and "777".toInt(8) }.getOrNull()
                            file.parentFile?.mkdirs()
                            writeAtomically(file, result.text.toByteArray())
                            if (mode != null) Os.chmod(file.path, mode)
                        }.onFailure { Log.w(TAG, "can't add the server to $file", it) }.isSuccess
                    if (ok) done += agent.bin.also { Log.i(TAG, "added to ${agent.name}'s $file") }
                }
                McpConfigEdit.Result.Present -> done += agent.bin
                // Tried again next start: the user may fix the file.
                is McpConfigEdit.Result.Unreadable -> Log.w(TAG, "${agent.name}'s $file: ${result.why}; the server isn't added")
            }
        }
        if (done.size != before) {
            runCatching {
                registered.parentFile?.mkdirs()
                writeAtomically(registered, done.sorted().joinToString("\n", postfix = "\n").toByteArray())
            }.onFailure { Log.w(TAG, "can't save $registered", it) }
        }
    }

    /** Where [bin] keeps its MCP servers, relative to the guest home, and how to add ours. */
    private class Registration(
        val bin: String,
        val file: (home: File) -> File,
        val edit: (String?) -> McpConfigEdit.Result,
    )

    companion object {
        const val COMMAND = "omalauncher-mcp"

        /** What the shim writes on xdg-open's FIFO, before its folder's guest path. */
        const val PREFIX = "$COMMAND:"
        private const val DIR_PREFIX = "mcp."

        private val NAME = McpServer.NAME

        private fun json(
            bin: String,
            path: String,
            section: String,
            entry: String,
        ) = Registration(bin, { File(it, path) }) { McpConfigEdit.json(it, section, NAME, entry) }

        /**
         * Each agent's user-level config, as documented by it. Pi has no MCP; the others (Grok, Antigravity, Oh My Pi,
         * Ori, Muse, Kimi) have no user config we know of to add to, and can start `omalauncher-mcp` themselves.
         */
        private val REGISTRATIONS =
            listOf(
                // What `claude mcp add --scope user` writes.
                json("claude", ".claude.json", "mcpServers", """{"type": "stdio", "command": "$COMMAND", "args": []}"""),
                Registration("codex", { File(it, ".codex/config.toml") }) { McpConfigEdit.toml(it, NAME, "command = \"$COMMAND\"") },
                Registration(
                    "opencode",
                    // opencode.jsonc instead, when that's the one there is.
                    { home -> File(home, ".config/opencode/opencode.json").let { f -> if (!f.exists() && File(home, ".config/opencode/opencode.jsonc").exists()) File(home, ".config/opencode/opencode.jsonc") else f } },
                ) { McpConfigEdit.json(it, "mcp", NAME, """{"type": "local", "command": ["$COMMAND"], "enabled": true}""") },
                json("copilot", ".copilot/mcp-config.json", "mcpServers", """{"type": "local", "command": "$COMMAND", "args": [], "tools": ["*"]}"""),
                json("crush", ".config/crush/crush.json", "mcp", """{"type": "stdio", "command": "$COMMAND"}"""),
                json("cursor-agent", ".cursor/mcp.json", "mcpServers", """{"command": "$COMMAND", "args": []}"""),
            )

        private val INSTRUCTIONS =
            "Tools for OmaLauncher++, the Android launcher this Linux runs in: home's pages and tiles, owl widgets and apps, " +
                "the Omarchy theme, the launcher's style, and the phone's apps. Each write is checked first and refused with " +
                "the reason if the launcher couldn't take it. They work on the files in ~/.config/omalauncher " +
                "(~/${WidgetEngine.GUEST_DIR}, ~/${HomeStore.GUEST_DIR}/${HomeStore.FILE}, style/style.toml), which can also " +
                "be edited directly; ~/${WidgetEngine.GUEST_DIR}/README.md documents the owl language."
    }
}
