#!/usr/bin/env python3
"""Render a security audit findings.json into an A4 PDF with headless Chrome.

Usage: python3 -I build_report.py <findings.json> <output.pdf>

Uses only the Python standard library. The HTML file is kept beside the PDF.
"""

import html
import json
import math
import shutil
import subprocess
import sys
from pathlib import Path

SEVERITIES = ["critical", "high", "medium", "low", "info"]
COLORS = {
    "critical": "#B91C1C",
    "high": "#EA580C",
    "medium": "#D97706",
    "low": "#2563EB",
    "info": "#6B7280",
    "strength": "#059669",
}
CATEGORIES = {
    "1": "Unlocked database (tenant and owner isolation)",
    "2": "Permission decided in the browser",
    "3": "IDOR",
    "4": "Exposed keys (hard-coded secrets)",
    "5": "Unsanitized input (XSS)",
    "other": "Other",
}


def e(value):
    return html.escape("" if value is None else str(value))


def chip(severity):
    color = COLORS.get(severity, COLORS["info"])
    return f'<span class="chip" style="background:{color}">{e(severity.upper())}</span>'


def donut(counts):
    total = sum(counts.values())
    size, radius, width = 220, 80, 34
    cx = cy = size / 2
    parts = [f'<svg width="{size}" height="{size}" viewBox="0 0 {size} {size}">']
    if total == 0:
        parts.append(f'<circle cx="{cx}" cy="{cy}" r="{radius}" fill="none" stroke="{COLORS["strength"]}" '
                     f'stroke-width="{width}"/>')
    else:
        angle = -math.pi / 2
        for sev in SEVERITIES:
            n = counts.get(sev, 0)
            if n == 0:
                continue
            sweep = 2 * math.pi * n / total
            if n == total:
                parts.append(f'<circle cx="{cx}" cy="{cy}" r="{radius}" fill="none" stroke="{COLORS[sev]}" '
                             f'stroke-width="{width}"/>')
                break
            x1, y1 = cx + radius * math.cos(angle), cy + radius * math.sin(angle)
            x2, y2 = cx + radius * math.cos(angle + sweep), cy + radius * math.sin(angle + sweep)
            large = 1 if sweep > math.pi else 0
            parts.append(f'<path d="M {x1:.2f} {y1:.2f} A {radius} {radius} 0 {large} 1 {x2:.2f} {y2:.2f}" '
                         f'fill="none" stroke="{COLORS[sev]}" stroke-width="{width}"/>')
            angle += sweep
    parts.append(f'<text x="{cx}" y="{cy - 4}" text-anchor="middle" class="donut-n">{total}</text>')
    parts.append(f'<text x="{cx}" y="{cy + 18}" text-anchor="middle" class="donut-l">findings</text></svg>')
    legend = "".join(f'<div><span class="sw" style="background:{COLORS[s]}"></span>{e(s.capitalize())}: '
                     f'{counts.get(s, 0)}</div>' for s in SEVERITIES)
    return f'<div class="donut">{"".join(parts)}<div class="legend">{legend}</div></div>'


def bars(findings):
    rows = []
    per_cat = {c: {s: 0 for s in SEVERITIES} for c in CATEGORIES}
    for f in findings:
        per_cat.setdefault(str(f.get("category", "other")), {s: 0 for s in SEVERITIES})[f["severity"]] += 1
    peak = max([sum(v.values()) for v in per_cat.values()] + [1])
    label_w, bar_w, row_h = 300, 330, 30
    height = row_h * len(per_cat) + 10
    rows.append(f'<svg width="{label_w + bar_w + 40}" height="{height}">')
    for i, (cat, sev_counts) in enumerate(per_cat.items()):
        y = i * row_h + 5
        rows.append(f'<text x="{label_w - 8}" y="{y + 17}" text-anchor="end" class="bar-l">'
                    f'{e(CATEGORIES.get(cat, cat))}</text>')
        x = label_w
        for sev in SEVERITIES:
            n = sev_counts[sev]
            if n:
                w = bar_w * n / peak
                rows.append(f'<rect x="{x:.1f}" y="{y}" width="{w:.1f}" height="22" fill="{COLORS[sev]}"/>')
                x += w
        rows.append(f'<text x="{x + 6:.1f}" y="{y + 17}" class="bar-n">{sum(sev_counts.values())}</text>')
    rows.append("</svg>")
    return "".join(rows)


def build_html(data):
    findings = sorted(data.get("findings", []),
                      key=lambda f: (str(f.get("category", "other")), SEVERITIES.index(f["severity"])))
    counts = {s: sum(1 for f in findings if f["severity"] == s) for s in SEVERITIES}
    title = f'Security Audit Report — {data.get("project", "Project")}'

    methodology = "".join(f'<tr><td>{e(CATEGORIES.get(str(m["category"]), m["category"]))}</td>'
                          f'<td>{e(m["mapping"])}</td></tr>' for m in data.get("methodology", []))
    not_applicable = "".join(f'<li><b>{e(CATEGORIES.get(str(n["category"]), n["category"]))}:</b> '
                             f'{e(n["reason"])}</li>' for n in data.get("not_applicable", []))
    summary_cards = "".join(f'<div class="card" style="border-color:{COLORS[s]}"><div class="card-n" '
                            f'style="color:{COLORS[s]}">{counts[s]}</div>{e(s.capitalize())}</div>'
                            for s in SEVERITIES)
    strengths = "".join(f'<li><span class="chip" style="background:{COLORS["strength"]}">OK</span> '
                        f'{e(s["text"])} <code>{e(s.get("evidence", ""))}</code></li>'
                        for s in data.get("strengths", []))
    weaknesses = "".join(f"<li>{e(w)}</li>" for w in data.get("weaknesses", []))

    tables = []
    for cat, label in CATEGORIES.items():
        rows = [f for f in findings if str(f.get("category", "other")) == cat]
        if not rows:
            continue
        body = "".join(
            f'<tr><td>{chip(f["severity"])}</td><td><code>{e(f["file"])}:{e(f["lines"])}</code></td>'
            f'<td><b>{e(f.get("id", ""))}</b> {e(f["description"])}'
            f'<div class="sub"><b>Exploitability:</b> {e(f.get("exploitability", ""))}</div>'
            + (f'<div class="sub"><b>Conditions:</b> {e(f["conditions"])}</div>' if f.get("conditions") else "")
            + (f'<pre>{e(f["snippet"])}</pre>' if f.get("snippet") else "")
            + (f'<div class="sub src">Source: {e(f["source"])}</div>' if f.get("source") else "")
            + "</td></tr>" for f in rows)
        tables.append(f'<h3>{e(label)}</h3><table class="findings"><thead><tr><th>Severity</th>'
                      f'<th>File:line</th><th>Description</th></tr></thead><tbody>{body}</tbody></table>')

    recommendations = "".join(f'<tr><td><b>{e(r["priority"])}</b></td><td>{e(r["text"])}</td>'
                              f'<td>{e(", ".join(r.get("findings", [])))}</td></tr>'
                              for r in data.get("recommendations", []))
    issues = "".join(f'<pre class="issue">--- ISSUE {i["n"]} ---\n{e(i["body"])}\n--- END ISSUE {i["n"]} ---</pre>'
                     for i in data.get("issues", []))

    return f"""<!doctype html><html lang="en"><head><meta charset="utf-8"><title>{e(title)}</title><style>
@page {{ size: A4; margin: 20mm;
  @top-left {{ content: "{e(title)}"; font: 9pt sans-serif; color: #64748b; }}
  @bottom-right {{ content: "Page " counter(page) " of " counter(pages); font: 9pt sans-serif; color: #64748b; }} }}
@page :first {{ @top-left {{ content: none; }} }}
body {{ font: 10.5pt/1.45 "Inter", "Helvetica Neue", Arial, sans-serif; color: #0f172a; }}
h1 {{ font-size: 26pt; margin: 0 0 6mm; }} h2 {{ font-size: 16pt; border-bottom: 2px solid #e2e8f0;
  padding-bottom: 2mm; margin-top: 0; }} h3 {{ font-size: 12pt; margin: 6mm 0 2mm; }}
.cover {{ height: 240mm; display: flex; flex-direction: column; justify-content: center; }}
.meta td {{ padding: 1mm 4mm 1mm 0; vertical-align: top; }}
section {{ break-before: page; }}
table {{ width: 100%; border-collapse: collapse; }} th, td {{ text-align: left; vertical-align: top;
  padding: 2mm; border-bottom: 1px solid #e2e8f0; }} th {{ background: #f1f5f9; font-size: 9.5pt; }}
tr {{ break-inside: avoid; }}
code, pre {{ font-family: "JetBrains Mono", "DejaVu Sans Mono", monospace; font-size: 8.5pt; }}
pre {{ background: #f8fafc; border: 1px solid #e2e8f0; padding: 2mm; white-space: pre-wrap;
  word-break: break-word; margin: 1.5mm 0; }}
.findings td:first-child {{ width: 22mm; }} .findings td:nth-child(2) {{ width: 48mm; word-break: break-all; }}
.chip {{ display: inline-block; color: #fff; border-radius: 3mm; padding: 0.4mm 2.5mm; font-size: 8pt;
  font-weight: 700; }}
.sub {{ font-size: 9pt; color: #334155; margin-top: 1mm; }} .src {{ color: #64748b; }}
.cards {{ display: flex; gap: 3mm; margin: 4mm 0; }} .card {{ flex: 1; border: 2px solid; border-radius: 2mm;
  padding: 2mm; text-align: center; }} .card-n {{ font-size: 20pt; font-weight: 700; }}
.charts {{ display: flex; flex-direction: column; gap: 6mm; }} .donut {{ display: flex; align-items: center;
  gap: 8mm; }} .donut-n {{ font-size: 28pt; font-weight: 700; }} .donut-l {{ font-size: 10pt; fill: #64748b; }}
.legend div {{ margin: 1mm 0; }} .sw {{ display: inline-block; width: 4mm; height: 4mm; margin-right: 2mm;
  vertical-align: middle; }} .bar-l {{ font-size: 9.5pt; }} .bar-n {{ font-size: 9.5pt; font-weight: 700; }}
ul.plain {{ list-style: none; padding: 0; }} ul.plain li {{ margin: 2mm 0; }}
.issue {{ break-inside: auto; font-size: 8.5pt; }}
</style></head><body>
<div class="cover"><h1>{e(title)}</h1><table class="meta">
<tr><td><b>Date</b></td><td>{e(data.get("date", ""))}</td></tr>
<tr><td><b>Scope</b></td><td>{e(data.get("scope", ""))}</td></tr>
<tr><td><b>Stack</b></td><td>{e(data.get("stack", ""))}</td></tr></table>
<h3>Method</h3><table><thead><tr><th>Category</th><th>Mapping to this stack</th></tr></thead>
<tbody>{methodology}</tbody></table>{f'<h3>Categories that do not apply</h3><ul>{not_applicable}</ul>'
                                     if not_applicable else ''}</div>
<section><h2>Executive summary</h2><div class="cards">{summary_cards}</div>
<div class="charts">{donut(counts)}{bars(findings)}</div></section>
<section><h2>Strengths</h2><ul class="plain">{strengths or "<li>None recorded.</li>"}</ul>
<h2>Weaknesses</h2><ul>{weaknesses or "<li>None recorded.</li>"}</ul></section>
<section><h2>Findings by category</h2>{"".join(tables) or "<p>No findings.</p>"}</section>
<section><h2>Recommendations</h2><table><thead><tr><th>Priority</th><th>Action</th><th>Findings</th></tr>
</thead><tbody>{recommendations}</tbody></table></section>
<section><h2>Issues for GitHub</h2>{issues or "<p>No actionable findings.</p>"}</section>
</body></html>"""


def find_chrome():
    for name in ("google-chrome", "chromium", "chromium-browser", "chrome"):
        path = shutil.which(name)
        if path:
            return path
    sys.exit("No Chrome or Chromium found. Install one, or open the HTML file and print it to PDF.")


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    for f in data.get("findings", []):
        if f.get("severity") not in SEVERITIES:
            sys.exit(f'Finding {f.get("id")} has an unknown severity: {f.get("severity")}')
    pdf = Path(sys.argv[2]).resolve()
    pdf.parent.mkdir(parents=True, exist_ok=True)
    page = pdf.with_suffix(".html")
    page.write_text(build_html(data), encoding="utf-8")
    subprocess.run([find_chrome(), "--headless", "--disable-gpu", "--no-pdf-header-footer",
                    f"--print-to-pdf={pdf}", page.as_uri()], check=True, capture_output=True)
    print(pdf)


if __name__ == "__main__":
    main()
