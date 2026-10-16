/// GuandanRules: PLACEHOLDER for the native Swift rules core.
///
/// Future home of rule profiles, the combination classifier, the legal action
/// generator and the deal/match reducers, ported from the Kotlin `:core` module.
/// The port is WI-11 work and has NOT started; this target contains no game logic.
public enum GuandanRules {}

/// The identifying header shared by every rule profile JSON file
/// (`rules/profiles/*.json` and the profile test fixtures).
///
/// Only the header is modelled; the `options` map and the rest of the profile
/// belong to the WI-11 port. Unknown keys are ignored by `Decodable`.
public struct RuleProfileHeader: Decodable, Equatable, Sendable {
    public let id: String
    public let version: String
    public let displayName: String
    public let provisional: Bool

    public init(id: String, version: String, displayName: String, provisional: Bool) {
        self.id = id
        self.version = version
        self.displayName = displayName
        self.provisional = provisional
    }
}
