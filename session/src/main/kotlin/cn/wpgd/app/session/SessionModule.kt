package cn.wpgd.app.session

/**
 * Transport-independent session protocol (WI-05): handshake and admission, framing, sealed
 * channel, idempotent commands and receipts, per-member projections, observer grants. Entry
 * points: [cn.wpgd.app.session.host.SessionHost] and [cn.wpgd.app.session.client.SessionClient].
 */
object SessionModule {
    const val NAME: String = "session"
}
