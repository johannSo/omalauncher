# Scheduled agent jobs

OmaLauncher++ runs coding agents on a schedule, with nobody there: a prompt, the agent that answers it, when, and in
which folder. Each job is a `[[job]]` table in `jobs.toml` in this folder (`~/.config/omalauncher/jobs/jobs.toml`).
Edit it, or use the jobs screen on the phone (settings › agents › [ jobs ]); the launcher picks up a saved file within
a second. What it couldn't read goes into `errors.txt` here, with the reason; a job with a mistake is left out until
it's fixed, the others still run.

```toml
[[job]]
id = "morning"                      # letters, digits, - and _; names its folder of results
name = "Morning summary"            # shown on the phone; the id if left out
agent = "claude"                    # the agent's command; the launcher's default agent if left out
schedule = "weekdays at 07:00"
dir = "~/projects/blog"             # where it runs; ~ by default
prompt = "Summarize yesterday's commits and open issues in five lines.\nBe brief."
wifi = true                         # only on Wi-Fi (or Ethernet)
charging = false                    # only while on a charger
notify = "always"                   # always, failure or never
permissions = "safe"                # safe or full; read Permissions below
timeout = "30m"                     # stopped after this long: "90s", "30m", "2h"
enabled = true
```

Every key but `id`, `schedule` and the prompt can be left out. Other keys:

- `prompt_file = "prompts/morning.md"`: the prompt, from a file, relative to this folder or from `~/` or `/`;
  read as the job starts. With `prompt` too, it comes first, then the file.
- `command = "grok -p \"$JOB_PROMPT\""`: a shell command to run instead of an agent. Use it for agents the launcher
  doesn't know a non-interactive mode of (Grok, Crush, Antigravity, Ori, Muse), or for a script.
- `tz = "Europe/Berlin"`: the time zone the schedule is in; the phone's if left out.

TOML strings here are one line: write line breaks as `\n`, or put a long prompt in a `prompt_file`.

## Schedules

| schedule | runs |
|---|---|
| `daily at 07:00`, `every day at 7am` | every day at seven |
| `daily at 07:00, 19:00` | twice a day |
| `weekdays at 9`, `weekends at 10:30` | Monday to Friday; Saturday and Sunday |
| `mon,wed,fri at 18:30`, `sunday at 20:00` | on those days |
| `hourly`, `hourly at :15` | every hour, on the hour or at a quarter past |
| `every 15m`, `every 2h` | from the top of the hour, or from midnight; the step has to divide an hour or a day |
| `0 7 * * 1-5` | cron: minute, hour, day of month, month, day of week (0 or 7 is Sunday), with `*`, lists, ranges, `/steps` and names (`mon`, `jan`); a day of month and a day of week both set means either |
| `@hourly`, `@daily`, `@weekly`, `@monthly`, `@yearly` | cron's shorthands |
| `manual` | only when started with [ run now ] |

Times are the phone's local time (or `tz`'s). When the clocks go forward, a time that's skipped (02:30) runs as they
jump (03:30). When they go back, a time that comes twice runs the first time; a job that runs every hour runs in both.

A job runs on time while the phone sleeps if the launcher may set exact alarms (Android 13 and later ask: the jobs
screen says so and opens the setting). A run that couldn't happen on time, because the phone was off, Linux was shut
down, or there was no Wi-Fi or charger for a job that needs one, happens once as soon as it can, and only once however
many it missed. Linux starts for a job if the launcher wasn't running, but a Linux shut down in settings stays down:
its jobs wait. One job runs at a time; the others wait for their turn.

## Permissions

The agent runs headless, as root in this system, in `dir`, with the prompt and no terminal: nothing can answer a
question it asks. `permissions` decides what happens to what it would ask about:

| agent | `safe` (the default) | `full` |
|---|---|---|
| Claude Code | `claude -p --permission-mode dontAsk`: what your settings don't allow is refused | `claude -p --dangerously-skip-permissions` (with `IS_SANDBOX=1`, which Claude needs as root) |
| Codex | `codex exec --sandbox read-only`: its sandbox can't start in this system, so every command it tries fails and it answers from what it knows | `codex exec --dangerously-bypass-approvals-and-sandbox` |
| GitHub Copilot | `copilot -p`: tools it would ask about are refused | `copilot -p --allow-all-tools` |
| Cursor CLI | `cursor-agent -p`: proposes commands, doesn't run them | `cursor-agent -p --force` |
| Kimi | `kimi --prompt` | `kimi --yolo --prompt` |
| OpenCode | `opencode run`, with the permissions in its own config | the same |
| Pi, Oh My Pi | `pi -p`, `omp -p`: they ask about nothing, so they can do anything | the same |

Codex is also given `--skip-git-repo-check`, so it runs outside a repository. A `command` runs with whatever
permissions its own flags give.

With `full`, or with an agent that never asks, the job can run any command and change or delete any file in this
system, your home and the projects in it included, and use the network, every time it runs, with nobody watching.
Give it a prompt and a `dir` you'd trust it with unattended, and prefer `safe` for jobs that only read and report.

## Results

Each job has a folder here named after its id:

- `output.txt`: what the last run printed (its last 256 KB)
- `status.json`: the job and its last run, as below
- `history.json`: the last 20 runs, newest first: `started`, `finished`, `duration`, `result`, `exit`, `summary`, `manual`
- `runs/<started>.txt`: the output of the last 5 runs

`status.json` in this folder lists every job, in the order of `jobs.toml`, for widgets:

```json
{ "jobs": [ {
  "id": "morning", "name": "Morning summary", "agent": "claude", "schedule": "weekdays at 07:00", "enabled": true,
  "state": "idle", "reason": null, "next": 1793430000,
  "result": "ok", "exit": 0, "started": 1793343600, "finished": 1793343671, "duration": 71,
  "summary": "the last three lines of the output", "output": "the last 4000 characters of the output"
} ] }
```

`state` is `idle`, `queued` (waiting for its turn), `running` or `waiting` (`reason` says for what: Wi-Fi, a charger,
Linux). `result` is how the last run ended: `ok`, `failed`, `timeout` or `stopped`, or null before the first.
Times are Unix seconds. A widget reads it with `source jobs = file("~/.config/omalauncher/jobs/status.json") json`;
`examples/62-jobs.owl` in the widgets folder shows each job's latest result.

While it runs, a job has `JOB_ID`, `JOB_NAME`, `JOB_PROMPT` and `JOB_RESULTS` (its folder here) in its environment,
so a prompt can ask the agent to write a file there for a widget to read.
