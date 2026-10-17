package cn.wpgd.app.automation

import cn.wpgd.app.AppGraph
import cn.wpgd.app.AutomationHook

/**
 * DEBUG BUILDS ONLY (app/src/debug). The end-to-end driver's view of what a tester reads off the
 * screens: this device's own six-digit code while it waits for approval, the codes of joiners
 * waiting on this host (both are on screen in every build), and the port a LAN room listens on.
 *
 * Reached only through [AutomationHook] by code running in this process: the instrumentation driver
 * (`tools/e2e/`, started by `adb shell am instrument`). It opens no socket, registers no component,
 * writes no file and logs nothing; the caller decides where the line goes (the driver keeps it in
 * app-private storage). The marker [MARKER] lets tools/check_automation_hook.py prove that release
 * and r8check APKs do not contain this class.
 */
object E2eStatusHook : AutomationHook {
    const val MARKER = "wpgd-e2e-hook-v1"

    override fun status(graph: AppGraph): String {
        val t = graph.runningOwner()?.state?.value?.table
        val pending = t?.hostSide?.pending.orEmpty().joinToString(",", "[", "]") { p ->
            """{"connectionId":${p.connectionId},"code":${str(p.code)},"nickname":${str(p.nickname)},"role":${str(p.role.name)},"seat":${str(p.seat?.name)},"replace":${p.replace}}"""
        }
        return """{"hook":"$MARKER","tableId":${str(t?.tableId)},"kind":${str(t?.kind?.name)},"joinPhase":${str(t?.join?.phase?.name)},""" +
            """"joinCode":${str(t?.join?.code)},"pending":$pending,"listenPort":${t?.hostSide?.listenPort ?: "null"}}"""
    }

    /** A JSON string literal (or null). */
    private fun str(s: String?): String = if (s == null) "null" else buildString {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}
