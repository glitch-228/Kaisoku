package org.koitharu.kotatsu.main.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PrimaryFragmentChangeTest {

	@Test
	fun `commit callback reports the attached fragment, not the throwaway instance`() {
		val setPrimary = source("MainNavigationDelegate.kt")
			.substringAfter("privatefunsetPrimaryFragment(")
			.substringBefore("privatefunonNavigationItemReselected(")

		assertTrue(setPrimary.contains(".runOnCommit{primaryFragment?.let{onFragmentChanged(it,fromUser=true)}}"))
		assertFalse(setPrimary.contains("onFragmentChanged(fragment,"))
	}

	@Test
	fun `tracker search target must be attached`() {
		val changed = source("MainActivity.kt")
			.substringAfter("overridefunonFragmentChanged(")
			.substringBefore("adjustFabVisibility(")

		assertTrue(changed.contains("fragment.takeIf{it.isAdded}as?"))
	}

	private fun source(name: String): String {
		val relativePath = "org/koitharu/kotatsu/main/ui/$name"
		return (
			sequenceOf(
				File("src/main/kotlin", relativePath),
				File("app/src/main/kotlin", relativePath),
			).firstOrNull(File::isFile)?.readText()
				?: error("Cannot find production source: $relativePath")
			)
			.replace(Regex("""//[^\r\n]*"""), "")
			.replace(Regex("""\s+"""), "")
	}
}
