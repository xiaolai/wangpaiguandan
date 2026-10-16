package cn.wpgd.app

/**
 * The seam a local, ADB-driven end-to-end driver (instrumentation in the same process) uses to read
 * what a tester reads off the screens, chiefly the six-digit confirmation codes. Main code never
 * knows an implementation: each build type binds one in its own source set ([automationHook]).
 * `src/debug` binds the real hook; `src/release` and `src/r8check` bind [None], so those builds do
 * not even compile the hook (tools/check_automation_hook.py checks the APKs). Threat model:
 * dev-docs/automation-hook.md.
 */
interface AutomationHook {
    /** A one-line JSON status of the running table for the driver; null in builds without a hook. */
    fun status(graph: AppGraph): String? = null

    /** The binding of release and r8check builds: no hook. */
    object None : AutomationHook
}
