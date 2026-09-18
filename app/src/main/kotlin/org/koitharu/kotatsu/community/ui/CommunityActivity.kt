package org.koitharu.kotatsu.community.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RatingBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.community.data.CommunityComment
import org.koitharu.kotatsu.community.data.CommunityRepository
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.getParcelableExtraCompat
import javax.inject.Inject

@AndroidEntryPoint
class CommunityActivity : AppCompatActivity() {

	@Inject
	lateinit var community: CommunityRepository

	private lateinit var manga: org.koitharu.kotatsu.parsers.model.Manga
	private lateinit var rating: RatingBar
	private lateinit var comments: LinearLayout
	private lateinit var status: TextView
	private lateinit var editor: EditText

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		manga = intent.getParcelableExtraCompat<ParcelableManga>(AppRouter.KEY_MANGA)?.manga ?: run {
			finish()
			return
		}
		title = manga.title
		setContentView(createView())
		load()
	}

	private fun createView(): ScrollView {
		val context = this
		val content = LinearLayout(context).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(resources.getDimensionPixelSize(R.dimen.screen_padding))
		}
		content.addView(TextView(context).apply {
			text = manga.title
			textSize = 22f
			setTextColor(resources.getColor(com.google.android.material.R.color.material_on_background_emphasis_high_type, theme))
		})
		content.addView(TextView(context).apply {
			text = getString(R.string.community_rating)
			setPadding(0, 24, 0, 4)
		})
		rating = RatingBar(context, null, android.R.attr.ratingBarStyle).apply {
			numStars = 5
			stepSize = 0.5f
			layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
		}
		content.addView(rating)
		content.addView(Button(context).apply {
			text = getString(R.string.community_rating_saved)
			setOnClickListener { saveRating() }
		})
		status = TextView(context).apply { setPadding(0, 8, 0, 8) }
		content.addView(status)
		content.addView(TextView(context).apply {
			text = getString(R.string.community_comments)
			textSize = 18f
			setPadding(0, 20, 0, 8)
		})
		editor = EditText(context).apply {
			hint = getString(R.string.community_comment_hint)
			minLines = 3
			gravity = Gravity.TOP or Gravity.START
		}
		content.addView(editor)
		content.addView(Button(context).apply {
			text = getString(R.string.community_post)
			setOnClickListener { postComment() }
		})
		comments = LinearLayout(context).apply {
			orientation = LinearLayout.VERTICAL
		}
		content.addView(comments)
		return ScrollView(context).apply { addView(content) }
	}

	private fun load() {
		lifecycleScope.launch {
			try {
				val data = withContext(Dispatchers.IO) {
					community.ensureIdentity()
					community.getRating(manga) to community.getComments(manga)
				}
				rating.rating = data.first.mine ?: data.first.average
				status.text = getString(R.string.community_comments_count, data.second.size)
				showComments(data.second)
			} catch (error: Throwable) {
				status.text = getString(R.string.community_load_failed, error.getDisplayMessage(resources))
			}
		}
	}

	private fun saveRating() {
		lifecycleScope.launch {
			try {
				withContext(Dispatchers.IO) { community.setRating(manga, rating.rating) }
				status.text = getString(R.string.community_rating_saved)
			} catch (error: Throwable) {
				status.text = error.getDisplayMessage(resources)
			}
		}
	}

	private fun postComment() {
		val body = editor.text.toString().trim()
		if (body.isBlank()) return
		lifecycleScope.launch {
			try {
				val comment = withContext(Dispatchers.IO) { community.postComment(manga, body) }
				editor.text?.clear()
				comments.addView(commentView(comment), 0)
				status.text = getString(R.string.community_comment_posted)
			} catch (error: Throwable) {
				status.text = error.getDisplayMessage(resources)
			}
		}
	}

	private fun showComments(items: List<CommunityComment>) {
		comments.removeAllViews()
		items.forEach { comments.addView(commentView(it)) }
	}

	private fun commentView(comment: CommunityComment): TextView = TextView(this).apply {
		text = buildString {
			append(comment.author.ifBlank { getString(R.string.community) })
			append("\n")
			append(if (comment.isSpoiler) "⚠️ " else "")
			append(if (comment.deleted) "[deleted]" else comment.body)
		}
		setTextColor(if (comment.deleted) Color.GRAY else currentTextColor)
		setPadding(0, 12, 0, 12)
	}
}

