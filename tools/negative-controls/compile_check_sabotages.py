#!/usr/bin/env python3
"""Before a sweep: does every .java sabotage of run_negative_controls.py still compile?

A sabotage that no longer compiles stops the sweep at that control, hours in, and every control
after it goes unmeasured. The runner refuses a drifted anchor before it starts, but an anchor can
still match where the sabotage no longer compiles: QS3's anchor followed its code into an extracted
method where a name it uses is not in scope, and HM3's edit still compiled in its own file while
another file of the module used the constant it removes (2026-09-25, the sixth sweep stopped at its
827th control). The runner's own --compile-check builds the module once per control with Maven,
about a minute each — as long as the sweep itself.

This applies the runner's sabotage_text to each control in memory and compiles the sabotaged file
together with every file of the same module that names its type (\\bType\\b) with javac, against
that module's target/classes, target/test-classes and test-scope dependencies. Sources go to a
temporary directory and classes to another; the tree's sources are not written. 1,611 sabotages
took about 20 minutes with 3 workers (2026-10-04).

Controls whose file lives outside their module (core locks that read a verifier file as text) are
skipped: the sweep never builds those files either.

Run it on a worktree at the commit to be swept, not on a tree an IDE builds into:

    git worktree add --detach /tmp/nw-presweep HEAD
    python3 tools/negative-controls/compile_check_sabotages.py /tmp/nw-presweep

It first runs `mvn -o test-compile` and `dependency:build-classpath` for each module that has
.java controls (--no-build skips the compile when the tree is already built). Exit 1 when any
sabotage does not compile.
"""
import argparse
import concurrent.futures as cf
import importlib.util
import os
import re
import subprocess
import sys
import tempfile

MAVEN_FLAGS = ["-Dskip.npm=true", "-Dskip.installnodenpm=true"]


def load_runner(root):
    """The runner's CONTROLS and sabotage_text, without running it (its main is guarded)."""
    path = os.path.join(root, "tools", "negative-controls", "run_negative_controls.py")
    spec = importlib.util.spec_from_file_location("run_negative_controls", path)
    runner = importlib.util.module_from_spec(spec)
    saved = sys.argv
    sys.argv = [path]
    try:
        spec.loader.exec_module(runner)
    finally:
        sys.argv = saved
    return runner


def prepare(root, modules, cp_dir, build):
    """Compile each module's main and test classes (unless build is False) and write its classpath."""
    for module in modules:
        if build:
            subprocess.run(["mvn", "-o", "-q", "-pl", module, "test-compile", "-DskipTests"] + MAVEN_FLAGS,
                           cwd=root, check=True)
        subprocess.run(["mvn", "-o", "-q", "-f", os.path.join(module, "pom.xml"), "dependency:build-classpath",
                        f"-Dmdep.outputFile={os.path.join(cp_dir, f'cp-{module}.txt')}",
                        "-Dmdep.includeScope=test"], cwd=root, check=True)


class Checker:
    def __init__(self, root, cp_dir, runner):
        self.root = root
        self.cp_dir = cp_dir
        self.runner = runner
        self._sources = {}

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

    def classpath(self, module):
        with open(os.path.join(self.cp_dir, f"cp-{module}.txt")) as fh:
            dependencies = fh.read().strip()
        return ":".join([os.path.join(self.root, module, "target", "classes"),
                         os.path.join(self.root, module, "target", "test-classes"), dependencies])

    def check(self, control):
        """(id, verdict, detail): verdict is 'ok …', 'skipped …', 'ANCHOR' or 'DOES NOT COMPILE …'."""
        module = control.get("module", "core")
        relative = control["file"]
        if not relative.startswith(module + "/"):
            return control["id"], "skipped (file outside its module)", None
        path = os.path.join(self.root, relative)
        with open(path, encoding="utf-8") as fh:
            original = fh.read()
        try:
            sabotaged = self.runner.sabotage_text(original, control)
        except SystemExit as refusal:
            return control["id"], "ANCHOR", str(refusal)[:300]
        type_name = os.path.basename(relative)[: -len(".java")]
        names_it = re.compile(r"\b" + re.escape(type_name) + r"\b")
        dependents = [p for p, text in self.sources(module).items() if p != path and names_it.search(text)]
        with tempfile.TemporaryDirectory() as source_dir, tempfile.TemporaryDirectory() as class_dir:
            files = []
            for p, text in [(path, sabotaged)] + [(p, self.sources(module)[p]) for p in dependents]:
                copy = os.path.join(source_dir, os.path.relpath(p, self.root))
                os.makedirs(os.path.dirname(copy), exist_ok=True)
                with open(copy, "w", encoding="utf-8") as fh:
                    fh.write(text)
                files.append(copy)
            # English diagnostics: under a Japanese locale javac says エラー, not error.
            proc = subprocess.run(["javac", "-J-Duser.language=en", "--release", "21", "-proc:none", "-nowarn",
                                   "-encoding", "UTF-8", "-d", class_dir, "-cp", self.classpath(module)] + files,
                                  capture_output=True, text=True)
        if proc.returncode != 0:
            errors = [line for line in (proc.stdout + proc.stderr).splitlines()
                      if " error: " in line or line.startswith("error:")][:3]
            return control["id"], f"DOES NOT COMPILE ({len(files)} files)", "\n    ".join(errors)
        return control["id"], f"ok ({len(files)} files)", None


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
        checker = Checker(root, cp_dir, runner)
        print(f"checking {len(java)} .java sabotages of {len(runner.CONTROLS)} controls, "
              f"{args.workers} at a time", flush=True)
        bad, skipped = [], 0
        with cf.ThreadPoolExecutor(args.workers) as pool:
            for done, (cid, verdict, detail) in enumerate(pool.map(checker.check, java), 1):
                if verdict.startswith("skipped"):
                    skipped += 1
                elif not verdict.startswith("ok"):
                    bad.append(cid)
                    print(f"  {cid}: {verdict}\n    {detail}", flush=True)
                if done % 200 == 0:
                    print(f"  ... {done}/{len(java)}", flush=True)
    print(f"done: {len(java) - skipped - len(bad)} compile, {len(bad)} do not, {skipped} skipped "
          f"(outside their module){': ' + ' '.join(bad) if bad else ''}")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
