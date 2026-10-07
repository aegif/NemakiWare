#!/usr/bin/env python3
"""Before a sweep: a fast screen for .java sabotages of run_negative_controls.py that do not compile.

A sabotage that no longer compiles stops the sweep at that control, hours in, and every control
after it goes unmeasured. The runner refuses a drifted anchor before it starts, but an anchor can
still match where the sabotage no longer compiles: QS3's anchor followed its code into an extracted
method where a name it uses is not in scope, and HM3's edit still compiled in its own file while
another file of the module used the constant it removes (2026-09-25; HM3 stopped the sixth sweep at
its 827th control). The runner's own --compile-check builds the whole module with Maven once per
control — exact, and about a minute each, as long as the sweep itself.

This screen applies the runner's sabotage_text to each control in memory and compiles the sabotaged
file together with the files of the same module that use its type, with javac, in Maven's two steps:
a main file with the main classpath (target/classes and compile-scope dependencies — no test
classes), then the test files that use it against that output; a test file with the test classpath.
The files that use the type are those that name it in their source, and those whose compiled
classes (the constant pools under target/classes and target/test-classes) refer to it or to a type
nested in it — a call through an inherited field, a `var` or a chained call names no type in the
source, but its class file refers to the method's owner. Sources go to a temporary directory and
classes to another; the tree's sources are not written. 1,611 sabotages took 25 minutes with 3
workers, the module builds included (2026-10-04).

What it does not see — the sweep stops there, and the runner lists the controls it did not measure
so the sweep can be resumed by id once the sabotage is fixed:
  - a file that reaches a removed member only through a subtype or another type that does not
    name the sabotaged one (its class file refers to that other type);
  - a constant inlined at compile time into a file that names neither its type nor a type nested
    in it;
  - a call inside a block the compiler drops (`if (false)`, a constant-false guard) in a file that
    does not name the type: the block is type-checked, but no class file refers to what it uses;
  - a secondary top-level class whose class file is not named after its source file.
It compiles with the javac of the JDK Maven runs on (the runtime `mvn -v` reports; JAVA_HOME, then
PATH, when that cannot be read) and -source/-target 21 as the pom does, so an API that JDK lacks —
one newer than it, or one removed after 21 — fails here as it does in the sweep.

A test file's sabotage is compiled with the test files that use it only: main code cannot use a test
type (a main file that names it does so in a comment), and Maven does not rebuild main for it.

Controls whose file lives outside their module (core locks that read a verifier file as text) are
not compiled — the sweep never builds those files either — but their anchors are still checked.

Run it on a worktree at the commit to be swept, not on a tree an IDE builds into:

    git worktree add --detach /tmp/nw-presweep HEAD
    python3 tools/negative-controls/compile_check_sabotages.py /tmp/nw-presweep

It first runs `mvn -o test-compile` (--no-build skips it when the tree is already built) and
`dependency:build-classpath` for each module that has .java controls. Exit 1 when any sabotage
does not compile in this screen or its anchor no longer applies.
"""
import argparse
import concurrent.futures as cf
import importlib.util
import os
import re
import struct
import subprocess
import sys
import tempfile

MAVEN_FLAGS = ["-Dskip.npm=true", "-Dskip.installnodenpm=true"]
POM_SOURCE_TARGET = "21"  # core/pom.xml and the root pom: <source>/<target> 21, no <release>
DESCRIPTOR_TYPE = re.compile(r"L([\w/$]+)[;<]")


def load_runner(root):
    """The runner's CONTROLS and sabotage_text, without running it (its main is guarded)."""
    path = os.path.join(root, "tools", "negative-controls", "run_negative_controls.py")
    spec = importlib.util.spec_from_file_location("run_negative_controls", path)
    runner = importlib.util.module_from_spec(spec)
    saved_argv, saved_bytecode = sys.argv, sys.dont_write_bytecode
    sys.argv, sys.dont_write_bytecode = [path], True  # no __pycache__ in the tree under check
    try:
        spec.loader.exec_module(runner)
    finally:
        sys.argv, sys.dont_write_bytecode = saved_argv, saved_bytecode
    return runner


def maven_javac(root):
    """The javac of the JDK Maven runs on: the runtime `mvn -v` reports, else JAVA_HOME, else PATH."""
    try:
        version = subprocess.run(["mvn", "-v"], cwd=root, capture_output=True, text=True).stdout
        found = re.search(r"runtime: (.+)$", version, re.MULTILINE)
        home = found.group(1).strip() if found else os.environ.get("JAVA_HOME", "")
    except OSError:
        home = os.environ.get("JAVA_HOME", "")
    candidate = os.path.join(home, "bin", "javac") if home else ""
    return candidate if candidate and os.path.exists(candidate) else "javac"


def prepare(root, modules, cp_dir, build):
    """Compile each module (unless build is False) and write its compile and test classpaths."""
    for module in modules:
        if build:
            subprocess.run(["mvn", "-o", "-q", "-pl", module, "test-compile", "-DskipTests"] + MAVEN_FLAGS,
                           cwd=root, check=True)
        for scope in ("compile", "test"):
            subprocess.run(["mvn", "-o", "-q", "-f", os.path.join(module, "pom.xml"), "dependency:build-classpath",
                            f"-Dmdep.outputFile={os.path.join(cp_dir, f'cp-{module}-{scope}.txt')}",
                            f"-DincludeScope={scope}"], cwd=root, check=True)


def referenced_types(class_file):
    """Internal names (a/b/C) a class file's constant pool refers to: class entries and descriptors."""
    with open(class_file, "rb") as fh:
        data = fh.read()
    if data[:4] != b"\xca\xfe\xba\xbe":
        return set()
    count = struct.unpack(">H", data[8:10])[0]
    offset, index, names, utf8, class_names = 10, 1, set(), {}, []
    while index < count:
        tag = data[offset]
        if tag == 1:  # Utf8: descriptors and signatures name types as La/b/C; or La/b/C<
            length = struct.unpack(">H", data[offset + 1:offset + 3])[0]
            utf8[index] = data[offset + 3:offset + 3 + length].decode("utf-8", "replace")
            names.update(DESCRIPTOR_TYPE.findall(utf8[index]))
            offset += 3 + length
        elif tag == 7:  # Class: its name is a Utf8 stored bare (a/b/C), or an array descriptor
            class_names.append(struct.unpack(">H", data[offset + 1:offset + 3])[0])
            offset += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            offset += 5
        elif tag in (5, 6):
            offset += 9
            index += 1  # long and double take two slots
        elif tag in (8, 16, 19, 20):
            offset += 3
        elif tag == 15:
            offset += 4
        else:
            raise ValueError(f"{class_file}: constant pool tag {tag}")
        index += 1
    for name_index in class_names:
        name = utf8.get(name_index, "")
        names.update(DESCRIPTOR_TYPE.findall(name) if name.startswith("[") else [name])
    return names


class Checker:
    def __init__(self, root, cp_dir, runner, javac_path="javac"):
        self.root = root
        self.cp_dir = cp_dir
        self.runner = runner
        self.javac_path = javac_path
        self._sources = {}
        self._referrers = {}

    def sources(self, module):
        """Every .java file of the module's main and test trees, read once."""
        if module not in self._sources:
            found = {}
            for sub in ("src/main/java", "src/test/java"):
                for directory, _, files in os.walk(os.path.join(self.root, module, sub)):
                    for name in files:
                        if name.endswith(".java"):
                            path = os.path.join(directory, name)
                            with open(path, encoding="utf-8", errors="replace") as fh:
                                found[path] = fh.read()
            self._sources[module] = found
        return self._sources[module]

    def referrers(self, module):
        """Top-level type (a/b/C) -> the source files whose class files refer to it or a type nested in it."""
        if module not in self._referrers:
            index = {}
            for out, src in (("target/classes", "src/main/java"), ("target/test-classes", "src/test/java")):
                base = os.path.join(self.root, module, out)
                for directory, _, files in os.walk(base):
                    for name in files:
                        if not name.endswith(".class"):
                            continue
                        class_file = os.path.join(directory, name)
                        internal = os.path.relpath(class_file, base)[: -len(".class")]
                        source = os.path.join(self.root, module, src, internal.split("$")[0] + ".java")
                        if not os.path.exists(source):
                            continue  # a secondary top-level class: not mapped (a stated limit)
                        for referred in referenced_types(class_file):
                            index.setdefault(referred.split("$")[0], set()).add(source)
            self._referrers[module] = index
        return self._referrers[module]

    def classpath(self, module, scope, *before):
        with open(os.path.join(self.cp_dir, f"cp-{module}-{scope}.txt")) as fh:
            dependencies = fh.read().strip()
        own = [os.path.join(self.root, module, "target", "classes")]
        if scope == "test":
            own.append(os.path.join(self.root, module, "target", "test-classes"))
        return ":".join(list(before) + own + [dependencies])

    def javac(self, files, classpath, out_dir):
        # English diagnostics: under a Japanese locale javac says エラー, not error.
        return subprocess.run([self.javac_path, "-J-Duser.language=en", "-source", POM_SOURCE_TARGET,
                               "-target", POM_SOURCE_TARGET, "-Xlint:-options", "-proc:none", "-nowarn",
                               "-encoding", "UTF-8", "-d", out_dir, "-cp", classpath] + files,
                              capture_output=True, text=True)

    def check(self, control):
        """(id, verdict, detail): 'ok …', 'skipped …', 'ANCHOR' or 'DOES NOT COMPILE …'."""
        module = control.get("module", "core")
        relative = control["file"]
        path = os.path.join(self.root, relative)
        with open(path, encoding="utf-8") as fh:
            original = fh.read()
        try:
            sabotaged = self.runner.sabotage_text(original, control)
        except SystemExit as refusal:
            return control["id"], "ANCHOR", str(refusal)[:300]
        if not relative.startswith(module + "/"):
            return control["id"], "skipped (file outside its module; anchor applies)", None
        main_root = os.path.join(self.root, module, "src", "main", "java") + os.sep
        internal = os.path.relpath(path, os.path.join(self.root, module, "src",
                                                       "main" if path.startswith(main_root) else "test", "java"))
        internal = internal[: -len(".java")]
        type_name = os.path.basename(internal)
        names_it = re.compile(r"\b" + re.escape(type_name) + r"\b")
        users = {p for p, text in self.sources(module).items() if names_it.search(text)}
        users |= self.referrers(module).get(internal, set())
        users.discard(path)
        main_users = sorted(p for p in users if p.startswith(main_root))
        test_users = sorted(p for p in users if not p.startswith(main_root))
        with tempfile.TemporaryDirectory() as source_dir, tempfile.TemporaryDirectory() as main_out, \
                tempfile.TemporaryDirectory() as test_out:
            def copies(pairs):
                written = []
                for p, text in pairs:
                    copy = os.path.join(source_dir, os.path.relpath(p, self.root))
                    os.makedirs(os.path.dirname(copy), exist_ok=True)
                    with open(copy, "w", encoding="utf-8") as out:
                        out.write(text)
                    written.append(copy)
                return written
            text_of = self.sources(module)
            if path.startswith(main_root):
                # Maven's compile step: the main classpath only, then testCompile against its output.
                steps = [(copies([(path, sabotaged)] + [(p, text_of[p]) for p in main_users]),
                          self.classpath(module, "compile"), main_out)]
                if test_users:
                    steps.append((copies([(p, text_of[p]) for p in test_users]),
                                  self.classpath(module, "test", main_out), test_out))
            else:
                # Main code cannot use a test type, and Maven does not rebuild main for a test change.
                steps = [(copies([(path, sabotaged)] + [(p, text_of[p]) for p in test_users]),
                          self.classpath(module, "test"), test_out)]
            compiled = 0
            for files, classpath, out_dir in steps:
                compiled += len(files)
                proc = self.javac(files, classpath, out_dir)
                if proc.returncode != 0:
                    errors = [line for line in (proc.stdout + proc.stderr).splitlines()
                              if " error: " in line or line.startswith("error:")][:3]
                    return control["id"], f"DOES NOT COMPILE ({compiled} files)", "\n    ".join(errors)
        return control["id"], f"ok ({compiled} files)", None


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("root", help="the worktree to check (at the commit to be swept)")
    parser.add_argument("--no-build", action="store_true", help="the tree is already test-compiled")
    parser.add_argument("--workers", type=int, default=3)
    args = parser.parse_args()
    root = os.path.abspath(args.root)
    runner = load_runner(root)
    java = [c for c in runner.CONTROLS if c["file"].endswith(".java")]
    modules = sorted({c.get("module", "core") for c in java})
    with tempfile.TemporaryDirectory() as cp_dir:
        prepare(root, modules, cp_dir, build=not args.no_build)
        javac_path = maven_javac(root)
        checker = Checker(root, cp_dir, runner, javac_path)
        print(f"checking {len(java)} .java sabotages of {len(runner.CONTROLS)} controls, "
              f"{args.workers} at a time, with {javac_path} -source/-target {POM_SOURCE_TARGET}", flush=True)
        failed, anchors, skipped = [], [], 0
        with cf.ThreadPoolExecutor(args.workers) as pool:
            for done, (cid, verdict, detail) in enumerate(pool.map(checker.check, java), 1):
                if verdict.startswith("skipped"):
                    skipped += 1
                elif verdict == "ANCHOR":
                    anchors.append(cid)
                    print(f"  {cid}: {verdict}\n    {detail}", flush=True)
                elif not verdict.startswith("ok"):
                    failed.append(cid)
                    print(f"  {cid}: {verdict}\n    {detail}", flush=True)
                if done % 200 == 0:
                    print(f"  ... {done}/{len(java)}", flush=True)
    print(f"done: {len(java) - skipped - len(failed) - len(anchors)} compile, {len(failed)} do not compile, "
          f"{len(anchors)} anchors no longer apply, {skipped} skipped (outside their module)"
          + (f": {' '.join(failed + anchors)}" if failed or anchors else ""))
    return 1 if failed or anchors else 0


if __name__ == "__main__":
    sys.exit(main())
