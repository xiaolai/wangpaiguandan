# Provenance

Where each part of this repository came from, and what is known about its terms.
The machine-readable record is [`release/license-inventory.json`](release/license-inventory.json);
the source register with sizes, URLs and SHA-256 hashes is Appendix B of
[`rules/base-v1.md`](rules/base-v1.md).

## This project's own work — MIT

Application code, the first-party `:core` and `:session` modules, the rule
profiles, the rule text under `rules/`, the tests and the tooling. See
[`LICENSE`](LICENSE) and [`release/PROJECT-LICENSE.md`](release/PROJECT-LICENSE.md).

## Supplied design material — terms not recorded

The supplied design package is **not in this repository**. It was removed before
publication, together with the seven icon source files. What remains is what the
build uses and what records the provenance.

| Material | Where | Origin |
| --- | --- | --- |
| Design package: 8 HTML/JavaScript/Markdown files | **not shipped**; recorded in [`design/import-manifest.json`](design/import-manifest.json) | Supplied by the design team; each file's SHA-256 is recorded |
| Baseline screenshots: 10 PNGs | **not shipped**; recorded in [`design/baseline-assets-manifest.json`](design/baseline-assets-manifest.json) | Supplied separately with the design package |
| Platform icons: 7 SVGs | **not shipped**; recorded in `design/import-manifest.json` under `later_imports` | Supplied later by the design team |
| Design tokens, screen inventory, table-state inventory | `design/*.json` | Extracted from the supplied HTML without executing its scripts |
| Launcher resources | `app/src/main/res/drawable*/ic_launcher*` | Converted to vector XML, or rendered at each density, from those icons |

The distribution terms of this material are **not recorded**. The project owner
marks it `OWNER-DECISION` in `release/license-inventory.json`, and
`release/PROJECT-LICENSE.md` states the same.

The seven icon files arrived with an embedded provenance-metadata block. It was
removed from the copies before they were archived and the artwork is unchanged:
every file rendered pixel-identically before and after at 432 px.
`design/import-manifest.json` keeps both hashes — `source_sha256` as supplied,
`sha256` as stored.

## Third-party documents — rules research

The documents themselves are **not in this repository**: the PDFs, Word files
and HTML reposts were removed before publication. What remains is the project's
own rule text, which cites them, and the records needed to find each copy again.

| Kept here | What it is |
| --- | --- |
| `rules/sources/<key>.md` | One sidecar per document: title, issuer, kind, retrieval date, source URL and the SHA-256 of the file that was read |
| `rules/sources/text/<KEY>.txt` | Plain-text extractions; the line numbers cited by `rules/base-v1.md` refer to these, and `tools/golden/audit.py` and `MatchSpecTest` read them |
| `rules/base-v1.md` Appendix B | The register: key, file name, hash and URL |
| `rules/*.md`, `rules/profiles/` | This project's own rule synthesis and profiles (MIT) |

These are other people's publications. **No redistribution licence is recorded
for any of them**, which is why the originals are not shipped. The sources, with
the link for each, are listed in the table below for retrieval.

| Key | Records here | Kind and issuer | Source |
| --- | --- | --- | --- |
| JS17 | `js-simple-rules-2017.md`, `text/JS17.txt` | Official provincial text, 江苏省社会体育管理中心, 2017 | [archived original](https://web.archive.org/web/20181105052446id_/http://www.jssports.gov.cn/module/download/downfile.jsp?classid=0&filename=1704061633399213374.doc), [notice page](https://web.archive.org/web/20190411113926id_/http://www.jssports.gov.cn/art/2017/4/6/art_3367_117945.html) |
| HA17 | `huaian-national-rules-2017-repost.md`, `text/HA17.txt` | Secondary repost of the 2017 national text | <http://cnmzppw.com/tv/20220301153936.html> |
| CG22a | `competitive-rules-2022-trial-repost.md`, `text/CG22a.txt` | Secondary repost of the 2022 trial rules | <https://www.sohu.com/a/713406311_121124665> |
| CG22b | `competitive-rules-2022-trial-repost.md`, `text/CG22b.txt` | Second, independent repost | <https://www.sohu.com/a/621481154_121124679> |
| CG22 (Gansu) | `competitive-rules-2022-trial-gansu-excerpt.md` | Abridged official text, reposted | <http://gansuqiyuan.com/show/id/1320.html> |
| WG25 | `world-rules-2025.md`, `text/WG25.txt` | Primary PDF of a body whose standing was not verified | `https://gawsf.b-cdn.net/files/` + URL-encoded name; full URL in the sidecar |
| CUP | `g1a-clause-check.md`, `text/CUP.txt` | China University of Petroleum staff event rules, 2024 | <https://www.cup.edu.cn/jiguan/docs/2024-03/a37a68b7f82c405f94a214c2becf7cc3.pdf> |
| SEU | `g1a-clause-check.md`, `text/SEU.txt` | Southeast University competition rules | [PDF](https://ddgh.seu.edu.cn/_upload/article/files/44/c8/f455e1d04d2a998e40454931740a/4f853bb4-29b9-45dc-9c56-7627ed4c9726.pdf) |
| NJMU | `g1a-clause-check.md`, `text/NJMU.txt` | Nanjing Medical University staff event rules | [DOC](https://gonghui.njmu.edu.cn/_upload/article/files/5d/c8/858326da4e149f73e21ef98e9356/10310dd7-085d-4b97-abf2-b75ed3462411.doc) |
| YZU | `g1a-clause-check.md`, `text/YZU.txt` | Yangzhou University event rules | **Origin not confirmed**: the URL found by search serves a different file |
| PD17 | `people-daily-2017-04-15.md`, `text/PD17.txt` | News report, 人民网 | <http://qipai.people.com.cn/GB/n1/2017/0415/c22160-29213186.html> |

`rules/sources/text/` holds plain-text extractions of the same documents; the
line numbers cited by the rule text refer to those files. Each document's own
sidecar `.md` records the retrieval date, the hash and how it was obtained.

## Reference analysis of another project

`lanticy/guandan` (<https://codeberg.org/lanticy/guandan>) was read and audited
as a reference. No source from it has been copied. That audit and the derived
feature ledger and implementation plan live in the **sibling `dev-docs`
repository** under `reference/`, which is not part of this repository.
