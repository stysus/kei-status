#!/usr/bin/env python3
"""Union the per-shard costs files into the store's single costs file.

Each costs-N file is not a partial view: a shard's CLI merges its measurements into its own
checkout of the store, so the file carries the whole baseline it started from plus that
shard's fresh timings. A plain update() over the files in name order therefore lets the last
file's stale copies of the other shards' modules win - one night's union kept a single
shard's measurements, and the shard split kept planning on weeks-old numbers while real times
had drifted (an image-probing change multiplied one module from 3 to 27 minutes, and its
shard ran 42 minutes against a 12-minute plan).

The entry that differs from the baseline is the one a shard actually measured that night, so
only those are taken; entries equal to the baseline are stale copies and are skipped. Shards
are disjoint, so at most one file carries a differing value for a module - except a module
that runs in two shards through `--lang` lines, where the later file wins, as it already did.

Usage: tools/union-costs.py <store-costs-file> <incoming-dir> [--self-test]
"""
import glob
import json
import os
import sys


def union(baseline, views):
    """The baseline with every view's fresh measurements applied.

    A view's entry wins only when it differs from the baseline: everything else in the view
    is a copy of the baseline the shard started from, not something it measured.
    """
    merged = dict(baseline)
    for view in views:
        for module, cost in view.items():
            if baseline.get(module) != cost:
                merged[module] = cost
    return merged


def read_json(path, what):
    try:
        with open(path, encoding="utf-8") as handle:
            return json.load(handle)
    except (ValueError, OSError) as error:
        print(f"::warning::ignoring unreadable {what} {path}: {error}", file=sys.stderr)
        return None


def main(store_costs, incoming):
    baseline = read_json(store_costs, "costs file") or {}
    views = []
    for path in sorted(glob.glob(os.path.join(incoming, "**", "costs-*"), recursive=True)):
        view = read_json(path, "shard costs view")
        if view is not None:
            views.append(view)
    if not views:
        return
    merged = union(baseline, views)
    if merged:
        with open(store_costs, "w", encoding="utf-8") as handle:
            json.dump(merged, handle, indent=2, sort_keys=True)
            handle.write("\n")


def self_test():
    # Two views, each the baseline plus its own measurement. The last file also carries a
    # stale copy of the first shard's fresh entry; the union must keep both measurements.
    baseline = {"heavy": 204, "other": 5}
    shard5 = {"heavy": 1600, "other": 5}
    shard9 = {"heavy": 204, "other": 6}
    assert union(baseline, [shard5, shard9]) == {"heavy": 1600, "other": 6}, union(baseline, [shard5, shard9])
    # Order must not matter, because glob order is lexicographic, not shard order.
    assert union(baseline, [shard9, shard5]) == {"heavy": 1600, "other": 6}
    # A brand-new module no baseline knows is fresh in every view that names it.
    assert union({}, [{"new": 1, "x": 2}]) == {"new": 1, "x": 2}
    print("ok: costs union self-test")


if __name__ == "__main__":
    args = sys.argv[1:]
    if "--self-test" in args:
        self_test()
    else:
        main(args[0], args[1])
