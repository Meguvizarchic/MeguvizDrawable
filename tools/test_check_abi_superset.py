#!/usr/bin/env python3
"""Exercise the published-API superset check on small synthetic dumps."""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_abi_superset import compare, parse, read_allowed  # noqa: E402

BASE = """# a.B (public)
public final class a.B<T> implements java.lang.AutoCloseable {
  public a.B(int);
    descriptor: (I)V

  public int size();
    descriptor: ()I
}

# a.I (public)
public interface a.I extends java.lang.Runnable {
  public abstract void go();
    descriptor: ()V
}
"""


class SupersetContracts(unittest.TestCase):
    def problems(self, current, allowed=frozenset()):
        return compare(parse(BASE), parse(current), allowed)

    def test_identical_and_additive_dumps_pass(self):
        added = BASE.replace("implements java.lang.AutoCloseable", "implements java.lang.AutoCloseable, java.io.Serializable") \
                    .replace("  public int size();", "  public long extra();\n    descriptor: ()J\n\n  public int size();")
        self.assertEqual([], self.problems(BASE))
        self.assertEqual([], self.problems(added))
        self.assertEqual([], self.problems(BASE + "\n# a.New (public)\npublic class a.New {\n}\n"))

    def test_removed_class_member_and_changed_signature_fail(self):
        self.assertEqual(["class removed or no longer visible: a.I"], self.problems(BASE.split("# a.I")[0]))
        removed = self.problems(BASE.replace("  public int size();\n    descriptor: ()I\n", ""))
        self.assertEqual(1, len(removed), removed)
        changed = self.problems(BASE.replace("public int size();\n    descriptor: ()I", "public long size();\n    descriptor: ()J"))
        self.assertTrue(any("size" in item for item in changed))

    def test_lost_supertype_and_new_final_or_abstract_fail(self):
        self.assertTrue(any("AutoCloseable" in item for item in self.problems(BASE.replace(" implements java.lang.AutoCloseable", ""))))
        self.assertTrue(any("kind changed" in item for item in self.problems(BASE.replace("public interface a.I extends", "public abstract class a.I extends"))))
        loosened = BASE.replace("public final class a.B<T>", "public class a.B<T>")
        self.assertEqual([], self.problems(loosened))  # dropping final is compatible
        tightened = compare(parse(loosened), parse(BASE), set())
        self.assertTrue(any("modifiers" in item for item in tightened))

    def test_reviewed_removals_are_honoured(self):
        current = BASE.replace("  public int size();\n    descriptor: ()I\n", "")
        self.assertEqual([], self.problems(current, {"a.B :: public int size();"}))
        self.assertTrue(self.problems(current, {"a.B :: public int other();"}))
        self.assertEqual([], self.problems(BASE.split("# a.I")[0], {"a.I :: *"}))

    def test_allowed_file_ignores_comments(self):
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "allowed.txt"
            path.write_text("# reviewed\n\na.B :: public   int size();  # moved\n", encoding="utf-8")
            self.assertEqual({"a.B :: public int size();"}, read_allowed(path))

    def test_the_committed_1_0_0_dump_parses_and_is_not_empty(self):
        root = Path(__file__).resolve().parents[1]
        parsed = parse((root / "docs/abi/1.0.0/core-api.txt").read_text("utf-8"))
        self.assertGreater(len(parsed), 30)
        self.assertEqual([], compare(parsed, parsed))


if __name__ == "__main__":
    unittest.main(verbosity=2)
