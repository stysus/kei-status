#!/usr/bin/env python3
"""Verify android-shim's hand copies of core's Chromium boundary interfaces.

core's WebViewGlueBridge reaches the WebView provider through the
org.chromium.support_lib_boundary interfaces it compiles itself, but the harness's
URLClassLoader asks its parent - which owns android-shim - first, so the shim's
re-declarations are the classes that actually load. They are copies by hand rather than
imports: core marks the interfaces internal, and the extensions-source tree is a runtime
checkout, not a build dependency of this repository. Nothing else keeps the two in sync,
and a member core added or renamed would only surface as NoSuchMethodError mid-sweep.

This compares the JVM member names of every interface on both sides. It runs in the sweep's
prepare job, which has the pinned checkout, and can be run by hand after pulling
extensions-source.

Usage: tools/core-boundary-contract.py [--self-test]
"""
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CORE_DIR = os.path.join(
    REPO, "extensions-source", "core", "src", "main", "kotlin", "org", "chromium", "support_lib_boundary"
)
SHIM_DIR = os.path.join(
    REPO, "android-shim", "src", "main", "java", "org", "chromium", "support_lib_boundary"
)

# Interface members sit at one indent level; the bodies of default methods sit deeper, so
# anchoring on the exact indent keeps these from picking up call lines inside them. The
# modifier words (`suspend`, `override`, `internal`, ...) are skipped so a member core adds
# with one still parses - a member neither side sees would pass the comparison silently.
KOTLIN_MEMBER = re.compile(r"^    (?:[a-z]+ +)*(fun|val|var) (\w+)")


def kotlin_members(text):
    """The JVM member names a Kotlin interface declares: `fun x` as `x`, a property as its
    getter (an `isX` name stays unchanged), a `var` as getter and setter."""
    names = set()
    for line in text.splitlines():
        match = KOTLIN_MEMBER.match(line)
        if not match:
            continue
        keyword, name = match.groups()
        if keyword == "fun":
            names.add(name)
        else:
            names.add(getter(name))
            if keyword == "var":
                names.add(setter(name))
    return names


def java_members(text):
    """The method names a Java interface declares: the first identifier before a `(` on a
    member line. Constants have no `(` and never match."""
    names = set()
    for line in text.splitlines():
        if line.startswith("    ") and not line.startswith("     ") and "(" in line:
            match = re.search(r"(\w+)\(", line)
            if match:
                names.add(match.group(1))
    return names


def getter(name):
    if len(name) > 2 and name.startswith("is") and name[2].isupper():
        return name
    return "get" + name[0].upper() + name[1:]


def setter(name):
    # Kotlin drops the "is" prefix: a `var isReady` gets setReady(...), not setIsReady(...)
    if len(name) > 2 and name.startswith("is") and name[2].isupper():
        return "set" + name[2:]
    return "set" + name[0].upper() + name[1:]


def interfaces_of(directory, parse):
    """{interface name: member names} for every source file in a boundary directory."""
    result = {}
    for file in sorted(os.listdir(directory)):
        if not file.endswith((".kt", ".java")):
            continue
        with open(os.path.join(directory, file), encoding="utf-8") as handle:
            result[file.rsplit(".", 1)[0]] = parse(handle.read())
    return result


def check():
    if not os.path.isdir(CORE_DIR):
        sys.exit(
            "no extensions-source checkout at extensions-source/core/...; this guard needs "
            "the tree the sweep is about to compile"
        )

    core = interfaces_of(CORE_DIR, kotlin_members)
    shim = interfaces_of(SHIM_DIR, java_members)

    # A parser that silently reads nothing would pass every comparison vacuously.
    for name, members in list(core.items()) + list(shim.items()):
        if not members:
            sys.exit(f"parsed no members from interface {name}; parser broken or file empty")

    failures = []
    for name in sorted(set(core) | set(shim)):
        if name not in core:
            failures.append(f"{name}: android-shim declares it but core does not")
            continue
        if name not in shim:
            failures.append(f"{name}: core declares it but android-shim does not")
            continue
        for member in sorted(core[name] - shim[name]):
            failures.append(f"{name}.{member}: core declares it, android-shim's copy does not")
        for member in sorted(shim[name] - core[name]):
            failures.append(f"{name}.{member}: android-shim's copy declares it, core does not")

    if failures:
        sys.exit(
            "android-shim's org.chromium.support_lib_boundary copies drifted from "
            "extensions-source/core (the shim's copies are the ones that load at run "
            "time, so core would fail to link against them):\n  " + "\n  ".join(failures)
        )
    print(f"ok: {len(core)} boundary interfaces match extensions-source/core")


def self_test():
    kotlin = kotlin_members(
        "internal interface Example {\n"
        "    fun postMessage(message: String)\n"
        "    suspend fun ping()\n"
        "    override fun toString(): String = \"x\"\n"
        "    val userAgentMetadataMap: MutableMap<String, Any>\n"
        "    var isSomething: Boolean\n"
        "        get() = false\n"
        "    fun withDefault() {\n"
        "        postMessage(\"body lines are deeper and ignored\")\n"
        "    }\n"
        "}\n"
    )
    assert kotlin == {
        "postMessage",
        "ping",
        "toString",
        "getUserAgentMetadataMap",
        "isSomething",
        "setSomething",
        "withDefault",
    }, kotlin

    java = java_members(
        "public interface Example {\n"
        "    void postMessage(String message);\n"
        "    Map<String, Object> getUserAgentMetadataMap();\n"
        "    boolean isSomething();\n"
        "    void setSomething(\n"
        "            boolean value);\n"
        "    int CONSTANT = 1;\n"
        "}\n"
    )
    assert java == {"postMessage", "getUserAgentMetadataMap", "isSomething", "setSomething"}, java

    assert getter("isReady") == "isReady"
    assert getter("ready") == "getReady"
    assert setter("ready") == "setReady"
    print("ok: parser self-test")


if __name__ == "__main__":
    if "--self-test" in sys.argv[1:]:
        self_test()
    else:
        check()
