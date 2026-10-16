package cn.wpgd.app

import cn.wpgd.app.automation.E2eStatusHook

/** Debug builds only: the end-to-end driver's hook (see [AutomationHook]). */
internal val automationHook: AutomationHook = E2eStatusHook
