#!/usr/bin/env python3
"""Prompt lab driver: runs on this computer, the rewrites themselves run in the app on the phone.

    tools/lab/lab.py start <run> [--models a,b@high] [--parallel 2] [--strength full]
    tools/lab/lab.py status <run>
    tools/lab/lab.py pull <run>      # copies files/lab/<run> to ~/mdreader-lab/<run> and scores it

The key never leaves the phone: the app uses its own sealed settings. Outputs hold novel text,
so they live outside the repo, in ~/mdreader-lab.
"""
import argparse
import html
import io
import json
import re
import subprocess
import sys
import tarfile
from collections import Counter, defaultdict
from pathlib import Path

PACKAGE = "com.mdreader"
OUT = Path.home() / "mdreader-lab"

BANNED = [
    "ephemeral", "enigmatic", "palpable", "tapestry", "testament to", "symphony of", "couldn't help but",
    "a mix of", "orbs", "a sense of", "it was as if", "eyes glinted", "intricate", "myriad", "unwavering",
    "a wave of", "sent shivers", "the weight of", "visceral", "ethereal", "resolute",
]
SPELLED = r"\b(two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|" \
          r"seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand|million)\b"
JUNK = [r"\$", r"\\rightarrow", r"<think>", r"===", r"```"]
COMMON_CAPS = set("""I The A An He She It They We You His Her Their Our My Your This That These Those But And Or So
Then When While If As At In On Of For With Without From To By After Before Even Yet Still Just Only Now There Here
What Who Why How Where Which No Yes Not Every Each All Some Most Many One Two Three Mr Mrs Ms Sir Lady Lord""".split())


def adb(*args, binary=False):
    out = subprocess.run(["adb", *args], capture_output=True, check=False)
    if out.returncode != 0:
        sys.exit(out.stderr.decode() or "adb failed")
    return out.stdout if binary else out.stdout.decode()


def start(a):
    cmd = ["shell", "am", "start", "-n", f"{PACKAGE}/.LabActivity", "--es", "run", a.run, "--ei", "parallel", str(a.parallel)]
    if a.models:
        cmd += ["--es", "models", a.models]
    if a.strength:
        cmd += ["--es", "strength", a.strength]
    if a.chapters:
        cmd += ["--es", "chapters", a.chapters]
    print(adb(*cmd).strip())


def status(a):
    text = adb("shell", f"run-as {PACKAGE} cat files/lab/{a.run}/results.jsonl 2>/dev/null; run-as {PACKAGE} ls files/lab/{a.run}/DONE 2>/dev/null; true")
    rows = [json.loads(l) for l in text.splitlines() if l.startswith("{")]
    done = "DONE" in text
    print(f"{len(rows)} rewrites finished{' · run complete' if done else ' · still running'}")
    for r in rows:
        print(f"  {r['model']:28} {r['chapter']:28} " + (f"ERROR {r['error']}" if "error" in r else f"{r['words']}w / {r['sourceWords']}w · {r['passes']} pass · {r['seconds']}s"))


def pull(a):
    data = adb("exec-out", f"run-as {PACKAGE} tar cf - -C files lab/{a.run}", binary=True)
    dest = OUT
    dest.mkdir(parents=True, exist_ok=True)
    with tarfile.open(fileobj=io.BytesIO(data)) as tar:
        tar.extractall(dest, filter="data")
    run = dest / "lab" / a.run
    print(f"pulled to {run}")
    report(run)


def words(text):
    return re.findall(r"[\w’']+", text)


FILLER = set("""of and a an the in on to his her their same other first last next only whole rest with was were is
had has at from for as who that which by into onto over under before after he she it they him them said but or so
then again too could would did""".split())


def story(source):
    """The source without translator notes and footnote markers, whose numbers are not story."""
    keep = [l for l in source.splitlines() if "☜" not in l and not re.match(r"^\s*\d+\\?\.\s*[”\"“]", l)]
    return re.sub(r"\\?\[\d+\\?\]", "", "\n".join(keep))


def trigrams(text):
    w = re.findall(r"[^\W\d_][\w’'-]*", text)
    return Counter(" ".join(t).lower() for t in zip(w, w[1:], w[2:])
                   if t[0].lower() == "the" and t[1].lower() not in FILLER and t[2].lower() not in FILLER
                   and t[1][0].islower() and t[2][0].islower())


def score(source, out):
    sw, ow = len(words(source)), len(words(out))
    source = story(source)
    src_nums = set(re.findall(r"\d[\d,.]*\d|\d", source))
    out_nums = set(re.findall(r"\d[\d,.]*\d|\d", out))
    low = out.lower()
    banned = {b: low.count(b) for b in BANNED if b in low}
    spelled_src = len(re.findall(SPELLED, source.lower()))
    spelled_all = [m.group(0) for m in re.finditer(SPELLED + r"(-year-old)?", low)]
    # Only spelled numbers beyond what the source itself spells out count against the rewrite.
    spelled = spelled_all[spelled_src:] if len(spelled_all) > spelled_src else []
    src_tri = trigrams(source)
    epithets = {k: v for k, v in trigrams(out).items() if v >= 3 and src_tri.get(k, 0) < 2}
    src_lower = {w.lower() for w in re.findall(r"[^\W\d_]+", source)}
    singles = [m.group(1) for m in re.finditer(r"(?<=[a-z,;] )([A-Z][^\W\d_A-Z]{2,})(?![^\W\d_’'])", out)]
    labels = [m.group(1) for m in re.finditer(r"[“\"]([A-Z][a-z]+(?: [A-Z][a-z]+)+)[”\"]", out)
              if any(w.lower() not in src_lower for w in m.group(1).split())]
    new_names = sorted({w for w in singles if w.lower() not in src_lower and w not in COMMON_CAPS} | set(labels))
    junk = sum(len(re.findall(j, out)) for j in JUNK)
    return {
        "ratio": round(ow / max(sw, 1), 2),
        "missingNumbers": sorted(src_nums - out_nums),
        "spelled": spelled,
        "banned": banned,
        "epithets": epithets,
        "newNames": new_names,
        "junk": junk,
        "dialogue": (out.count("“") + out.count('"') // 2, source.count("“") + source.count('"') // 2),
    }


def iter_words(text):
    return re.findall(r"[a-z’']+", text)


def issues(s):
    return len(s["missingNumbers"]) + len(s["spelled"]) + sum(s["banned"].values()) + len(s["epithets"]) + len(s["newNames"]) + s["junk"] + (1 if s["ratio"] < 1.0 else 0)


def report(run: Path):
    results = [json.loads(l) for l in (run / "results.jsonl").read_text().splitlines() if l.strip()]
    sources = {p.name.removesuffix(".source.md"): p.read_text() for p in run.glob("*.source.md")}
    models = sorted({r["model"] for r in results})
    chapters = sorted(sources)
    cells, per_model = {}, defaultdict(list)
    for r in results:
        folder = run / re.sub(r'[\\/:*?"<>|]', "", r["model"]).replace(" ", "_")
        f = folder / f"{r['chapter']}.md"
        if "error" in r or not f.exists():
            cells[(r["model"], r["chapter"])] = (None, r)
            per_model[r["model"]].append(None)
            continue
        s = score(sources[r["chapter"]], f.read_text())
        cells[(r["model"], r["chapter"])] = ((s, f.read_text()), r)
        per_model[r["model"]].append((s, r))

    lines = [f"Run {run.name}", ""]
    lines.append(f"{'model':30} {'ok':>4} {'ratio':>6} {'issues':>7} {'2nd':>4} {'sec':>6}")
    for m in models:
        ok = [x for x in per_model[m] if x]
        if not ok:
            lines.append(f"{m:30} {0:>4}")
            continue
        ratio = sum(s['ratio'] for s, _ in ok) / len(ok)
        iss = sum(issues(s) for s, _ in ok) / len(ok)
        second = sum(1 for _, r in ok if r.get("passes", 1) > 1)
        sec = sum(r["seconds"] for _, r in ok) / len(ok)
        lines.append(f"{m:30} {len(ok):>4} {ratio:>6.2f} {iss:>7.1f} {second:>4} {sec:>6.0f}")
    summary = "\n".join(lines)
    print(summary)
    (run / "summary.txt").write_text(summary + "\n")

    def detail(s):
        parts = [f"ratio {s['ratio']}", f"dialogue {s['dialogue'][0]}/{s['dialogue'][1]}"]
        for key, label in [("missingNumbers", "missing numbers"), ("spelled", "spelled numbers"), ("newNames", "new names")]:
            if s[key]:
                parts.append(f"{label}: {', '.join(map(str, s[key][:8]))}")
        if s["banned"]:
            parts.append("banned: " + ", ".join(f"{k}×{v}" for k, v in s["banned"].items()))
        if s["epithets"]:
            parts.append("repeated: " + ", ".join(f"“{k}”×{v}" for k, v in s["epithets"].items()))
        if s["junk"]:
            parts.append(f"markup junk ×{s['junk']}")
        return " · ".join(parts)

    def prose(text):
        return "".join(f"<p>{html.escape(p)}</p>" for p in text.split("\n\n") if p.strip())

    out = [f"""<!doctype html><meta charset=utf-8><title>Lab {html.escape(run.name)}</title>
<style>body{{background:#111110;color:#e8e4dc;font:14px system-ui;margin:20px}}pre{{color:#bbb}}
.row{{display:flex;gap:14px;overflow-x:auto;padding-bottom:12px}}.col{{min-width:380px;max-width:380px;background:#1a1918;
border:1px solid #2c2a27;padding:12px;height:70vh;overflow-y:auto}}.col p{{font:15px/1.6 Georgia,serif;color:#d9d5cc}}
h2{{color:#e05a44;font-size:15px;margin-top:30px}}h3{{font-size:12px;margin:0 0 6px}}.m{{font-size:11px;color:#9a958b;margin-bottom:8px}}
</style><h1 style="font-size:16px">Prompt lab · {html.escape(run.name)}</h1><pre>{html.escape(summary)}</pre>
<p class=m>Scroll each row sideways. Mark the passages you love; they become the golden samples.</p>"""]
    for c in chapters:
        out.append(f"<h2>{html.escape(c)}</h2><div class=row><div class=col><h3>SOURCE</h3>{prose(sources[c])}</div>")
        for m in models:
            got, r = cells.get((m, c), (None, {}))
            if got is None:
                out.append(f"<div class=col><h3>{html.escape(m)}</h3><div class=m>{html.escape(r.get('error', 'missing'))}</div></div>")
                continue
            s, text = got
            meta = f"{r.get('passes', 1)} pass · {r.get('seconds')}s · {detail(s)}"
            out.append(f"<div class=col><h3>{html.escape(m)}</h3><div class=m>{html.escape(meta)}</div>{prose(text)}</div>")
        out.append("</div>")
    (run / "report.html").write_text("".join(out))
    print(f"report: {run / 'report.html'}")


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("start")
    s.add_argument("run")
    s.add_argument("--models")
    s.add_argument("--chapters")
    s.add_argument("--strength")
    s.add_argument("--parallel", type=int, default=2)
    s.set_defaults(fn=start)
    for name, fn in [("status", status), ("pull", pull)]:
        q = sub.add_parser(name)
        q.add_argument("run")
        q.set_defaults(fn=fn)
    r = sub.add_parser("report")
    r.add_argument("path")
    r.set_defaults(fn=lambda a: report(Path(a.path)))
    a = p.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
