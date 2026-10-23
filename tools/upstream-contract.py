#!/usr/bin/env python3
"""Regenerate host-shim's upstream contract snapshot.

Extensions and core/ are both compiled with `compileOnly(<pinned extensions-lib>)`, so every
`eu.kanade.tachiyomi.*` member they can reach is declared in that jar. The snapshot this script
writes is therefore the complete link-time contract the shim has to satisfy, and
`UpstreamContractTest` verifies the shim against it without needing the artifact at test time.

The artifact comes from the Gradle cache, or from JitPack when the cache has never seen it, so the
check runs on a fresh checkout without building anything. The jar is read as class files, not as
`javap` text. `javap` output is a convenience format that
changes between JDK releases, and a generator that re-parses it can lose members silently - a
snapshot regenerated on a different JDK would shrink, and `--check` would call the smaller file
correct because it regenerates the same way. Reading the constant pool and the field/method tables
gives the same answer on every JDK, so a difference here is always a real difference.

Run it after bumping the pin in `extensions-source/gradle/libs.versions.toml`.

Usage: tools/upstream-contract.py [--check]
"""
import glob
import os
import re
import struct
import sys
import tempfile
import urllib.request
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOML = os.path.join(REPO, "extensions-source", "gradle", "libs.versions.toml")
SNAPSHOT = os.path.join(REPO, "host-shim", "src", "test", "resources", "upstream-contract.txt")
GROUP = "com.github.keiyoushi"
ARTIFACT = "extensions-lib"
PACKAGE_PREFIX = "eu/kanade/tachiyomi/"

ACC_PUBLIC = 0x0001
ACC_PROTECTED = 0x0004

# Compiler plumbing that is public but not part of the API. Kotlin emits `access$...` helpers for
# private members and `$delegate` fields for `by lazy` properties; both are implementation detail a
# reimplementation is not expected to reproduce.
SYNTHETIC = re.compile(r"^(?:access\$|.*\$delegate$|.*_delegate\$lambda\$)")

UTF8 = 1
INTEGER, FLOAT, LONG, DOUBLE = 3, 4, 5, 6
CLASS, STRING = 7, 8
FIELDREF, METHODREF, INTERFACE_METHODREF = 9, 10, 11
NAME_AND_TYPE = 12
METHOD_HANDLE, METHOD_TYPE = 15, 16
DYNAMIC, INVOKE_DYNAMIC = 17, 18
MODULE, PACKAGE = 19, 20

TWO_SLOT = (LONG, DOUBLE)
TWO_BYTES = (STRING, METHOD_TYPE, MODULE, PACKAGE)
FOUR_BYTES = (FIELDREF, METHODREF, INTERFACE_METHODREF, NAME_AND_TYPE, DYNAMIC, INVOKE_DYNAMIC)


class Reader:
    def __init__(self, data):
        self.data = data
        self.pos = 0

    def u1(self):
        self.pos += 1
        return self.data[self.pos - 1]

    def u2(self):
        value = struct.unpack_from(">H", self.data, self.pos)[0]
        self.pos += 2
        return value

    def u4(self):
        value = struct.unpack_from(">I", self.data, self.pos)[0]
        self.pos += 4
        return value

    def skip(self, count):
        self.pos += count


def skip_attributes(reader):
    for _ in range(reader.u2()):
        reader.u2()
        reader.skip(reader.u4())


def read_class(path):
    """Returns (name, supertypes, [(kind, name, descriptor, access_flags)])."""
    with open(path, "rb") as handle:
        reader = Reader(handle.read())

    if reader.u4() != 0xCAFEBABE:
        raise ValueError(f"{path} is not a class file")
    reader.u2()
    reader.u2()

    # Constant pool.
    count = reader.u2()
    utf8, classes = {}, {}
    index = 1
    while index < count:
        tag = reader.u1()
        if tag == UTF8:
            length = reader.u2()
            utf8[index] = reader.data[reader.pos:reader.pos + length].decode("utf-8", "replace")
            reader.skip(length)
        elif tag in (INTEGER, FLOAT):
            reader.skip(4)
        elif tag in TWO_SLOT:
            reader.skip(8)
            index += 1
        elif tag == CLASS:
            classes[index] = reader.u2()
        elif tag in TWO_BYTES:
            reader.skip(2)
        elif tag in FOUR_BYTES:
            reader.skip(4)
        elif tag == METHOD_HANDLE:
            reader.skip(3)
        else:
            raise ValueError(f"unknown constant pool tag {tag} in {path}")
        index += 1

    def class_name(pool_index):
        return utf8.get(classes.get(pool_index, -1), "")

    reader.u2()  # class access flags
    name = class_name(reader.u2())
    super_index = reader.u2()
    super_name = class_name(super_index) if super_index else None
    interfaces = [class_name(reader.u2()) for _ in range(reader.u2())]

    members = []
    for kind in ("field", "method"):
        for _ in range(reader.u2()):
            access = reader.u2()
            member_name = utf8[reader.u2()]
            descriptor = utf8[reader.u2()]
            members.append((kind, member_name, descriptor, access))
            skip_attributes(reader)

    skip_attributes(reader)  # class attributes

    supertypes = [t for t in [super_name, *interfaces] if t and t != "java/lang/Object"]
    return name, supertypes, members


def pinned_version():
    text = open(TOML).read()
    match = re.search(
        rf'^[\w-]+\s*=\s*\{{\s*module\s*=\s*"{re.escape(GROUP)}:{ARTIFACT}",\s*version\s*=\s*"([^"]+)"',
        text,
        re.M,
    )
    if not match:
        sys.exit(f"no {GROUP}:{ARTIFACT} entry with an inline version in {TOML}")
    return match.group(1)


def aar_path(version, work):
    """The pinned AAR: from the Gradle cache when it is there, otherwise from the repository the
    extension build fetches it from. Both serve the same bytes."""
    cache = os.environ.get("GRADLE_USER_HOME", os.path.expanduser("~/.gradle"))
    pattern = os.path.join(
        cache, "caches", "modules-2", "files-2.1", GROUP, ARTIFACT, version, "*", f"{ARTIFACT}-{version}.aar"
    )
    matches = sorted(glob.glob(pattern))
    if matches:
        return matches[-1]

    # A fresh clone has never built an extension, so the artifact is not in the cache yet. Resolving
    # it here keeps the check runnable in CI without compiling anything.
    url = f"https://www.jitpack.io/{GROUP.replace('.', '/')}/{ARTIFACT}/{version}/{ARTIFACT}-{version}.aar"
    target = os.path.join(work, f"{ARTIFACT}-{version}.aar")
    try:
        urllib.request.urlretrieve(url, target)
    except OSError as error:
        sys.exit(
            f"could not fetch {ARTIFACT}:{version} from the Gradle cache or {url}\n"
            f"  {error}\n"
            "Offline? Build any extension once so the artifact is cached:\n"
            "  cd extensions-source && ./gradlew :src:en:jnovel:compileDebugKotlin"
        )
    return target


def class_files(root):
    for base, _, files in os.walk(root):
        for name in sorted(files):
            if name.endswith(".class"):
                yield os.path.join(base, name)


def generate():
    version = pinned_version()

    with tempfile.TemporaryDirectory() as work:
        aar = aar_path(version, work)
        with zipfile.ZipFile(aar) as archive:
            archive.extract("classes.jar", work)
        class_dir = os.path.join(work, "classes")
        with zipfile.ZipFile(os.path.join(work, "classes.jar")) as archive:
            archive.extractall(class_dir)

        parsed = []
        for path in class_files(class_dir):
            name, supertypes, members = read_class(path)
            if name.startswith(PACKAGE_PREFIX):
                parsed.append((name, supertypes, members))
        parsed.sort(key=lambda entry: entry[0])

        lines = [
            f"# The link-time contract host-shim must satisfy for extensions-lib:{version}.",
            "# Generated by tools/upstream-contract.py from the pinned artifact. Do not edit by hand.",
            f"pin={version}",
        ]
        for name, supertypes, members in parsed:
            dotted = name.replace("/", ".")
            lines.append(f"class {dotted}")
            for supertype in supertypes:
                lines.append(f"extends {dotted} {supertype.replace('/', '.')}")

            # Every public member counts, bridges included: a bridge is still a link target, and the
            # Kotlin shim emits the same ones. Only compiler plumbing is skipped.
            visible = [
                (kind, member_name, descriptor)
                for kind, member_name, descriptor, access in members
                if access & (ACC_PUBLIC | ACC_PROTECTED) and not SYNTHETIC.match(member_name)
            ]
            for kind, member_name, descriptor in sorted(set(visible)):
                lines.append(f"{kind} {dotted} {member_name} {descriptor}")

    if len(parsed) < 2 or not any(members for _, _, members in parsed):
        sys.exit(f"generated an empty contract from {aar}; refusing to write it")

    return version, "\n".join(lines) + "\n"


def main():
    version, body = generate()

    if "--check" in sys.argv[1:]:
        current = open(SNAPSHOT).read() if os.path.exists(SNAPSHOT) else ""
        if current != body:
            sys.exit(f"{SNAPSHOT} is out of date for extensions-lib:{version}; run tools/upstream-contract.py")
        print(f"{SNAPSHOT} matches extensions-lib:{version}")
        return

    os.makedirs(os.path.dirname(SNAPSHOT), exist_ok=True)
    with open(SNAPSHOT, "w") as handle:
        handle.write(body)
    members = sum(1 for line in body.splitlines() if line.startswith(("method ", "field ")))
    print(f"wrote {SNAPSHOT} for extensions-lib:{version} ({members} members)")


if __name__ == "__main__":
    main()
