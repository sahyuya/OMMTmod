# Wiki remake verification — 2026-09-08

Scope: user wiki, generator, and Pages workflow only. No MOD/plugin production logic changed by this task.

## PASS

- `node OMMT/tools/wiki/build.mjs`: 28 bilingual articles, 126 search entries.
- `node OMMT/tools/wiki/verify.mjs`: 29 HTML documents including 404; internal links, assets, fragment targets and duplicate IDs.
- With `WIKI_NODE_MODULES` pointing to the installed Playwright node_modules, the same verify command passed in headless Microsoft Edge.
- Browser checks: all articles at desktop width; Japanese body search, aliases, NFKC normalization, no results, HTML input treated as text; keyboard result navigation and Escape; persisted theme; widths 320, 390, 768; English search; reading without JavaScript.
- Test HTTP server used the `/OMMTmod/` project subpath.
- Desktop home, search and mobile screenshots visually inspected. Screenshots are local build outputs under `build/wiki-review/`.

## NOT RUN

- Public GitHub Pages deployment: no commit/push/deployment performed.
- Safari, Firefox, physical phones, screen-reader audit.
- In-game reproduction of every documented operation. Instructions were checked against current source, not accepted as new game test results.
- New user-supplied screenshots: awaiting images listed in `wiki/IMAGE_REQUESTS.md`.

No MOD JAR build is needed for these documentation-only changes.
