package org.koitharu.kotatsu.reader.domain

import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import java.lang.ref.WeakReference

/**
 * Anime4K v0.9 (gradient push) ported to AGSL, applied as a screen-space RenderEffect.
 * Refines line art on pages that are rendered above their native resolution.
 */
object UpscaleEffect {

	// only pages whose native size is well below the screen are considered low-res
	const val MIN_SCALE = 1.5f

	val isSupported: Boolean
		get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

	/** Page ids currently rendered with the effect, used to toggle the toolbar icon */
	private val mutableActivePages = MutableStateFlow<Set<Long>>(emptySet())
	val activePages = mutableActivePages.asStateFlow()

	fun setActive(pageId: Long, isActive: Boolean) {
		mutableActivePages.update { pages ->
			if (isActive) pages + pageId else pages - pageId
		}
	}

	/** What the reader is showing right now, in source-image coordinates */
	class Viewport(val scale: Float, val centerX: Float, val centerY: Float, val fitScale: Float)

	private data class RegisteredView(
		val view: WeakReference<SubsamplingScaleImageView>,
		val isEnabled: Boolean,
		val config: UpscaleConfig,
	)

	private val views = HashMap<Long, RegisteredView>()

	fun registerView(pageId: Long, ssiv: SubsamplingScaleImageView, isEnabled: Boolean, config: UpscaleConfig = UpscaleConfig()) {
		views.entries.removeAll { (id, registration) ->
			registration.view.get() == null || id != pageId && registration.view.get() === ssiv
		}
		views[pageId] = RegisteredView(WeakReference(ssiv), isEnabled, config)
	}

	fun unregisterView(pageId: Long, ssiv: SubsamplingScaleImageView) {
		if (views[pageId]?.view?.get() === ssiv) {
			views.remove(pageId)
		}
		setActive(pageId, false)
	}

	fun shouldApply(
		isSupported: Boolean,
		isEnabled: Boolean,
		isPowerSaveMode: Boolean,
		fitScale: Float,
		config: UpscaleConfig = UpscaleConfig(),
	): Boolean = isSupported && isEnabled && !isPowerSaveMode && config.strength > 0 && fitScale > config.threshold

	fun passCount(scale: Float): Int = when {
		scale >= 3f -> 4
		scale >= 2f -> 3
		else -> 2
	}

	/**
	 * Computed live from the page view, so it reflects the portion of the page actually
	 * on screen — important for webtoon strips that are much taller than the viewport.
	 */
	fun viewportFor(pageId: Long): Viewport? {
		val ssiv = views[pageId]?.view?.get() ?: return null
		if (!ssiv.isReady || !ssiv.isShown) {
			return null
		}
		val visible = Rect()
		if (!ssiv.getGlobalVisibleRect(visible)) {
			return null
		}
		val location = IntArray(2)
		ssiv.getLocationOnScreen(location)
		val center = ssiv.viewToSourceCoord(
			visible.exactCenterX() - location[0],
			visible.exactCenterY() - location[1],
		) ?: return null
		return Viewport(ssiv.scale, center.x, center.y, ssiv.width / ssiv.sWidth.toFloat())
	}

	fun refreshPowerSaveState(isPowerSaveMode: Boolean) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
		views.entries.removeAll { (pageId, registration) ->
			val view = registration.view.get()
			if (view == null) {
				setActive(pageId, false)
				true
			} else {
				val fitScale = if (view.isReady && view.sWidth > 0) view.width / view.sWidth.toFloat() else 0f
				val effect = if (shouldApply(true, registration.isEnabled, isPowerSaveMode, fitScale, registration.config)) {
					create(fitScale, registration.config)
				} else {
					null
				}
				view.setRenderEffect(effect)
				setActive(pageId, effect != null)
				false
			}
		}
	}

	@RequiresApi(Build.VERSION_CODES.TIRAMISU)
	fun create(scale: Float, config: UpscaleConfig = UpscaleConfig()): RenderEffect? = runCatching {
		// Anime4K is iterative: chain more push passes the more the page is stretched
		val passes = config.passes.takeIf { it > 0 } ?: passCount(scale)
		val px = (scale / 2f).coerceIn(1f, 2.5f)
		var effect: RenderEffect? = null
		repeat(passes) {
			val shader = RuntimeShader(AGSL)
			shader.setFloatUniform("strength", config.strength / 100f)
			shader.setFloatUniform("px", px)
			val pass = RenderEffect.createRuntimeShaderEffect(shader, "src")
			effect = effect?.let { RenderEffect.createChainEffect(pass, it) } ?: pass
		}
		effect
	}.onFailure {
		it.printStackTraceDebug()
	}.getOrNull()

	private val AGSL = """
		uniform shader src;
		uniform float strength;
		uniform float px;

		float lum(float2 p) {
			return dot(float4(src.eval(p)).rgb, float3(0.299, 0.587, 0.114));
		}

		float4 push(float4 cc, float4 a, float4 b, float4 c) {
			return mix(cc, (a + b + c) / 3.0, strength);
		}

		half4 main(float2 fc) {
			float4 ctl = float4(src.eval(fc + float2(-px, -px)));
			float4 ctc = float4(src.eval(fc + float2(0.0, -px)));
			float4 ctr = float4(src.eval(fc + float2(px, -px)));
			float4 cml = float4(src.eval(fc + float2(-px, 0.0)));
			float4 cmc = float4(src.eval(fc));
			float4 cmr = float4(src.eval(fc + float2(px, 0.0)));
			float4 cbl = float4(src.eval(fc + float2(-px, px)));
			float4 cbc = float4(src.eval(fc + float2(0.0, px)));
			float4 cbr = float4(src.eval(fc + float2(px, px)));

			float l11 = dot(ctl.rgb, float3(0.299, 0.587, 0.114));
			float l12 = dot(ctc.rgb, float3(0.299, 0.587, 0.114));
			float l13 = dot(ctr.rgb, float3(0.299, 0.587, 0.114));
			float l21 = dot(cml.rgb, float3(0.299, 0.587, 0.114));
			float l22 = dot(cmc.rgb, float3(0.299, 0.587, 0.114));
			float l23 = dot(cmr.rgb, float3(0.299, 0.587, 0.114));
			float l31 = dot(cbl.rgb, float3(0.299, 0.587, 0.114));
			float l32 = dot(cbc.rgb, float3(0.299, 0.587, 0.114));
			float l33 = dot(cbr.rgb, float3(0.299, 0.587, 0.114));

			float l01 = lum(fc + float2(-px, -2.0 * px));
			float l02 = lum(fc + float2(0.0, -2.0 * px));
			float l03 = lum(fc + float2(px, -2.0 * px));
			float l10 = lum(fc + float2(-2.0 * px, -px));
			float l14 = lum(fc + float2(2.0 * px, -px));
			float l20 = lum(fc + float2(-2.0 * px, 0.0));
			float l24 = lum(fc + float2(2.0 * px, 0.0));
			float l30 = lum(fc + float2(-2.0 * px, px));
			float l34 = lum(fc + float2(2.0 * px, px));
			float l41 = lum(fc + float2(-px, 2.0 * px));
			float l42 = lum(fc + float2(0.0, 2.0 * px));
			float l43 = lum(fc + float2(px, 2.0 * px));

			// 1 - gradient magnitude: high value = away from a line
			float atl = 1.0 - clamp(abs(l12 - l10) + abs(l21 - l01), 0.0, 1.0);
			float atc = 1.0 - clamp(abs(l13 - l11) + abs(l22 - l02), 0.0, 1.0);
			float atr = 1.0 - clamp(abs(l14 - l12) + abs(l23 - l03), 0.0, 1.0);
			float aml = 1.0 - clamp(abs(l22 - l20) + abs(l31 - l11), 0.0, 1.0);
			float amc = 1.0 - clamp(abs(l23 - l21) + abs(l32 - l12), 0.0, 1.0);
			float amr = 1.0 - clamp(abs(l24 - l22) + abs(l33 - l13), 0.0, 1.0);
			float abl = 1.0 - clamp(abs(l32 - l30) + abs(l41 - l21), 0.0, 1.0);
			float abc = 1.0 - clamp(abs(l33 - l31) + abs(l42 - l22), 0.0, 1.0);
			float abr = 1.0 - clamp(abs(l34 - l32) + abs(l43 - l23), 0.0, 1.0);

			float minL; float maxD;

			minL = min(min(atl, atc), atr);
			maxD = max(max(abl, abc), abr);
			if (minL > amc && minL > maxD) { return half4(push(cmc, ctl, ctc, ctr)); }
			minL = min(min(abl, abc), abr);
			maxD = max(max(atl, atc), atr);
			if (minL > amc && minL > maxD) { return half4(push(cmc, cbl, cbc, cbr)); }

			minL = min(min(atl, aml), abl);
			maxD = max(max(atr, amr), abr);
			if (minL > amc && minL > maxD) { return half4(push(cmc, ctl, cml, cbl)); }
			minL = min(min(atr, amr), abr);
			maxD = max(max(atl, aml), abl);
			if (minL > amc && minL > maxD) { return half4(push(cmc, ctr, cmr, cbr)); }

			minL = min(min(atc, atr), amr);
			maxD = max(max(aml, abl), abc);
			if (minL > amc && minL > maxD) { return half4(push(cmc, ctc, ctr, cmr)); }
			minL = min(min(aml, abl), abc);
			maxD = max(max(atc, atr), amr);
			if (minL > amc && minL > maxD) { return half4(push(cmc, cml, cbl, cbc)); }

			minL = min(min(atl, atc), aml);
			maxD = max(max(amr, abr), abc);
			if (minL > amc && minL > maxD) { return half4(push(cmc, ctl, ctc, cml)); }
			minL = min(min(amr, abr), abc);
			maxD = max(max(atl, atc), aml);
			if (minL > amc && minL > maxD) { return half4(push(cmc, cmr, cbr, cbc)); }

			return half4(cmc);
		}
	""".trimIndent()
}
