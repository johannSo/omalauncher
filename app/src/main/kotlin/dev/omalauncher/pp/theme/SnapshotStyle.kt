package dev.omalauncher.pp.theme

/** style.toml's `[snapshots]`: snapshots of the Linux system, see [dev.omalauncher.pp.linux.Snapshots]. */
object SnapshotStyle {
    val BEFORE_UPDATE =
        Setting.Flag(
            "snapshots.before-update",
            true,
            "A snapshot of the Linux system before every system update from settings, when storage has room for it; settings › linux › snapshots rolls back to one.",
        )
    val KEEP = Setting.Count("snapshots.keep", 2, 1..10, "How many snapshots to keep: taking one more deletes the oldest.")
    val HOME =
        Setting.Flag(
            "snapshots.home",
            true,
            "Snapshots also hold /root and /home, so a restore can roll them back too when asked (by default it keeps them as they are); false leaves them out, for smaller snapshots.",
        )

    val ALL: List<Setting<*>> = listOf(BEFORE_UPDATE, KEEP, HOME)
}
