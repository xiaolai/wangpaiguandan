# Initial architecture boundaries

Status: bootstrap, 2026-10-14. Modules implemented: `:app`, `:core`.

`:app` owns Android lifecycle, native Compose presentation, accessibility semantics, design-token usage and component previews. It must not become the authority for legal moves, another seat's private data, observer grants or network-driven mutation.

`:core` contains immutable card identities and fixture data. It contains no Android imports, transport code, UI state, device IDs, Java UUID dependencies or inherited upstream game implementation. The deterministic rule reducer, complete legal-action generator, profile specification and seat-specific observations will be added after their contracts are reviewed.

Future session boundaries: one host authority per session epoch; commands and receipts tied to turn/revision/capability; per-member private projections; ordered bounded framing; atomic versioned checkpoints; seat-private AI observation. BLE and local Wi-Fi are adapters above the same contract. Spectators are independent read-only members, not extra playing seats. Nearby sessions have no mandatory cloud account or Google Play services dependency.

An iPhone client is planned but no SwiftUI/KMP choice is finalized by this bootstrap. The shared-core option and native-client parity must be proven with common fixtures; HTML exports are not embedded as the mobile product.

The files in `dev-docs/reference/` are historical audit/target documents from 2026-10-13. Their proposed work items and commands are not current implementation status. Some reference paths describe the earlier local research environment. Use this document and README for the actual initialized module/build structure; review the ledger and design decisions before creating the next implementation plan.
