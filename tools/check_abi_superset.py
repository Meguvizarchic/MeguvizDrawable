#!/usr/bin/env python3
"""Fail when a freshly built ABI dump lost anything a published release's dump had.

`docs/abi/<version>/*-api.txt` are snapshots; nothing compared a build against the released one, so a
removed or changed public member only showed up if somebody diffed the files by hand. This reads two
`tools/dump_abi.py` outputs (published baseline, fresh build) and requires, for every class in the
baseline: the class still exists, its member lines are all still present, it did not become
`final`/`abstract`, and it still extends/implements everything it did. Additions are fine.

    check_abi_superset.py docs/abi/1.0.0/core-api.txt build/abi-now/core-api.txt [--allowed file]

`--allowed` holds reviewed removals, one `<class> :: <member line>` (or `<class> :: *`) per line with
`#` comments. Scope: the public/protected Java ABI only, not behaviour.
"""
import argparse
from pathlib import Path
import re
import sys

HEADER = re.compile(r"^(?P<mods>(?:[\w-]+ )*?)(?P<kind>@interface|interface|class|enum) (?P<name>[^\s<]+)")


def split_top_level(text, separator):
    parts, depth, current = [], 0, []
    for character in text:
        depth += (character == "<") - (character == ">")
        if character == separator and depth == 0:
            parts.append("".join(current).strip())
            current = []
        else:
            current.append(character)
    parts.append("".join(current).strip())
    return [part for part in parts if part]


def parse_declaration(line):
    text = line.strip().rstrip("{").strip()
    supertypes, depth, cut = [], 0, len(text)
    for index, character in enumerate(text):
        depth += (character == "<") - (character == ">")
        if depth == 0 and (text.startswith(" extends ", index) or text.startswith(" implements ", index)):
            cut = index
            break
    declaration, rest = text[:cut], text[cut:]
    for keyword in (" extends ", " implements "):
        for segment in re.findall(rf"{keyword}(.*?)(?= (?:extends|implements) |$)", rest):
            supertypes.extend(split_top_level(segment, ","))
    match = HEADER.match(declaration)
    if not match:
        raise ValueError(f"Unrecognised class declaration: {line!r}")
    modifiers = set(match.group("mods").split())
    return {"kind": match.group("kind"), "shape": frozenset(modifiers & {"final", "abstract"}),
            "supertypes": frozenset(supertypes)}


def parse(text):
    """Binary class name -> {declaration, members}; sections start at `# <class> (<visibility>)`."""
    classes, name, declaration, members = {}, None, None, set()

    def close():
        if name is not None and declaration is not None:
            classes[name] = {"declaration": declaration, "members": set(members)}

    for raw in text.splitlines():
        line = raw.rstrip()
        if line.startswith("# "):
            close()
            name, declaration, members = line[2:].rsplit(" (", 1)[0], None, set()
        elif not line.strip() or line == "}":
            continue
        elif not line.startswith(" "):
            declaration = parse_declaration(line)
        elif name is not None:
            members.add(line.strip())
    close()
    return classes


def read_allowed(path):
    allowed = set()
    if path:
        for line in Path(path).read_text("utf-8").splitlines():
            line = line.split("#", 1)[0].strip()
            if line:
                allowed.add(re.sub(r"\s+", " ", line))
    return allowed


def compare(published, current, allowed=frozenset()):
    problems = []
    for name, old in sorted(published.items()):
        if f"{name} :: *" in allowed:
            continue
        new = current.get(name)
        if new is None:
            problems.append(f"class removed or no longer visible: {name}")
            continue
        before, after = old["declaration"], new["declaration"]
        if before["kind"] != after["kind"]:
            problems.append(f"{name}: kind changed {before['kind']} -> {after['kind']}")
        if before["shape"] != after["shape"] and not (before["shape"] - after["shape"] == {"final"} and not after["shape"] - before["shape"]):
            problems.append(f"{name}: modifiers changed {sorted(before['shape'])} -> {sorted(after['shape'])}")
        for supertype in sorted(before["supertypes"] - after["supertypes"]):
            problems.append(f"{name}: no longer extends or implements {supertype}")
        for member in sorted(old["members"] - new["members"]):
            if member.startswith("descriptor:"):
                continue  # always paired with a member line, which is reported instead
            if f"{name} :: {re.sub(r' +', ' ', member)}" in allowed:
                continue
            problems.append(f"{name}: member missing or changed: {member}")
    return problems


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("published", type=Path)
    parser.add_argument("current", type=Path)
    parser.add_argument("--allowed", type=Path)
    args = parser.parse_args()
    published = parse(args.published.read_text("utf-8"))
    current = parse(args.current.read_text("utf-8"))
    if not published or not current:
        print("empty ABI dump; refusing to pass", file=sys.stderr)
        return 2
    problems = compare(published, current, read_allowed(args.allowed))
    for problem in problems[:100]:
        print(problem, file=sys.stderr)
    print(f"{args.published}: {len(published)} classes; {args.current}: {len(current)} classes; "
          f"{'FAILED, ' + str(len(problems)) + ' problem(s)' if problems else 'published API is still contained'}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
