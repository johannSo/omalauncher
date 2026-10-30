# Panels

Part of the widget docs: README.md, one folder up, comes first, and paths here are from the widgets folder.

A panel is a file of its own that a swipe on home opens: swiping up opens the app drawer, above the prompt bar;
swiping down brings one from the top. The app drawer is a panel too, `drawer.owl`: edit it to change the drawer, or
read it to see how a whole drawer is put together. The launcher ships one more, `controls.owl`, for swiping down: the
phone's switches, the volume, what's playing, the next alarm and the notifications. Which panel each swipe opens
is picked in settings › gestures, or in style.toml as `panel:` and the panel's file name (see `defaults.toml`; a bare
file name, as older files have it, works too):

```toml
[gestures]
swipe-up = "panel:my-drawer.owl"     # panel:drawer.owl by default; "none" leaves the drawer to tapping the bar
swipe-down = "panel:controls.owl"    # "none" by default
double-tap = "lock"                  # on empty space
two-finger-up = "app:org.mozilla.firefox"
two-finger-down = "quick-settings"
pinch-in = "pages"
pinch-out = "agent:what's on my calendar today?"
```

Every gesture takes the same actions: `none`; `drawer`; `notification-shade`; `quick-settings`; `pages` (settings ›
pages); `lock` and `recents` (the screen off, and Android's recent apps: both need the launcher's accessibility
service, which settings › gestures explains and turns on); `panel:<file>.owl`; `app:<package>` or
`app:<package>/<activity>` for an Android app (`app:com.android.settings/.Settings`); `linux-app:<desktop id>` for a
Linux app (`linux-app:firefox`, from firefox.desktop); `linux:<command>`, run in Linux without a window; `terminal:<command>`,
in a terminal of its own; and `agent:<prompt>`, sent to the default coding agent as the bar sends one. A panel opened by
any gesture but swiping up comes down from the top, as swiping down brings one; a value that's none of these is
reported in errors.txt and leaves the gesture as it was. Double tap counts on empty space only; two-finger swipes and
pinches anywhere on home's pages, and once two fingers land together the gesture is home's, not the widgets' under them.

The drawer is the panel picked for swiping up, else `drawer.owl`, else `.stock-drawer.owl`: the drawer as this
launcher ships it, which it keeps that way. So a drawer that's removed or stops compiling never locks anyone out of
their apps (a panel picked for swiping down that's broken opens nothing). Home's banner says which, and settings list it
with "(has errors)". Compare `drawer.owl` with `.stock-drawer.owl` to see what changed in the stock drawer since. With
nothing on swipe up, tapping the bar still opens the drawer.

Behind a panel the launcher draws the theme's background, as opaque as the panel's `opacity` says: from 0, home shows
through, to 1, solid; without one 0.97, so home barely shows (owl 13). `drawer.owl` and `controls.owl` both have 0.97. Leave
a panel's outer node without a `background` of its own, or it covers home whatever `opacity` is; a background on the
boxes inside it is fine.

Opening the drawer brings up the keyboard, so search is ready. For a drawer that's more for scrolling, `keyboard: false`
(owl 15) leaves it down: tap the prompt bar to search, and a hardware keyboard types into it as before. It's `true` or
`false` in the file, not an expression; a panel from the top has no bar, so there it changes nothing.

```owl
panel "my drawer" {
  meta { owl: 11 }
  source d = android.drawer
  let shown = query == "" ? d.apps : rank(d.apps, query)
  // Enter in the prompt bar: open the best match when it names an app well, as the bar does; a sentence asks the agent.
  onSubmit: {
    if len(shown) > 0 && strongMatch(shown[0], value) {
      open(shown[0].key)
    } else if trim(value) != "" {
      ask(value)
    }
  }
  Grid {
    lazy: true
    weight: 1
    columns: 5
    for a in shown key a.key {
      Column { align: center; onTap: open(a.key); Image { src: a.icon; size: 52 }; Text { text: a.name; style: small } }
    }
  }
}
```

The launcher keeps what's around a panel: the backdrop, the panel following the finger as it opens, closing it by
pulling down past the top of its list (pushing up past the end, for one from the top), a tap on its empty space or
Back. The drawer sits above the prompt bar, which stays with its microphone and agent menu: `query` is the bar's text
as it's typed, and `onSubmit` runs on Enter with it as `value`; without `onSubmit`, Enter asks the agent. Only a
panel opened by swiping up has the bar: in one from the top, `query` stays "" and `onSubmit`, `enter` and `onKey` never run. `enter` is what the bar shows it does, like
`enter: opens ? "▸ open" : "⏎ ask"` ("" shows nothing). With a keyboard attached, the bar's Up, Down, Tab and
Shift+Tab go to `onKey` with `value` set to `up`, `down`, `tab` or `shift-tab`, so a panel can move its own
highlight (drawer.owl keeps a `pick`). Up and Down come only while what's typed is one line; in more, they move
between its lines. Without `onKey`, the keys stay with the bar and do what they do in any text field. `open(...)` and `appAction(...)` leave the
panel as opening an app from the drawer does, and `close()` slides it away. Besides `enter` and
`opacity`, a panel has no properties, title or icon; like an app's, its nodes are the screen's. Give a long list `lazy: true`.
A node's `onSwipe` (owl 14) takes swipes sideways, so the drawer turns its tabs with them: right to left from
personal to work to private, left to right back.

## Private space

Android 15 and newer can keep apps in a private space, a profile of its own behind its own lock. Android shows
it only to the default home app, so the drawer lists it once OmaLauncher++ is your home app, the way it lists a work
profile: on a private tab, after the personal and work tabs, with a lock on each app's icon. Screen readers say
"Private WhatsApp", and search finds it by "WhatsApp private" too.

While the space is locked, none of its apps are listed: not on the tab, in search, `android.drawer` or anywhere
else. Its tab then only offers to unlock it (`[unlock]`, which asks for its PIN or fingerprint). Unlocked, the tab
lists its apps and shortcuts, with `[install]` to install apps into it from the app store, `[settings]` for
Android's private space settings (Android 16 on), and `[lock]`. Locking it stops its apps, and they leave the list
at once.

The space can hide while it's locked: then there's no private tab at all, and only typing "private" offers to unlock
it. Turn that on in Android's private space settings (Android 16 on, "hide when locked"), or, before Android 16,
with the drawer's own "hide while locked" switch on the unlocked tab (`privateSpace("hide")`).

To keep it private, the private space's apps never go on home (no "add to home" in their menu, and `layout.toml`
can't place them), opening them isn't counted (they never show among recent apps or in `android.recent`), and
`android.apps` leaves them out. `d.private` is `{locked, hidden, settings}` while there's a private space: `hidden`
while it hides as it locks, `settings` while `privateSpace("settings")` can open Android's settings for it. A drawer
of your own should keep them in a container of their own too, with a way to lock it. Until it declares `owl: 13`,
`android.drawer` leaves the private space out for it (no private apps, `d.private` null), so a drawer written before
can't mix them in with the personal ones:

```owl
panel "private" {
  meta { owl: 13 }
  source d = android.drawer
  Column {
    visible: d.private != null
    Row {
      Text { text: d.private.locked ? "private space locked" : "private space"; weight: 1 }
      Text { text: d.private.locked ? "[unlock]" : "[lock]"; onTap: privateSpace("toggle") }
    }
    Grid {
      columns: 5
      for a in d.apps.filter(a => a.profile == "private") key a.key {
        Column { align: center; onTap: open(a.key); Image { src: a.icon; size: 52; label: a.label }; Text { text: a.name; style: small } }
      }
    }
  }
}
```
