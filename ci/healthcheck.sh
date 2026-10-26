#!/usr/bin/env bash
#
# Reads what tonight actually found, where verify.sh only reads that the sweep ran.
#
# Two outputs: a rendered job summary (verdicts, transitions, throttled hosts), and a warning
# when the night moved too many sources to be ordinary.  A mass block or a broken extension-lib
# upgrade is a finding, not a pipeline failure — the page stays worth publishing — so this
# warns instead of failing.
#
# Usage: healthcheck.sh <store-dir> <incoming-dir>

set -euo pipefail

usage="usage: healthcheck.sh <store-dir> <incoming-dir>"
store="${1:?$usage}"
incoming="${2:?$usage}"

python3 - "$store" "$incoming" <<'PY'
import glob, json, os, sys

store, incoming = sys.argv[1:3]

# Tunable: a hand-checked threshold beats a statistical one nobody can explain later.
REGRESSION_BURST = 25    # sources that went FAIL or BLOCKED tonight
MASS_TRANSITIONS = 60    # transitions of any kind tonight

def reports(root):
    for path in sorted(glob.glob(os.path.join(root, "*.json"))):
        try:
            yield json.load(open(path))
        except Exception:
            continue

all_reports = list(reports(store))
verdicts = {}
for r in all_reports:
    for s in r.get("sources", []):
        verdicts[s["status"]] = verdicts.get(s["status"], 0) + 1

changed = list(reports(incoming))
regressions, fixes, new, gone, other = [], [], [], [], []
for r in changed:
    for c in r.get("changes", []):
        to, frm = c.get("to"), c.get("from")
        entry = (
            f'{c["source"]} [{c["lang"]}] {c.get("scenario") or "source"}: '
            f'{frm or "new"} -> {to or "gone"}'
        )
        if to is None:
            gone.append(entry)
        elif frm is None:
            new.append(entry)
        elif to in ("FAIL", "BLOCKED"):
            regressions.append(entry)
        elif to == "PASS" and frm in ("FAIL", "BLOCKED"):
            fixes.append(entry)
        else:
            other.append(entry)

hosts = sorted({h for r in changed for h in r.get("throttledHosts", [])})

dist = " \u00b7 ".join(
    f"{v} {k}" for k, v in sorted(verdicts.items(), key=lambda kv: -kv[1])
)
trans = len(regressions) + len(fixes) + len(new) + len(gone) + len(other)
lines = [
    "## Sweep summary",
    f"**{len(all_reports)}** modules, verdicts: {dist}",
    f"**{trans}** transition(s) tonight: {len(regressions)} regressed, "
    f"{len(fixes)} fixed, {len(new)} new, {len(gone)} gone, {len(other)} moved",
]
if hosts:
    lines.append(f"Throttled host(s): {', '.join(hosts)}")
for title, items in [("Regressions", regressions), ("Fixes", fixes)]:
    if items:
        lines.append(f"\n<details><summary>{title} ({len(items)})</summary>\n")
        lines += [f"- {i}" for i in sorted(items)[:50]]
        if len(items) > 50:
            lines.append(f"- … and {len(items) - 50} more")
        lines.append("\n</details>")

with open(os.environ.get("GITHUB_STEP_SUMMARY", os.devnull), "a") as f:
    f.write("\n".join(lines) + "\n")

# Also print the one-liner to the step log so it is visible without expanding the summary.
print(f"{len(all_reports)} modules, {trans} transition(s)")

if len(regressions) >= REGRESSION_BURST:
    print(
        f"::warning::{len(regressions)} source(s) went FAIL or BLOCKED tonight — "
        f"above the usual night; check for a site-side block or a harness change"
    )
if trans >= MASS_TRANSITIONS:
    print(
        f"::warning::{trans} transition(s) tonight — an unusual night; "
        f"see the job summary for the breakdown"
    )
PY
