package cn.wpgd.app.session

import cn.wpgd.app.core.rules.RuleProfile
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.core.state.SplitMix64
import cn.wpgd.app.session.client.ClientPhase
import cn.wpgd.app.session.diagnostics.AppVersion
import cn.wpgd.app.session.diagnostics.DiagnosticBuild
import cn.wpgd.app.session.diagnostics.DiagnosticField
import cn.wpgd.app.session.diagnostics.DiagnosticsReport
import cn.wpgd.app.session.diagnostics.ProfileRole
import cn.wpgd.app.session.diagnostics.SessionDiagnostics
import cn.wpgd.app.session.host.PauseReason
import cn.wpgd.app.session.wire.CloseReason
import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WI-12, P8, GDN-108: the local diagnostics export holds revisions, states and error counts, and no
 * hand, seed, capability, key, room id, member id, nickname or confirmation code.
 *
 * The export is built from an allow-list ([DiagnosticField]): each field has a fixed key and a value
 * shape (count, revision, flag, token from a fixed enum, profile id/version/hash) that cannot carry
 * free text. This test checks both halves: the secrets of a real table are absent from its export,
 * and nothing outside the allow-list can be written or passes the validator.
 */
class DiagnosticsRedactionTest {
    private class Played(val table: Table, val players: Map<Seat, Table.Peer>, val watcher: Table.Peer, val seeds: List<Long>)

    /** A real table: four admitted players, one admitted watcher, a dealt hand and some play. */
    private fun played(): Played {
        val seeds = ArrayList<Long>()
        val t = Table(seed = 4242, dealRandom = { s -> seeds += s; SplitMix64(s) })
        val players = t.fourPlayers()
        val watcher = t.observer("watcher-nick")
        t.startDeal()
        repeat(12) {
            val p = players.getValue(t.turn())
            t.command(p, simpleBody(p.client.memberView!!))
            t.until(5_000) { players.values.all { it.client.revision == t.host.revision } }
        }
        check(seeds.isNotEmpty()) { "the host dealt without drawing a seed" }
        return Played(t, players, watcher, seeds)
    }

    private fun export(p: Played, client: Table.Peer): String {
        val b = DiagnosticsReport.Builder()
        b.token(DiagnosticField.BUILD_MODE, DiagnosticBuild.DEVELOPMENT)
        b.profile(ProfileRole.BASE, TestProfiles.base)
        b.profile(ProfileRole.MATCH, TestProfiles.match)
        SessionDiagnostics.host(b, p.table.host)
        SessionDiagnostics.client(b, client.client)
        return b.build().render()
    }

    /** Words of the export: runs of letters, digits, `_` and `-` (card ids, codes and ids are such runs). */
    private fun words(text: String): Set<String> = Regex("[A-Za-z0-9_-]+").findAll(text).map { it.value }.toSet()

    @Test fun exportOfARealTableHoldsNoHandSeedCapabilityOrIdentity() {
        val p = played()
        val host = p.table.host
        val hands = host.state.deal!!.hands.values.flatten().toSet()
        assertTrue(hands.size > 50, "the deal must still hold most cards: ${hands.size}")
        val members = p.players.values + p.watcher
        val capabilities = members.map { checkNotNull(it.client.capability) }
        val memberIds = members.map { checkNotNull(it.client.memberId) }
        val codes = members.map { checkNotNull(it.client.confirmationCode) }
        val nicknames = members.map { it.client.config.nickname }
        val longSecrets = buildList {
            p.seeds.forEach { add(it.toString()); add(it.toULong().toString()); add(java.lang.Long.toHexString(it)) }
            addAll(capabilities)
            add(host.roomId)
        }
        longSecrets.forEach { assertTrue(it.length >= 6, "secret '$it' too short for a substring check") }

        for (peer in members) {
            val text = export(p, peer)
            for (s in longSecrets) assertFalse(text.contains(s), "export contains a secret: $s\n$text")
            val w = words(text)
            val leakedCards = w intersect hands
            assertTrue(leakedCards.isEmpty(), "export names cards $leakedCards\n$text")
            val leakedCodes = w intersect codes.toSet()
            assertTrue(leakedCodes.isEmpty(), "export holds a confirmation code $leakedCodes\n$text")
            val leakedIds = w intersect memberIds.toSet()
            assertTrue(leakedIds.isEmpty(), "export holds a member id $leakedIds\n$text")
            val leakedNames = w intersect nicknames.toSet()
            assertTrue(leakedNames.isEmpty(), "export holds a nickname $leakedNames\n$text")
        }
    }

    @Test fun exportHoldsRevisionsStatesErrorsAndTheProfiles() {
        val p = played()
        val south = p.players.getValue(Seat.SOUTH)
        val text = export(p, south)
        val lines = text.lines().drop(1).filter { it.isNotEmpty() }.associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals(DiagnosticsReport.FORMAT.toString(), lines["format"])
        assertEquals(p.table.host.revision.toString(), lines["host.revision"])
        assertEquals(south.client.revision.toString(), lines["client.revision"])
        assertEquals("ACTIVE", lines["host.status"])
        assertEquals("PLAYING", lines["host.phase"])
        assertEquals("ADMITTED", lines["client.phase"])
        assertEquals(p.table.host.metrics.commits.toString(), lines["host.commits"])
        assertEquals(p.table.host.metrics.rejections.toString(), lines["host.rejections"])
        assertEquals(south.client.metrics.digestMismatches.toString(), lines["client.digest_mismatches"])
        for ((role, profile) in listOf("base" to TestProfiles.base, "match" to TestProfiles.match)) {
            assertEquals(profile.id, lines["profile.$role.id"])
            assertEquals(profile.version, lines["profile.$role.version"])
            assertEquals(profile.hash, lines["profile.$role.hash"])
            // P1/M1 are signed (G1a, G1b); the flag is still exported, so a provisional build would say so (plan section 3).
            assertEquals(profile.provisional.toString(), lines["profile.$role.provisional"])
            assertEquals("false", lines["profile.$role.provisional"])
        }
        assertEquals(PauseReason.entries.size, lines.keys.count { it.startsWith("host.paused.") })
        assertEquals(CloseReason.entries.size, lines.keys.count { it.startsWith("host.closed.") })
        assertEquals(emptyList(), DiagnosticsReport.validate(text))
    }

    @Test fun validatorAcceptsOnlyAllowListedKeysAndShapes() {
        val p = played()
        val good = export(p, p.players.getValue(Seat.SOUTH))
        assertEquals(emptyList(), DiagnosticsReport.validate(good))
        val capability = checkNotNull(p.players.getValue(Seat.SOUTH).client.capability)
        val bad = mapOf(
            "unknown key" to "host.room_id=${p.table.host.roomId}",
            "free text in a count" to "host.commits=12 seed",
            "negative count" to "host.commits=-3",
            "a capability in a count" to "host.commits=$capability",
            "token outside its vocabulary" to "host.status=SOUTH",
            "token of another field" to "client.phase=PLAYING",
            "short hash" to "profile.base.hash=abc123",
            "capability as a hash" to "profile.base.hash=$capability",
            "unknown keyed member" to "host.paused.SEED=true",
            "flag not a flag" to "profile.base.provisional=yes",
            "no equals sign" to "host.commits",
            "duplicate key" to "host.commits=1",
        )
        for ((why, line) in bad) {
            // Replace the line with the same key, if any, so that only the value under test is wrong.
            val key = line.substringBefore('=')
            val lines = good.trimEnd().lines()
            val text = (if (why != "duplicate key" && lines.any { it.substringBefore('=') == key }) lines.map { if (it.substringBefore('=') == key) line else it } else lines + line)
                .joinToString("\n", postfix = "\n")
            assertTrue(DiagnosticsReport.validate(text).isNotEmpty(), "validator accepted $why: $line")
        }
        assertTrue(DiagnosticsReport.validate("not-the-header\n").isNotEmpty(), "validator accepted a wrong header")
        assertTrue(DiagnosticsReport.validate("${DiagnosticsReport.HEADER}\n").isNotEmpty(), "validator accepted a header-only (truncated) export")
    }

    @Test fun builderRefusesValuesOffTheAllowList() {
        val b = DiagnosticsReport.Builder()
        assertFailsWith<IllegalArgumentException> { b.token(DiagnosticField.HOST_STATUS, ClientPhase.ADMITTED) }
        assertFailsWith<IllegalArgumentException> { b.token(DiagnosticField.HOST_COMMITS, ClientPhase.ADMITTED) }
        assertFailsWith<IllegalArgumentException> { b.count(DiagnosticField.HOST_COMMITS, -1) }
        assertFailsWith<IllegalArgumentException> { b.count(DiagnosticField.HOST_CLOSED, CloseReason.REPLACED, -1) }
        assertFailsWith<IllegalArgumentException> { b.count(DiagnosticField.HOST_STATUS, 1) }
        assertFailsWith<IllegalArgumentException> { b.revision(DiagnosticField.HOST_COMMITS, 1) }
        assertFailsWith<IllegalArgumentException> { b.count(DiagnosticField.HOST_PAUSED, PauseReason.HOST, 1) }
        assertFailsWith<IllegalArgumentException> { b.flag(DiagnosticField.HOST_PAUSED, ClientPhase.ADMITTED, true) }
        b.count(DiagnosticField.HOST_COMMITS, 1)
        assertFailsWith<IllegalArgumentException> { b.count(DiagnosticField.HOST_COMMITS, 2) }
    }

    /**
     * The allow-list is the type system: no public builder method takes text, bytes, a collection or
     * an arbitrary object, so a secret cannot be passed in by mistake. A new method that does fails here.
     */
    @Test fun builderTakesNoFreeTextOrBytes() {
        val allowed = setOf(
            DiagnosticField::class.java, ProfileRole::class.java, RuleProfile::class.java, AppVersion::class.java,
            java.lang.Long.TYPE, java.lang.Boolean.TYPE, Enum::class.java,
        )
        val methods = DiagnosticsReport.Builder::class.java.declaredMethods.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
        assertTrue(methods.map { it.name }.containsAll(listOf("count", "revision", "flag", "token", "profile", "build")), methods.map { it.name }.toString())
        for (m in methods) for (t in m.parameterTypes) {
            assertTrue(t in allowed, "Builder.${m.name} takes a ${t.name}: only fields, numbers, flags, enum tokens and rule profiles may enter diagnostics")
        }
    }

    /** Local only: the diagnostics code has no I/O or network access; it returns text to its caller. */
    @Test fun diagnosticsCodeOpensNoFileOrSocket() {
        val dir = File("src/main/kotlin/cn/wpgd/app/session/diagnostics")
        val sources = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty(), "no diagnostics sources found under ${dir.absolutePath}")
        val forbidden = Regex("""\bimport\s+(java\.net|java\.nio|java\.io|javax\.net|okhttp3|android\.)""")
        for (f in sources) {
            val hit = forbidden.find(f.readText())
            assertTrue(hit == null, "${f.name} imports ${hit?.value}: diagnostics must stay a pure in-memory export")
        }
    }
}
