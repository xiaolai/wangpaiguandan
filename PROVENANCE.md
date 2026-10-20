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

| Material | Where | Origin |
| --- | --- | --- |
| Design package: 8 HTML/JavaScript/Markdown files | `design/original/` | Supplied by the design team; each file's hash is pinned in [`design/import-manifest.json`](design/import-manifest.json) |
| Baseline screenshots: 10 PNGs | `design/original/uploads/` | Supplied separately with the design package; hashes in [`design/baseline-assets-manifest.json`](design/baseline-assets-manifest.json) |
| Design tokens, screen inventory, table-state inventory | `design/*.json` | Extracted from the supplied HTML without executing its scripts |
| Platform icons: 7 SVGs | `design/original/icons/` | Supplied later by the design team; hashes pinned in `design/import-manifest.json` |
| Launcher resources | `app/src/main/res/drawable*/ic_launcher*` | Converted to vector XML, or rendered at each density, from those icons |

The distribution terms of this material are **not recorded**. The project owner
marks it `OWNER-DECISION` in `release/license-inventory.json`, and
`release/PROJECT-LICENSE.md` states the same. Recording those terms is a
prerequisite for publishing the material.

The seven icon files arrived with an embedded provenance-metadata block. It was
removed from the stored copies and the artwork is unchanged: every file renders
pixel-identically before and after at 432 px. `design/import-manifest.json` keeps
both hashes — `source_sha256` as supplied, `sha256` as stored.

## Third-party documents — rules research

Kept because the signed rule profiles cite them ([`rules/base-v1.md`](rules/base-v1.md)
Appendix B, [`rules/match-v1.md`](rules/match-v1.md)). These are other people's
publications. **No redistribution licence is recorded for any of them**, so
whether they may be published is a separate decision from whether the code may
be.

| Key | File(s) | Kind and issuer | Source |
| --- | --- | --- | --- |
| JS17 | `js-simple-rules-2017.doc`, `.notice.html` | Official provincial text, 江苏省社会体育管理中心, 2017 | [archived original](https://web.archive.org/web/20181105052446id_/http://www.jssports.gov.cn/module/download/downfile.jsp?classid=0&filename=1704061633399213374.doc), [notice page](https://web.archive.org/web/20190411113926id_/http://www.jssports.gov.cn/art/2017/4/6/art_3367_117945.html) |
| HA17 | `huaian-national-rules-2017-repost.html` | Secondary repost of the 2017 national text | <http://cnmzppw.com/tv/20220301153936.html> |
| CG22a | `competitive-rules-2022-trial-repost-a.html` | Secondary repost of the 2022 trial rules | <https://www.sohu.com/a/713406311_121124665> |
| CG22b | `competitive-rules-2022-trial-repost-b.html` | Second, independent repost | <https://www.sohu.com/a/621481154_121124679> |
| CG22 (Gansu) | `competitive-rules-2022-trial-gansu-excerpt.md` | Abridged official text, reposted | <http://gansuqiyuan.com/show/id/1320.html> |
| WG25 | `world-rules-2025.pdf` | Primary PDF of a body whose standing was not verified | `https://gawsf.b-cdn.net/files/` + URL-encoded name; full URL in `world-rules-2025.md` |
| CUP | `cup-event-rules-2024.pdf` | China University of Petroleum staff event rules, 2024 | <https://www.cup.edu.cn/jiguan/docs/2024-03/a37a68b7f82c405f94a214c2becf7cc3.pdf> |
| SEU | `seu-event-rules.pdf` | Southeast University competition rules | [PDF](https://ddgh.seu.edu.cn/_upload/article/files/44/c8/f455e1d04d2a998e40454931740a/4f853bb4-29b9-45dc-9c56-7627ed4c9726.pdf) |
| NJMU | `nanjing-medical-univ-rules.doc` | Nanjing Medical University staff event rules | [DOC](https://gonghui.njmu.edu.cn/_upload/article/files/5d/c8/858326da4e149f73e21ef98e9356/10310dd7-085d-4b97-abf2-b75ed3462411.doc) |
| YZU | `yangzhou-univ-rules.doc` | Yangzhou University event rules | **Origin not confirmed**: the URL found by search serves a different file |
| PD17 | `people-daily-2017-04-15.html` | News report, 人民网 | <http://qipai.people.com.cn/GB/n1/2017/0415/c22160-29213186.html> |

`rules/sources/text/` holds plain-text extractions of the same documents; the
line numbers cited by the rule text refer to those files. Each document's own
sidecar `.md` records the retrieval date, the hash and how it was obtained.

## Reference analysis of another project

`lanticy/guandan` (<https://codeberg.org/lanticy/guandan>) was read and audited
as a reference. No source from it has been copied. That audit and the derived
feature ledger and implementation plan live in the **sibling `dev-docs`
repository** under `reference/`, which is not part of this repository.
