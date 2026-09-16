package org.koitharu.kotatsu

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

class HiltTestRunner : AndroidJUnitRunner() {

    override fun newApplication(cl: ClassLoader?, name: String?, context: Context?): Application {
        // The test application lives in the instrumentation APK. On Android 16 the
        // supplied loader may contain only the target APK's classes.
        return super.newApplication(HiltTestApplication::class.java.classLoader, HiltTestApplication::class.java.name, context)
    }
}
