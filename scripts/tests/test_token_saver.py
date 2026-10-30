"""Token-saver measurement (reference implementation).

This mirrors the rules in app/.../agent/TokenSaver.kt EXACTLY
(JSON minify, comment stripping, repeated-line dedup, marked
truncation that always keeps error lines + the tail) so savings can
be measured here — the VM has no kotlinc, so the Kotlin file itself
cannot be executed. Figures below are therefore the measured savings
of the shared rules, NOT an on-device Kotlin run; on-device, every
agent run prints its own before/after token estimate in chat.

Safety properties asserted: error lines are never dropped, the last
lines of a log are never dropped, every cut carries an explicit
"[truncated, N lines omitted]" marker.
"""
import json
import re
import unittest

ERROR_RX = re.compile(r"error|exception|failed|failure|panic|traceback|fatal|assert", re.I)


def minify_json(t):
    out, in_str, esc = [], False, False
    for c in t:
        if esc:
            out.append(c); esc = False
        elif c == "\\" and in_str:
            out.append(c); esc = True
        elif c == '"':
            in_str = not in_str; out.append(c)
        elif not in_str and c in " \n\r\t":
            pass
        else:
            out.append(c)
    return "".join(out)


def strip_comments(t):
    out, i, in_str, str_ch, in_line, in_block = [], 0, False, '"', False, False
    while i < len(t):
        c = t[i]
        n = t[i + 1] if i + 1 < len(t) else "\0"
        if in_line:
            if c == "\n":
                in_line = False; out.append(c)
        elif in_block:
            if c == "*" and n == "/":
                in_block = False; i += 1
        elif in_str:
            out.append(c)
            if c == "\\" and i + 1 < len(t):
                out.append(t[i + 1]); i += 1
            elif c == str_ch:
                in_str = False
        elif c in "\"'`":
            in_str, str_ch = True, c; out.append(c)
        elif c == "/" and n == "/":
            in_line = True
        elif c == "/" and n == "*":
            in_block = True; i += 1
        else:
            out.append(c)
        i += 1
    return "".join(out)


def dedup_lines(t):
    lines = t.split("\n")
    if len(lines) < 4:
        return t
    out, i = [], 0
    while i < len(lines):
        j = i + 1
        while j < len(lines) and lines[j] == lines[i]:
            j += 1
        run = j - i
        if run >= 3 and not ERROR_RX.search(lines[i]):
            out += [lines[i], f"[{run - 1} identical lines omitted]"]
        else:
            out += [lines[i]] * run
        i = j
    return "\n".join(out)


def truncate_marked(t, max_lines=1200):
    lines = t.split("\n")
    if len(lines) <= max_lines:
        return t
    head, tail = lines[:800], lines[-100:]
    errors = [l for l in lines if ERROR_RX.search(l)][:100]
    omitted = len(lines) - len(head) - len(tail) - len(errors)
    return "\n".join(head + [f"[truncated, {omitted} lines omitted — error lines and tail preserved]"] + errors + tail)


def compress(text, code_aware=False):
    out = text
    s = out.lstrip()
    if (s.startswith("{") or s.startswith("[")) and len(s) > 40:
        out = minify_json(out)
    if code_aware:
        out = strip_comments(out)
    out = dedup_lines(out)
    out = truncate_marked(out)
    return out


def tokens(chars):
    return chars // 4


class TestTokenSaver(unittest.TestCase):
    def test_json_blob(self):
        blob = {"models": [{"id": f"model-{i}", "name": f"Model {i}",
                            "tags": ["free", "chat", "code"], "meta": {"a": 1, "b": [1, 2, 3]}}
                           for i in range(60)]}
        pretty = json.dumps(blob, indent=2)
        got = compress(pretty)
        self.assertEqual(json.loads(got), blob, "minified JSON must parse to the same value")
        print(f"\nJSON blob: {tokens(len(pretty))} -> {tokens(len(got))} tokens "
              f"(saved {100 * (len(pretty) - len(got)) // len(pretty)}%)")

    def test_code_file(self):
        with open("app/src/main/java/com/ersaz/hermesmobile/agent/TokenSaver.kt") as f:
            code = f.read()
        got = compress(code, code_aware=True)
        self.assertNotIn("SAFETY RULES (never violated)", got, "comments must be stripped")
        print(f"\nCode file: {tokens(len(code))} -> {tokens(len(got))} tokens "
              f"(saved {100 * (len(code) - len(got)) // len(code)}%)")

    def test_log_safety(self):
        lines = [f"INFO worker tick {i}" for i in range(50)]
        lines += ["INFO heartbeat ok"] * 40
        lines += [f"INFO worker tick {100 + i}" for i in range(1500)]
        lines += ["ERROR disk write failed on /data/x", "Traceback (most recent call last)"]
        lines += [f"INFO tail {i}" for i in range(50)]
        log = "\n".join(lines)
        got = compress(log)
        self.assertIn("ERROR disk write failed", got, "error lines must survive")
        self.assertIn("Traceback (most recent call last)", got, "traceback must survive")
        self.assertIn("INFO tail 49", got, "log tail must survive")
        self.assertIn("[truncated,", got, "cuts must be marked")
        self.assertIn("identical lines omitted", got, "repeated lines must collapse")
        print(f"\nLog: {tokens(len(log))} -> {tokens(len(got))} tokens "
              f"(saved {100 * (len(log) - len(got)) // len(log)}%)")


if __name__ == "__main__":
    unittest.main(verbosity=2)
