package org.koitharu.kotatsu.reader.ui

import android.content.res.Resources
import android.os.SystemClock
import android.view.MotionEvent
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.util.ext.resolveDp
import kotlin.math.roundToLong

private const val MAX_SWITCH_DELAY = 10_000L
private const val MIN_SWITCH_DELAY = 300L
private const val INTERACTION_SKIP_MS = 2_000L
private const val TICK_DELAY_MS = 8L
private const val BASE_SCROLL_DELAY_MS = 32f
private const val SPEED_FACTOR_CHANGE_PER_SEC = 0.65f
private const val HOLD_THRESHOLD_MS = 250L

class ScrollTimer @AssistedInject constructor(
	@Assisted resources: Resources,
	@Assisted private val listener: ReaderControlDelegate.OnInteractionListener,
	@Assisted lifecycleOwner: LifecycleOwner,
	settings: AppSettings,
) {

	private val coroutineScope = lifecycleOwner.lifecycleScope
	private var job: Job? = null
	private var scrollSpeedMultiplier: Float = MIN_MULTIPLIER
	var pageSwitchDelay: Long = 100L
		private set
	private var resumeAt = 0L
	private var isTouchDown = MutableStateFlow(false)
	private val isRunning = MutableStateFlow(false)
	private val isBoost = MutableStateFlow(false)
	private val scrollDelta = resources.resolveDp(1)
	private val baseScrollSpeedPxPerSec = (scrollDelta * 1000f) / BASE_SCROLL_DELAY_MS
	private val touchSlopPx = resources.resolveDp(8).toFloat()

	// "Hold to speed up" (opt-in). While the finger is held still past HOLD_THRESHOLD_MS the
	// autoscroll speed is multiplied by boostMultiplier instead of pausing. Updated from settings.
	@Volatile
	private var holdBoostEnabled = false
	@Volatile
	private var boostMultiplier = AppSettings.AUTOSCROLL_BOOST_DEFAULT
	@Volatile
	private var downAtMs = 0L
	private var downX = 0f
	private var downY = 0f
	@Volatile
	private var movedBeyondSlop = false

	val isActive: StateFlow<Boolean>
		get() = isRunning

	val isBoosting: StateFlow<Boolean>
		get() = isBoost

	val boostMultiplierValue: Float
		get() = boostMultiplier

	init {
		// Seed synchronously: the flow below delivers on another dispatcher, and until it does the
		// multiplier stays at its minimum — restored controls re-arm the timer during layout before
		// any flow emission. Mirrors the page-delay seeding contract.
		onSpeedChanged(settings.readerAutoscrollSpeed)
		settings.observeAsFlow(AppSettings.KEY_READER_AUTOSCROLL_SPEED) {
			readerAutoscrollSpeed
		}.flowOn(Dispatchers.Default)
			.onEach {
				onSpeedChanged(it)
			}.launchIn(coroutineScope)
		settings.observeAsFlow(AppSettings.KEY_READER_AUTOSCROLL_HOLD) {
			isReaderAutoscrollHoldBoostEnabled
		}.flowOn(Dispatchers.Default)
			.onEach {
				holdBoostEnabled = it
			}.launchIn(coroutineScope)
		settings.observeAsFlow(AppSettings.KEY_READER_AUTOSCROLL_BOOST) {
			readerAutoscrollBoostMultiplier
		}.flowOn(Dispatchers.Default)
			.onEach {
				boostMultiplier = it
			}.launchIn(coroutineScope)
	}

	fun setActive(value: Boolean) {
		if (isRunning.value != value) {
			isRunning.value = value
			restartJob()
		}
	}

	fun onUserInteraction() {
		resumeAt = SystemClock.elapsedRealtime() + INTERACTION_SKIP_MS
	}

	fun onTouchEvent(event: MotionEvent) {
		when (event.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				isTouchDown.value = true
				downAtMs = SystemClock.elapsedRealtime()
				downX = event.x
				downY = event.y
				movedBeyondSlop = false
			}

			// A second pointer (pinch/zoom) is not a hold: cancel any pending boost.
			MotionEvent.ACTION_POINTER_DOWN -> {
				movedBeyondSlop = true
			}

			MotionEvent.ACTION_MOVE -> {
				if (!movedBeyondSlop) {
					val dx = event.x - downX
					val dy = event.y - downY
					if (dx * dx + dy * dy > touchSlopPx * touchSlopPx) {
						movedBeyondSlop = true
					}
				}
			}

			MotionEvent.ACTION_UP,
			MotionEvent.ACTION_CANCEL -> {
				isTouchDown.value = false
				movedBeyondSlop = false
			}
		}
	}

	// A boost engages only when enabled and the finger is held still past the threshold; any drag
	// (movedBeyondSlop) or pinch keeps the existing pause behaviour instead.
	private fun isBoostActive(): Boolean {
		return holdBoostEnabled &&
			isTouchDown.value &&
			!movedBeyondSlop &&
			SystemClock.elapsedRealtime() - downAtMs >= HOLD_THRESHOLD_MS
	}

	private fun onSpeedChanged(speed: Float) {
		scrollSpeedMultiplier = speedToMultiplier(speed)
		pageSwitchDelay = (MAX_SWITCH_DELAY / scrollSpeedMultiplier)
			.roundToLong()
			.coerceIn(MIN_SWITCH_DELAY, MAX_SWITCH_DELAY)
	}

	private fun restartJob() {
		job?.cancel()
		resumeAt = 0L
		isBoost.value = false
		if (!isRunning.value) {
			job = null
			return
		}
		job = coroutineScope.launch {
			var chapterSwitchAccumulator = 0L
			var scrollAccumulator = 0f
			var speedFactor = 1f
			val speedStepPerTick = SPEED_FACTOR_CHANGE_PER_SEC * (TICK_DELAY_MS / 1000f)
			while (isActive) {
				val boosting = isBoostActive()
				isBoost.value = boosting
				// While boosting, a held finger no longer pauses: ramp the factor back up and let the
				// boost multiplier apply below.
				if (!boosting && isPaused()) {
					speedFactor = (speedFactor - speedStepPerTick).coerceAtLeast(0f)
				} else if (speedFactor < 1f) {
					speedFactor = (speedFactor + speedStepPerTick).coerceAtMost(1f)
				}
				if (speedFactor == 0f) {
					delayUntilResumed()
					continue
				}
				delay(TICK_DELAY_MS)
				if (!listener.isReaderResumed()) {
					continue
				}

				val boostFactor = if (boosting) boostMultiplier else 1f
				val effectiveMultiplier = scrollSpeedMultiplier * speedFactor * boostFactor
				scrollAccumulator += baseScrollSpeedPxPerSec * effectiveMultiplier * (TICK_DELAY_MS / 1000f)
				val scrollByPx = scrollAccumulator.toInt()
				if (scrollByPx <= 0) {
					continue
				}
				scrollAccumulator -= scrollByPx

				if (!listener.scrollBy(scrollByPx, false)) {
					chapterSwitchAccumulator += TICK_DELAY_MS
					if (chapterSwitchAccumulator >= pageSwitchDelay) {
						listener.switchPageBy(1)
						chapterSwitchAccumulator = 0L
					}
				} else {
					chapterSwitchAccumulator = 0L
				}
			}
		}
	}

	private fun isPaused(): Boolean {
		return isTouchDown.value || resumeAt > SystemClock.elapsedRealtime()
	}

	private suspend fun delayUntilResumed() {
		while (isPaused()) {
			val delayTime = resumeAt - SystemClock.elapsedRealtime()
			if (delayTime > 0) {
				delay(delayTime)
			} else {
				yield()
			}
			isTouchDown.first { !it }
		}
	}

	@AssistedFactory
	interface Factory {

		fun create(
			resources: Resources,
			lifecycleOwner: LifecycleOwner,
			listener: ReaderControlDelegate.OnInteractionListener,
		): ScrollTimer
	}

	companion object {

		private const val MIN_MULTIPLIER = 0.1f
		private const val MAX_MULTIPLIER = 10.0f

		fun speedToMultiplier(speed: Float): Float {
			return (MIN_MULTIPLIER + (MAX_MULTIPLIER - MIN_MULTIPLIER) * speed.coerceIn(0f, 1f))
				.coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
		}
	}
}
