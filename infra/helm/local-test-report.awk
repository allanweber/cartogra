# Renders the local-test.sh check log as one self-contained HTML page.
# Input lines: KIND<TAB>EPOCH<TAB>TEXT, KIND = S (step), P (pass), F (fail).
# Variables: rc, passed, started, finished, args, cluster, commit.
BEGIN { FS = "\t"; steps = 0 }

function esc(s) {
  gsub(/&/, "\\&amp;", s); gsub(/</, "\\&lt;", s); gsub(/>/, "\\&gt;", s); gsub(/"/, "\\&quot;", s)
  return s
}

function dur(sec,   m) {
  if (sec < 60) return sec "s"
  m = int(sec / 60)
  return m "m " (sec - m * 60) "s"
}

$1 == "S" { steps++; name[steps] = $3; begin[steps] = $2; n[steps] = 0; next }
$1 == "P" || $1 == "F" {
  if (steps == 0) { steps = 1; name[1] = "Setup"; begin[1] = $2; n[1] = 0 }
  k = ++n[steps]; kind[steps, k] = $1; text[steps, k] = $3
  if ($1 == "F") failed[steps] = 1
}

END {
  ok = (rc == 0)
  for (i = 1; i <= steps; i++) end_[i] = (i < steps) ? begin[i + 1] : finished
  if (!ok && !failed[steps] && steps > 0) { stopped[steps] = 1 }

  print "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
  print "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
  print "<title>Local test report</title><style>"
  print ":root{--bg:#f6f7f9;--card:#fff;--fg:#1d2330;--muted:#6b7280;--line:#e5e7eb;--ok:#0f7b4a;--ok-bg:#e6f4ec;--bad:#b42318;--bad-bg:#fdecea}"
  print "@media (prefers-color-scheme:dark){:root{--bg:#0f1218;--card:#171b23;--fg:#e6e8ec;--muted:#9aa3b2;--line:#262c38;--ok:#4ade80;--ok-bg:#12261b;--bad:#f87171;--bad-bg:#2a1414}}"
  print "*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.5 system-ui,-apple-system,Segoe UI,sans-serif}"
  print "main{max-width:880px;margin:0 auto;padding:32px 16px 64px}"
  print "header{display:flex;flex-wrap:wrap;align-items:center;gap:12px 16px;margin-bottom:8px}"
  print "h1{font-size:22px;margin:0}.badge{padding:4px 12px;border-radius:999px;font-weight:600;font-size:13px;letter-spacing:.04em}"
  print ".badge.ok{background:var(--ok-bg);color:var(--ok)}.badge.bad{background:var(--bad-bg);color:var(--bad)}"
  print ".meta{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px;margin:20px 0 28px}"
  print ".meta div{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:10px 14px}"
  print ".meta dt{font-size:12px;color:var(--muted);text-transform:uppercase;letter-spacing:.05em}.meta dd{margin:2px 0 0;font-weight:600;overflow-wrap:anywhere}"
  print "section{background:var(--card);border:1px solid var(--line);border-radius:10px;margin-bottom:12px;overflow:hidden}"
  print "section.bad{border-color:var(--bad)}"
  print "section h2{display:flex;justify-content:space-between;gap:12px;font-size:15px;margin:0;padding:12px 16px}"
  print "section h2 .t{color:var(--muted);font-weight:400;font-variant-numeric:tabular-nums;white-space:nowrap}"
  print "ul{list-style:none;margin:0;padding:0 16px 12px}li{display:flex;gap:10px;padding:4px 0;border-top:1px solid var(--line)}"
  print "li:first-child{border-top:0}.i{font-weight:700;width:1em;flex:none}.P .i{color:var(--ok)}.F{color:var(--bad)}.F .i{color:var(--bad)}"
  print ".none{color:var(--muted);padding:0 16px 12px;font-size:14px}"
  print "</style></head><body><main>"

  printf "<header><h1>Local test report</h1><span class=\"badge %s\">%s</span></header>\n", ok ? "ok" : "bad", ok ? "PASSED" : "FAILED"
  print "<dl class=\"meta\">"
  printf "<div><dt>Checks passed</dt><dd>%d</dd></div>\n", passed
  printf "<div><dt>Duration</dt><dd>%s</dd></div>\n", dur(finished - started)
  printf "<div><dt>Started</dt><dd>%s</dd></div>\n", esc(start_text)
  printf "<div><dt>Cluster</dt><dd>%s</dd></div>\n", esc(cluster)
  printf "<div><dt>Commit</dt><dd>%s</dd></div>\n", esc(commit)
  printf "<div><dt>Options</dt><dd>%s</dd></div>\n", args == "" ? "none" : esc(args)
  if (!ok) printf "<div><dt>Exit code</dt><dd>%d</dd></div>\n", rc
  print "</dl>"

  for (i = 1; i <= steps; i++) {
    printf "<section%s><h2><span>%s</span><span class=\"t\">%s</span></h2>\n", (failed[i] || stopped[i]) ? " class=\"bad\"" : "", esc(name[i]), dur(end_[i] - begin[i])
    if (n[i] == 0 && !stopped[i]) { print "<p class=\"none\">Done, no checks in this step.</p></section>"; continue }
    print "<ul>"
    for (k = 1; k <= n[i]; k++)
      printf "<li class=\"%s\"><span class=\"i\">%s</span><span>%s</span></li>\n", kind[i, k], kind[i, k] == "P" ? "&#10003;" : "&#10007;", esc(text[i, k])
    if (stopped[i]) printf "<li class=\"F\"><span class=\"i\">&#10007;</span><span>Stopped here: a command failed (exit %d). See the terminal output.</span></li>\n", rc
    print "</ul></section>"
  }
  print "</main></body></html>"
}
