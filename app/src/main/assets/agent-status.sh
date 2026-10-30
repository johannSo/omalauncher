#!/bin/bash
# omalauncher: tells the launcher what a coding agent in a terminal is doing,
# for its notification and widgets (see AgentStatus.kt). The agents' hooks run
# it: Claude Code's and Codex's pass their event as JSON on stdin, Codex's
# notify as its last argument. A permission request waits for Allow or Deny
# from the phone and prints it as the hook's answer; without one (the
# launcher's timeout, or reports turned off) the agent asks in the terminal.
#
#   omalauncher-agent-status <agent>                      a hook, the event on stdin
#   omalauncher-agent-status <agent> <json>               Codex's notify
#   omalauncher-agent-status answer <request> allow|deny  answers a waiting request
set -u
inbox=${OMALAUNCHER_AGENT_INBOX:-/run/user/0/agent-status}
# The launcher makes it while it listens and reports are on; a hook mustn't slow the agent down otherwise.
[ -d "$inbox" ] || exit 0
[ $# -ge 1 ] || exit 2
id="${EPOCHREALTIME/[.,]/}-$$"

if [ "$1" = answer ]; then
  if [ $# -ne 3 ] || { [ "$3" != allow ] && [ "$3" != deny ]; }; then
    echo "usage: omalauncher-agent-status answer <request> allow|deny" >&2
    exit 2
  fi
  printf 'answer\n%s\n%s\n' "$2" "$3" >"$inbox/$id.req"
  exit 0
fi

agent=$1
if [ $# -ge 2 ]; then
  payload=${!#}
else
  IFS= read -r -d '' payload || true
fi

# This process and its ancestors: the launcher finds the terminal among them. Builtins only, no forks.
pids=$$
p=$PPID
for ((i = 0; i < 32 && p > 1; i++)); do
  pids+=" $p"
  { read -r stat <"/proc/$p/stat"; } 2>/dev/null || break
  # "pid (comm) state ppid …": comm may hold spaces and parentheses.
  stat=${stat##*) }
  read -r _ p _ <<<"$stat"
done

wait=-
[[ $payload =~ \"hook_event_name\"[[:space:]]*:[[:space:]]*\"PermissionRequest\" ]] && wait=wait
if [ $wait = wait ]; then
  # The answer comes on a FIFO of its own, held open read-write so neither side blocks; the launcher can tell
  # from it whether this hook still waits (an agent may give up on its hooks).
  fifo=$inbox/$id.fifo
  mkfifo -m 600 "$fifo" 2>/dev/null && exec 3<>"$fifo" || wait=-
fi

printf 'report\n%s\n%s\n%s\n%s' "$agent" "$pids" "$wait" "$payload" >"$inbox/$id.req"
[ $wait = wait ] || exit 0

trap 'rm -f "$fifo"' EXIT
answer=
read -r -t 3600 answer <&3
case $answer in
  allow)
    printf '{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"allow"}}}\n'
    ;;
  deny)
    printf '{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"deny","message":"The user denied this from their phone."}}}\n'
    ;;
esac
exit 0
