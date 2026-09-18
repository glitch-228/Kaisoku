package org.koitharu.kotatsu.core.image

import android.view.View
import android.view.ViewGroup.LayoutParams
import androidx.test.platform.app.InstrumentationRegistry
import coil3.size.Dimension
import coil3.size.Size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

class WeakViewSizeResolverTest {

	@Test fun keepsMeasuredPaddingAndWrapContentSizing() = runBlocking {
		withContext(Dispatchers.Main) {
			val view = newView()
			view.layoutParams = LayoutParams(100, 80)
			view.setPadding(5, 3, 7, 9)
			val resolver = WeakViewSizeResolver(view)
			assertEquals(Size(88, 68), resolver.size())
			view.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, 80)
			assertEquals(Size(Dimension.Undefined, Dimension(68)), resolver.size())
		}
	}

	@Test fun waitsForLayoutAndSupportsCancellationAndRetry() = runBlocking {
		withContext(Dispatchers.Main) {
			val view = newView()
			view.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
			val resolver = WeakViewSizeResolver(view)
			val cancelled = async(start = CoroutineStart.UNDISPATCHED) { resolver.size() }
			assertFalse(cancelled.isCompleted)
			cancelled.cancelAndJoin()
			val retry = async(start = CoroutineStart.UNDISPATCHED) { resolver.size() }
			assertFalse(retry.isCompleted)
			view.layout(0, 0, 120, 90)
			view.viewTreeObserver.dispatchOnPreDraw()
			assertEquals(Size(120, 90), retry.await())
		}
	}

	@Test fun retainedResolverDoesNotRetainViewAfterSizing() = runBlocking {
		val queue = ReferenceQueue<View>()
		val (resolver, reference) = withContext(Dispatchers.Main) { measuredResolver(queue) }
		var collected = false
		repeat(20) {
			if (!collected) {
				Runtime.getRuntime().gc()
				System.runFinalization()
				collected = queue.remove(100) != null
			}
		}
		assertTrue("The retained request size resolver must not retain its view", collected)
		assertNull(reference.get())
		try {
			resolver.size()
			fail("A released view must cancel size resolution")
		} catch (_: CancellationException) {
			// Expected: requests cannot resolve dimensions for a collected view.
		}
	}

	private suspend fun measuredResolver(queue: ReferenceQueue<View>): Pair<WeakViewSizeResolver<View>, WeakReference<View>> {
		val view = newView()
		view.layoutParams = LayoutParams(100, 80)
		val resolver = WeakViewSizeResolver(view)
		assertEquals(Size(100, 80), resolver.size())
		return resolver to WeakReference(view, queue)
	}

	private fun newView() = View(InstrumentationRegistry.getInstrumentation().targetContext)
}
