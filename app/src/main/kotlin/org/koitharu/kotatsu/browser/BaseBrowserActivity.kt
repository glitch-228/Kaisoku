package org.koitharu.kotatsu.browser

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.network.CommonHeaders
import org.koitharu.kotatsu.core.network.proxy.ProxyProvider
import org.koitharu.kotatsu.core.network.webview.adblock.AdBlock
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.parser.ParserMangaRepository
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.util.ext.configureForParser
import org.koitharu.kotatsu.core.util.ext.consumeAll
import org.koitharu.kotatsu.databinding.ActivityBrowserBinding
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import javax.inject.Inject

@AndroidEntryPoint
abstract class BaseBrowserActivity : BaseActivity<ActivityBrowserBinding>(), BrowserCallback {

	@Inject
	lateinit var proxyProvider: ProxyProvider

	@Inject
	lateinit var mangaRepositoryFactory: MangaRepository.Factory

	@Inject
	lateinit var adBlock: AdBlock

	private lateinit var onBackPressedCallback: WebViewBackPressedCallback

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		if (!setContentViewWebViewSafe { ActivityBrowserBinding.inflate(layoutInflater) }) {
			return
		}
		viewBinding.webView.webChromeClient = ProgressChromeClient(viewBinding.progressBar)
		onBackPressedCallback = WebViewBackPressedCallback(viewBinding.webView)
		onBackPressedDispatcher.addCallback(onBackPressedCallback)

		val mangaSource = MangaSource(intent?.getStringExtra(AppRouter.KEY_SOURCE))
		val repository = mangaRepositoryFactory.create(mangaSource) as? ParserMangaRepository
		val userAgent = intent?.getStringExtra(AppRouter.KEY_USER_AGENT)?.nullIfEmpty()
			?: repository?.getRequestHeaders()?.get(CommonHeaders.USER_AGENT)
		viewBinding.webView.configureForParser(userAgent)

		onCreate2(savedInstanceState, mangaSource, repository)
	}

	protected abstract fun onCreate2(
		savedInstanceState: Bundle?,
		source: MangaSource,
		repository: ParserMangaRepository?
	)

	override fun onApplyWindowInsets(
		v: View,
		insets: WindowInsetsCompat
	): WindowInsetsCompat {
		val type = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
		val barsInsets = insets.getInsets(type)
		viewBinding.webView.updatePadding(
			left = barsInsets.left,
			right = barsInsets.right,
			bottom = barsInsets.bottom,
		)
		viewBinding.appbar.updatePadding(
			left = barsInsets.left,
			right = barsInsets.right,
			top = barsInsets.top,
		)
		return insets.consumeAll(type)
	}

	override fun onPause() {
		// Make WebView cookie writes visible to the shared okhttp cookie jar immediately; without a
		// flush they can stay RAM-only until the next WebView flush and a refresh inside this pause
		// window misses the just-set cookies.
		runCatching { CookieManager.getInstance().flush() }
		viewBinding.webView.onPause()
		super.onPause()
	}

	override fun onResume() {
		super.onResume()
		viewBinding.webView.onResume()
	}

	override fun onDestroy() {
		if (hasViewBinding()) {
			runCatching { CookieManager.getInstance().flush() }
			val webView = viewBinding.webView
			webView.stopLoading()
			// Both clients can retain this Activity through their callbacks. Detach the WebView from
			// the window and replace them before destroy() so Chromium cannot keep the destroyed
			// Activity's complete view hierarchy alive through WebView.mContext.
			webView.webViewClient = WebViewClient()
			webView.webChromeClient = WebChromeClient()
			(webView.parent as? ViewGroup)?.removeView(webView)
			webView.removeAllViews()
			webView.destroy()
		}
		super.onDestroy()
	}

	override fun onLoadingStateChanged(isLoading: Boolean) {
		viewBinding.progressBar.isVisible = isLoading
	}

	override fun onTitleChanged(title: CharSequence, subtitle: CharSequence?) {
		this.title = title
		supportActionBar?.subtitle = subtitle
	}

	override fun onHistoryChanged() {
		onBackPressedCallback.onHistoryChanged()
	}
}
