package org.koitharu.kotatsu.core.ui.widgets

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Parcel
import android.os.Parcelable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewGroup.MarginLayoutParams
import android.view.ViewPropertyAnimator
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.customview.view.AbsSavedState
import androidx.interpolator.view.animation.FastOutLinearInInterpolator
import androidx.interpolator.view.animation.LinearOutSlowInInterpolator
import com.google.android.material.bottomnavigation.BottomNavigationMenuView
import com.google.android.material.color.MaterialColors
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import org.koitharu.kotatsu.core.util.ext.applySystemAnimatorScale
import org.koitharu.kotatsu.core.util.ext.measureHeight
import kotlin.math.max
import com.google.android.material.R as materialR

private const val STATE_DOWN = 1
private const val STATE_UP = 2

private const val SLIDE_UP_ANIMATION_DURATION = 225L
private const val SLIDE_DOWN_ANIMATION_DURATION = 175L

private const val MAX_ITEM_COUNT = 6

/**
 * Travel needed to move a bottom navigation bar fully off-screen. An anchored bar only has to clear
 * its own height; a floating one also has to clear the bottom margin it sits above, otherwise the top
 * of the pill stays visible once it has been "hidden".
 */
internal fun calculateNavHideOffset(height: Int, bottomMargin: Int): Float =
	(height.coerceAtLeast(0) + bottomMargin.coerceAtLeast(0)).toFloat()

class SlidingBottomNavigationView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	@AttrRes defStyleAttr: Int = materialR.attr.bottomNavigationStyle,
	@StyleRes defStyleRes: Int = materialR.style.Widget_Design_BottomNavigationView,
) : NavigationBarView(context, attrs, defStyleAttr, defStyleRes),
	CoordinatorLayout.AttachedBehavior {

	private var currentAnimator: ViewPropertyAnimator? = null

	private var currentState = STATE_UP
	private var behavior = HideBottomNavigationOnScrollBehavior()

	/**
	 * Fill colour the bar had before it was turned into a floating pill, so the anchored look can be
	 * restored and so an external tint (AMOLED) survives a style change in either direction.
	 */
	private var backgroundTint: Int? = null
	private var anchoredBackground: Drawable? = null
	private var isApplyingFloatingBackground = false

	var isPinned: Boolean
		get() = behavior.isPinned
		set(value) {
			behavior.isPinned = value
			if (value) {
				translationX = 0f
			}
		}

	/** Detached, rounded presentation with margins around the bar instead of a full-width surface. */
	var isFloating: Boolean = false
		set(value) {
			if (field != value) {
				field = value
				applyFloatingBackground()
			}
		}

	/** Corner radius applied while [isFloating]; ignored otherwise. */
	var floatingCornerRadius: Float = 0f
		set(value) {
			if (field != value) {
				field = value
				if (isFloating) {
					applyFloatingBackground()
				}
			}
		}

	/**
	 * Distance the bar has to travel to be fully off-screen. A floating bar sits above its own bottom
	 * margin, so translating by [getHeight] alone would leave a sliver of the pill visible.
	 */
	val hideOffset: Float
		get() = calculateNavHideOffset(
			height = measureHeight(),
			bottomMargin = (layoutParams as? MarginLayoutParams)?.bottomMargin ?: 0,
		)

	val isShownOrShowing: Boolean
		get() = isVisible && currentState == STATE_UP

	override fun getBehavior(): CoordinatorLayout.Behavior<*> {
		return behavior
	}

	/** From BottomNavigationView **/

	@SuppressLint("ClickableViewAccessibility")
	override fun onTouchEvent(event: MotionEvent): Boolean {
		super.onTouchEvent(event)
		// Consume all events to avoid views under the BottomNavigationView from receiving touch events.
		return true
	}

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		val minHeightSpec = makeMinHeightSpec(heightMeasureSpec)
		super.onMeasure(widthMeasureSpec, minHeightSpec)
		if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY) {
			setMeasuredDimension(
				measuredWidth,
				max(
					measuredHeight,
					suggestedMinimumHeight + paddingTop + paddingBottom,
				),
			)
		}
	}

	private fun makeMinHeightSpec(measureSpec: Int): Int {
		var minHeight = suggestedMinimumHeight
		if (MeasureSpec.getMode(measureSpec) != MeasureSpec.EXACTLY && minHeight > 0) {
			minHeight += paddingTop + paddingBottom

			return MeasureSpec.makeMeasureSpec(
				max(MeasureSpec.getSize(measureSpec), minHeight), MeasureSpec.AT_MOST,
			)
		}

		return measureSpec
	}

	override fun getMaxItemCount(): Int = MAX_ITEM_COUNT

	@SuppressLint("RestrictedApi")
	override fun createNavigationBarMenuView(context: Context) = BottomNavigationMenuView(context)

	/** End **/

	override fun onSaveInstanceState(): Parcelable {
		val superState = super.onSaveInstanceState()
		return SavedState(superState, currentState, translationY)
	}

	override fun onRestoreInstanceState(state: Parcelable?) {
		if (state is SavedState) {
			super.onRestoreInstanceState(state.superState)
			super.setTranslationY(state.translationY)
			currentState = state.currentState
		} else {
			super.onRestoreInstanceState(state)
		}
	}

	override fun setTranslationY(translationY: Float) {
		// Disallow translation change when state down
		if (currentState != STATE_DOWN) {
			super.setTranslationY(translationY)
		}
	}

	override fun setMinimumHeight(minHeight: Int) {
		super.setMinimumHeight(minHeight)
		getChildAt(0)?.minimumHeight = minHeight
	}

	override fun setBackgroundColor(color: Int) {
		if (!isApplyingFloatingBackground) {
			backgroundTint = color
			if (isFloating) {
				applyFloatingBackground()
				return
			}
		}
		super.setBackgroundColor(color)
	}

	private fun applyFloatingBackground() {
		if (isFloating && anchoredBackground == null) {
			anchoredBackground = background
		}
		isApplyingFloatingBackground = true
		try {
			if (isFloating) {
				val tint = backgroundTint ?: MaterialColors.getColor(
					this,
					materialR.attr.colorSurfaceContainer,
					Color.TRANSPARENT,
				)
				background = MaterialShapeDrawable(
					ShapeAppearanceModel.builder()
						.setAllCorners(CornerFamily.ROUNDED, floatingCornerRadius)
						.build(),
				).apply {
					initializeElevationOverlay(context)
					fillColor = ColorStateList.valueOf(tint)
					elevation = this@SlidingBottomNavigationView.elevation
				}
			} else {
				val tint = backgroundTint
				if (tint != null) {
					super.setBackgroundColor(tint)
				} else {
					background = anchoredBackground
				}
			}
		} finally {
			isApplyingFloatingBackground = false
		}
	}

	fun show() {
		if (currentState == STATE_UP) {
			return
		}
		currentAnimator?.cancel()
		clearAnimation()

		currentState = STATE_UP
		animateTranslation(
			0F,
			SLIDE_UP_ANIMATION_DURATION,
			LinearOutSlowInInterpolator(),
		)
	}

	fun hide() {
		if (currentState == STATE_DOWN) {
			return
		}
		currentAnimator?.cancel()
		clearAnimation()

		currentState = STATE_DOWN
		val target = hideOffset
		if (target == 0f) {
			doOnNextLayout { applyHiddenPosition() }
			return
		}
		animateTranslation(
			target,
			SLIDE_DOWN_ANIMATION_DURATION,
			FastOutLinearInInterpolator(),
		)
	}

	private fun applyHiddenPosition() {
		if (currentState != STATE_DOWN) {
			return
		}
		val target = hideOffset
		if (target == 0f) {
			return
		}
		super.setTranslationY(target)
	}

	fun showOrHide(show: Boolean) {
		if (show) {
			show()
		} else {
			hide()
		}
	}

	private fun animateTranslation(targetY: Float, duration: Long, interpolator: TimeInterpolator) {
		currentAnimator = animate()
			.translationY(targetY)
			.setInterpolator(interpolator)
			.setDuration(duration)
			.applySystemAnimatorScale(context)
			.setListener(
				object : AnimatorListenerAdapter() {
					override fun onAnimationEnd(animation: Animator) {
						currentAnimator = null
						postInvalidate()
					}
				},
			)
	}

	internal class SavedState : AbsSavedState {

		var currentState = STATE_UP
		var translationY = 0F

		constructor(superState: Parcelable, currentState: Int, translationY: Float) : super(superState) {
			this.currentState = currentState
			this.translationY = translationY
		}

		constructor(source: Parcel, loader: ClassLoader?) : super(source, loader) {
			currentState = source.readInt()
			translationY = source.readFloat()
		}

		override fun writeToParcel(out: Parcel, flags: Int) {
			super.writeToParcel(out, flags)
			out.writeInt(currentState)
			out.writeFloat(translationY)
		}

		companion object {

			@Suppress("unused")
			@JvmField
			val CREATOR: Parcelable.Creator<SavedState> = object : Parcelable.Creator<SavedState> {
				override fun createFromParcel(`in`: Parcel) = SavedState(`in`, SavedState::class.java.classLoader)

				override fun newArray(size: Int): Array<SavedState?> = arrayOfNulls(size)
			}
		}
	}
}
