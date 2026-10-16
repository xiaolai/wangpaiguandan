# Debug-only automation hook

The end-to-end driver (`tools/e2e/`, `app/src/androidTestDebug/.../e2e/E2eAgentTest.kt`) reads the
six-digit codes and the room port through `cn.wpgd.app.AutomationHook`. The real hook,
`app/src/debug/kotlin/cn/wpgd/app/automation/E2eStatusHook.kt`, exists only in the `debug` source
set; `src/release` and `src/r8check` bind `AutomationHook.None`, so those builds never compile it.
It has no manifest component, opens no socket, writes no file and logs nothing; the driver keeps
its status in app-private storage. `tools/ci.sh` runs `tools/check_automation_hook.py`: it must
find the hook in the debug APK and compiled classes (self-test) and must not find it in the
release or r8check APK, their compiled classes (before R8), their R8 mapping files or their merged
manifests.

Why four inputs, not only the APK: R8 deletes code nothing in the app calls and renames the rest,
so the APK's strings alone miss a hook that is compiled into a release build. Mutation run
(2026-10-16): with the hook class moved into `src/main` (release still bound to `None`), both APKs
scanned clean and the compiled-classes scan failed the check; with release and r8check also bound
to the hook, both APKs still scanned clean (R8 removed it from release entirely), and the
compiled-classes scan and the r8check mapping file failed the check. Both mutations were reverted.

## Threat model

1. Who can reach it: only code inside the app's process, which outside the app means an instrumentation started over ADB (`am instrument` needs the shell user and the test APK signed with the same key).
2. What a debug build exposes through it: the pending confirmation codes and the port, both already on the host's screen; anyone with ADB to that phone could read the screen anyway.
3. What it cannot do: approve anyone, skip the code comparison, or weaken G2; approval still runs through `SessionHost.approve`, which checks the code.
4. Why release cannot: release and r8check bind `AutomationHook.None` at compile time, so the hook class is never compiled into them, and a release build is not debuggable, so `run-as` cannot read app files.
5. What would break this: moving the hook or its binding into `src/main` or a release source set; the CI check fails in both cases (mutation run above).
