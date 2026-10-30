package dev.omalauncher.pp.linux

import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Ends a proot started with [Proot.process] and every guest process under it. [Process.destroy] only sends
 * SIGTERM, which proot ignores, so a stream or a hung shell kept running after the launcher let go of it; and
 * a SIGKILLed proot leaves its tracees running on untraced (a `while :; do echo; done` spins at full speed), so
 * each process in the tree is SIGKILLed, children before the proot that would otherwise reap them.
 */
fun killTree(process: Process) {
    val root = pidOf(process)
    if (root != null && process.isAlive) {
        // A few rounds: a shell may fork a new child between reading /proc and the kill.
        repeat(3) {
            val tree = descendants(root)
            if (tree.isEmpty()) return@repeat
            for (pid in tree) runCatching { Os.kill(pid, OsConstants.SIGKILL) }
        }
        runCatching { Os.kill(root, OsConstants.SIGKILL) }
    }
    // Closes the pipes too.
    process.destroy()
}

/** Android's Process says its pid only in its text, "Process[pid=1234, hasExited=false]". */
private fun pidOf(process: Process): Int? = Regex("""pid=(\d+)""").find(process.toString())?.groupValues?.get(1)?.toIntOrNull()

/** Every process under [root], from /proc's parent links; our own are the only ones we can read. */
private fun descendants(root: Int): List<Int> {
    val children = HashMap<Int, MutableList<Int>>()
    for (dir in File("/proc").listFiles().orEmpty()) {
        val pid = dir.name.toIntOrNull() ?: continue
        // "pid (comm) state ppid …": the name may hold spaces and parentheses, so read after its last ')'.
        val stat = runCatching { File(dir, "stat").readText() }.getOrNull() ?: continue
        val ppid = stat.substringAfterLast(')').trim().split(' ').getOrNull(1)?.toIntOrNull() ?: continue
        children.getOrPut(ppid) { ArrayList() }.add(pid)
    }
    val out = ArrayList<Int>()
    val queue = ArrayDeque(listOf(root))
    while (queue.isNotEmpty()) {
        for (c in children[queue.removeFirst()].orEmpty()) {
            out.add(c)
            queue.add(c)
        }
    }
    return out
}

/** A guest process: its pid and its command line, as the guest's `ps` shows it. */
data class GuestProcess(
    val pid: Int,
    val argv: List<String>,
) {
    /** What to call it: the program, and for an interpreter the script it runs (`node vite`). */
    val label get() = processLabel(argv)
}

/**
 * Every guest process: our own processes running proot's [loader]. proot hands the guest's argv on, so the
 * command line is the guest's own; the kernel's comm would only say the loader's name.
 */
fun guestProcesses(loader: String): List<GuestProcess> =
    File("/proc").listFiles().orEmpty().mapNotNull { dir ->
        val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
        val exe = runCatching { Os.readlink(File(dir, "exe").path) }.getOrNull()
        if (exe != loader) return@mapNotNull null
        val argv = runCatching { File(dir, "cmdline").readBytes().decodeToString().split('\u0000').dropLastWhile { it.isEmpty() } }.getOrNull()
        GuestProcess(pid, argv.orEmpty())
    }.sortedBy { it.pid }

/** [pid]'s open sockets: fd number to socket inode, from its /proc/<pid>/fd links (`socket:[12345]`); null if unreadable. */
fun socketFds(pid: Int): Map<Int, Long>? =
    File("/proc/$pid/fd").list()?.mapNotNull { name ->
        val fd = name.toIntOrNull() ?: return@mapNotNull null
        val link = runCatching { Os.readlink("/proc/$pid/fd/$name") }.getOrNull() ?: return@mapNotNull null
        val inode = link.removePrefix("socket:[").removeSuffix("]").takeIf { link.startsWith("socket:[") }?.toLongOrNull() ?: return@mapNotNull null
        fd to inode
    }?.toMap()

/** Runtimes whose program name says less than the script they run. */
private val INTERPRETER = Regex("""(node|nodejs|bun|deno|python[0-9.]*|ruby|php|perl|java)""")

/** [argv]'s program, without its directory; for an interpreter, with the script after it. Empty argv: "?". */
fun processLabel(argv: List<String>): String {
    val program = argv.firstOrNull()?.substringAfterLast('/')?.takeIf { it.isNotEmpty() } ?: return "?"
    if (!INTERPRETER.matches(program)) return program
    // The first argument that isn't an option: `node --watch server.js`, `python3 -m http.server` names the module.
    val rest = argv.drop(1)
    val module = rest.indexOf("-m").takeIf { it >= 0 }?.let { rest.getOrNull(it + 1) }
    val script = module ?: rest.firstOrNull { !it.startsWith('-') }?.substringAfterLast('/')
    return if (script.isNullOrEmpty()) program else "$program $script"
}
