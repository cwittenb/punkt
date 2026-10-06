"""Baut die App-Fassung der Web-App: web/punkt.html -> app/src/main/assets/web/index.html

- ergänzt das Dokumentgerüst (Doctype, Viewport, Rand-Reset), das der Artefakt-Host sonst selbst setzt
- entfernt den Google-Fonts-Link; die App hat kein Internet und bringt die Schriften selbst mit
- bettet web/engine.js ein, damit die App eine einzige Datei lädt
"""
import pathlib
import re
import sys

wurzel = pathlib.Path(__file__).resolve().parent.parent
quelle = wurzel / "web" / "punkt.html"
ziel = wurzel / "app" / "src" / "main" / "assets" / "web" / "index.html"

s = quelle.read_text(encoding="utf-8")
s = re.sub(r'<link[^>]*fonts\.(googleapis|gstatic)\.com[^>]*>\s*', "", s)
engine = (wurzel / "web" / "engine.js").read_text(encoding="utf-8")
marke = '<script src="engine.js"></script>'
if marke not in s:
    sys.exit("web/punkt.html bindet engine.js nicht ein")
s = s.replace(marke, "<script>\n" + engine + "\n</script>", 1)
if "fonts.googleapis" in s or "fonts.gstatic" in s:
    sys.exit("Google-Fonts-Verweis nicht vollständig entfernt")
if s.lstrip().lower().startswith("<!doctype"):
    sys.exit("web/punkt.html hat schon ein Gerüst; erwartet wird die Artefakt-Fassung ohne")

kopf = (
    '<!doctype html><html lang="de"><head><meta charset="utf-8">'
    '<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">'
    "<style>html,body{margin:0;padding:0}html{background:var(--page)}</style>"
    "</head><body>\n"
)
ziel.parent.mkdir(parents=True, exist_ok=True)
ziel.write_text(kopf + s + "\n</body></html>\n", encoding="utf-8")
print(f"{ziel.relative_to(wurzel)}: {ziel.stat().st_size} Bytes")
