package org.koitharu.kotatsu.settings

import androidx.fragment.app.FragmentFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.R
import org.xmlpull.v1.XmlPullParser

/** Read the packaged XML, not a manually maintained list that can miss new destinations. */
@RunWith(AndroidJUnit4::class)
class SettingsFragmentEntryPointsTest {
    @Test fun everyPreferenceDestinationHasAPublicConstructorAndCanBeInstantiated() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val names = linkedSetOf<String>()
        for (resource in R.xml::class.java.fields.filter { it.name.startsWith("pref_") }) {
            context.resources.getXml(resource.getInt(null)).use { xml ->
                while (xml.next() != XmlPullParser.END_DOCUMENT) {
                    if (xml.eventType == XmlPullParser.START_TAG) {
                        xml.getAttributeValue("http://schemas.android.com/apk/res/android", "fragment")?.let(names::add)
                    }
                }
            }
        }
        assertTrue(names.contains("org.koitharu.kotatsu.sync.extended.drive.ExtendedDriveSettingsFragment"))
        assertTrue(names.contains("org.koitharu.kotatsu.sync.extended.appserver.ServerExtendedSettingsFragment"))
        val factory = FragmentFactory()
        for (name in names) {
            val type = FragmentFactory.loadFragmentClass(context.classLoader, name)
            assertNotNull("Missing public empty constructor: $name", type.getConstructor())
            assertEquals(name, factory.instantiate(context.classLoader, name).javaClass.name)
        }
    }
}
