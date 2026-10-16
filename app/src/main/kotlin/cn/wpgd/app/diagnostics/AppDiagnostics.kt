package cn.wpgd.app.diagnostics

import cn.wpgd.app.owner.SessionOwner
import cn.wpgd.app.release.BuildMode
import cn.wpgd.app.session.diagnostics.AppVersion
import cn.wpgd.app.session.diagnostics.DiagnosticBuild
import cn.wpgd.app.session.diagnostics.DiagnosticField
import cn.wpgd.app.session.diagnostics.DiagnosticsReport
import cn.wpgd.app.session.diagnostics.FailureKind
import cn.wpgd.app.session.diagnostics.ProfileRole
import cn.wpgd.app.session.diagnostics.TableKindToken
import cn.wpgd.app.table.RuleProfiles
import java.io.IOException

/**
 * WI-12 local diagnostics (GDN-108, P8): the text the settings screen shows when the user asks for
 * it. Opt-in and local only: built on request, kept nowhere, sent nowhere (no permission is needed
 * and none is added). Every line comes from the `:session` allow-list ([DiagnosticField]): build
 * mode, version code and version name, both rule profiles' id, version, hash and provisional flag (plan section 3
 * requires them in a development build's diagnostics), the crash count, the owner's counters and the
 * open table's revisions and counters. Never a card, seed, capability, key, id or nickname.
 */
class AppDiagnostics(
    private val mode: BuildMode,
    private val versionCode: Int,
    versionName: String,
    private val profiles: () -> RuleProfiles,
    private val crashes: CrashCounter,
) {
    /** Fails fast: a build whose version name is not MAJOR.MINOR.PATCH cannot build diagnostics. */
    private val version: AppVersion = AppVersion.parse(versionName)

    fun export(owner: SessionOwner?): String {
        owner?.diagnostics(::common)?.let { return it }
        // No open owner, or its loop did not answer: export without the session lines.
        val b = DiagnosticsReport.Builder()
        common(b)
        b.token(DiagnosticField.OWNER_TABLE, TableKindToken.NONE)
        if (owner != null && !owner.terminated) {
            b.flag(DiagnosticField.OWNER_LOOP_RESPONSIVE, false)
            owner.writeCounters(b)
        }
        return b.build().render()
    }

    private fun common(b: DiagnosticsReport.Builder) {
        b.token(DiagnosticField.BUILD_MODE, if (mode == BuildMode.RELEASE) DiagnosticBuild.RELEASE else DiagnosticBuild.DEVELOPMENT)
        b.count(DiagnosticField.APP_VERSION_CODE, versionCode.toLong())
        b.appVersion(DiagnosticField.APP_VERSION_NAME, version)
        val p = profiles()
        b.profile(ProfileRole.BASE, p.base)
        b.profile(ProfileRole.MATCH, p.match)
        b.count(DiagnosticField.PROCESS_CRASHES, crashes.count())
    }
}

/** The kind of a failure, for diagnostics; the message is never used (it can name cards or ids). */
fun failureKind(t: Throwable): FailureKind = when (t) {
    is OutOfMemoryError -> FailureKind.OUT_OF_MEMORY
    is IOException -> FailureKind.IO
    is IllegalStateException -> FailureKind.ILLEGAL_STATE
    is IllegalArgumentException -> FailureKind.ILLEGAL_ARGUMENT
    else -> FailureKind.OTHER
}
