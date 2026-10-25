package org.koitharu.kotatsu.core.os

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object TvLauncherSettings {
    fun apply(context: Context, enabled: Boolean) {
        val component = ComponentName(context, "org.koitharu.kotatsu.main.ui.TvLauncher")
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (context.packageManager.getComponentEnabledSetting(component) != state) {
            context.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        }
    }
}
