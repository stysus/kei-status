#!/usr/bin/env python3
"""Verify android-shim's hand copies of core's Chromium boundary interfaces.

core's WebViewGlueBridge reaches the WebView provider through the
org.chromium.support_lib_boundary interfaces it compiles itself, but the harness's
URLClassLoader asks its parent - which owns android-shim - first, so the shim's
re-declarations are the classes that actually load. They are copies by hand rather than
imports: core marks the interfaces internal, and the extensions-source tree is a runtime
checkout, not a build dependency of this repository. Nothing else keeps the two in sync,
and a member core added or renamed would only surface as NoSuchMethodError mid-sweep.

This compares the JVM member names and parameter counts of every interface on both sides,
joining the lines a declaration wraps onto and skipping commas nested inside `<>` (a
generic's type arguments) or `()` (a functional type). An overload differs in parameter
count, so it compares as its own member; two overloads of the same count but different
types are still invisible to it and need a hand check against core's call sites. It runs
in the sweep's prepare job, which has the pinned checkout, and can be run by hand after
pulling extensions-source.

Usage: tools/core-boundary-contract.py [--self-test]
"""
import itertools
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
# with one still parses - a member neither side sees would pass the comparison silently -
# and so is a generic type-parameter clause (`fun <T : Comparable<T>> h`), which sits
# between `fun` and the name: `<` is not a `\w`, so without it a generic member would drop
# out of the parse on both sides and drift past the guard unnoticed.
KOTLIN_MEMBER = re.compile(r"^    (?:[a-z]+ +)*(fun|val|var)(?: *<.*>)? (\w+)")


def kotlin_members(text):
    """The JVM members a Kotlin interface declares, as (name, parameter count) pairs:
    `fun x(a, b)` as ("x", 2), a property as its getter (0 parameters) and, for a `var`,
    its setter (1). Overloads differ in count, so they compare as distinct members."""
    names = set()
    lines = text.splitlines()
    for i, line in enumerate(lines):
        match = KOTLIN_MEMBER.match(line)
        if not match:
            continue
        keyword, name = match.groups()
        if keyword == "fun":
            names.add((name, param_arity(line, lines[i + 1:])))
        else:
            names.add((getter(name), 0))
            if keyword == "var":
                names.add((setter(name), 1))
    return names


def java_members(text):
    """The methods a Java interface declares, as (name, parameter count) pairs, joining
    the lines a declaration wraps onto. Constants have no `(` and never match."""
    names = set()
    lines = text.splitlines()
    for i, line in enumerate(lines):
        if line.startswith("    ") and not line.startswith("     ") and "(" in line:
            match = re.search(r"(\w+)\(", line)
            if match:
                names.add((match.group(1), param_arity(line, lines[i + 1:])))
    return names


def param_arity(member_line, following_lines):
    """The parameter count of the declaration whose parameter list opens on
    [member_line], joining the wrapped continuation lines in [following_lines] until its
    parentheses close. A comma only separates parameters outside any nested `<>` (a
    generic's type arguments) or `()` (a functional type) - otherwise `Map<String, Any>`
    would count as two.

    Continuation lines sit at a deeper indent than the member line, so the per-line
    member regexes never mistake them for members of their own.
    """
    commas = 0
    has_param = False
    paren = 0
    angle = 0
    opened = False
    for line in itertools.chain([member_line], following_lines):
        for ch in line:
            if not opened:
                if ch == "(":
                    opened = True
                    paren = 1
                continue
            if paren == 1 and angle == 0 and ch == ",":
                commas += 1
            elif ch == "(":
                paren += 1
            elif ch == ")":
                paren -= 1
                if paren == 0:
                    return commas + 1 if has_param else 0
            elif ch == "<":
                angle += 1
            elif ch == ">":
                # `->` in a functional type has no matching `<`; clamping keeps it from
                # unbalancing the count for the commas that follow.
                angle = max(0, angle - 1)
            elif not ch.isspace():
                has_param = True
    raise ValueError(f"unbalanced parameter list in {member_line!r}")


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
    """{interface name: (member name, parameter count) pairs} per source file."""
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
            failures.append(
                f"{name}.{member[0]}: core declares it with {member[1]} parameter(s), "
                f"android-shim's copy does not"
            )
        for member in sorted(shim[name] - core[name]):
            failures.append(
                f"{name}.{member[0]}: android-shim's copy declares it with {member[1]} "
                f"parameter(s), core does not"
            )

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
        "    fun addWebMessageListener(\n"
        "            jsObjectName: String, allowedOriginRules: Array<String>, \n"
        "            listener: InvocationHandler, worldName: String)\n"
        "    fun setMeta(meta: MutableMap<String, Any>, flag: Boolean)\n"
        "    fun <T : Comparable<T>> h(x: T, y: T)\n"
        "}\n"
    )
    assert kotlin == {
        ("postMessage", 1),
        ("ping", 0),
        ("toString", 0),
        ("getUserAgentMetadataMap", 0),
        ("isSomething", 0),
        ("setSomething", 1),
        ("withDefault", 0),
        # The wrapped declaration joins its continuation lines; the comma inside
        # Array<String>/Map<...> generic arguments is not a parameter separator.
        ("addWebMessageListener", 4),
        ("setMeta", 2),
        # A generic type-parameter clause sits between `fun` and the name.
        ("h", 2),
    }, kotlin

    java = java_members(
        "public interface Example {\n"
        "    void postMessage(String message);\n"
        "    Map<String, Object> getUserAgentMetadataMap();\n"
        "    boolean isSomething();\n"
        "    void setSomething(\n"
        "            boolean value);\n"
        "    void addWebMessageListener(\n"
        "            String jsObjectName, String[] allowedOriginRules, InvocationHandler listener,\n"
        "            String worldName);\n"
        "    void setMeta(Map<String, Object> meta, boolean flag);\n"
        "    int CONSTANT = 1;\n"
        "}\n"
    )
    assert java == {
        ("postMessage", 1),
        ("getUserAgentMetadataMap", 0),
        ("isSomething", 0),
        ("setSomething", 1),
        ("addWebMessageListener", 4),
        ("setMeta", 2),
    }, java

    assert getter("isReady") == "isReady"
    assert getter("ready") == "getReady"
    assert setter("ready") == "setReady"
    print("ok: parser self-test")


if __name__ == "__main__":
    if "--self-test" in sys.argv[1:]:
        self_test()
    else:
        check()
