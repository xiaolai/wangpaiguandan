# Rule sources

The documents this project's rule profiles were written against are **not in
this repository**. Their originals — PDFs, Word files and HTML reposts — were
removed before publication, because they are other people's publications and no
redistribution licence is recorded for them. `.gitignore` keeps them out if they
are copied back in.

What remains here:

- `<key>.md` — one sidecar per document: title, issuer, kind, retrieval date,
  source URL and the SHA-256 of the file that was read. This is where to find a
  document again, and how to prove which copy was used.
- `text/<KEY>.txt` — plain-text extractions. The line numbers cited by
  `rules/base-v1.md` Appendix A and read by `tools/golden/audit.py` and
  `MatchSpecTest` refer to these files.

`rules/base-v1.md` Appendix B is the register: key, file name, hash and URL.
