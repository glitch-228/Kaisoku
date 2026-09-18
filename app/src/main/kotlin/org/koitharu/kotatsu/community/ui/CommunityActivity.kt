package org.koitharu.kotatsu.community.ui

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RatingBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.community.data.CommunityComment
import org.koitharu.kotatsu.community.data.CommunityCommentPage
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
	private lateinit var spoiler: CheckBox
	private var minimumLength = 20

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
		spoiler = CheckBox(context).apply { text = getString(R.string.community_spoiler) }
		content.addView(spoiler)
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
					community.getRating(manga) to community.getCommentsPage(manga)
				}
				rating.rating = data.first.mine ?: data.first.average
				minimumLength = data.second.minimumLength
				status.text = getString(R.string.community_comments_count, data.second.total)
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
		if (body.length < minimumLength) {
			status.text = getString(R.string.community_comment_too_short, minimumLength)
			return
		}
		lifecycleScope.launch {
			try {
				val comment = withContext(Dispatchers.IO) {
					community.postComment(manga, body, spoiler = spoiler.isChecked)
				}
				editor.text?.clear()
				spoiler.isChecked = false
				load()
				status.text = getString(R.string.community_comment_posted)
			} catch (error: Throwable) {
				status.text = error.getDisplayMessage(resources)
			}
		}
	}

	private fun showComments(page: CommunityCommentPage) {
		comments.removeAllViews()
		page.comments.forEach { comments.addView(commentView(it)) }
	}

	private fun commentView(comment: CommunityComment): LinearLayout {
		val root = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(comment.depth.coerceAtMost(6) * resources.getDimensionPixelSize(R.dimen.screen_padding), 12, 0, 12)
		}
		root.addView(TextView(this).apply {
			text = buildString {
				append(comment.author.ifBlank { getString(R.string.community) })
				if (comment.up != 0 || comment.down != 0) append(" · +${comment.up}/-${comment.down}")
			}
		})
		root.addView(TextView(this).apply {
			text = if (comment.deleted) {
				getString(R.string.community_deleted_comment)
			} else {
				(if (comment.isSpoiler) "⚠️ " else "") + comment.body
			}
			setTextColor(if (comment.deleted) Color.GRAY else currentTextColor)
		})
		val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
		fun action(label: Int, callback: () -> Unit) {
			actions.addView(Button(this@CommunityActivity).apply {
				text = getString(label)
				setOnClickListener { callback() }
			})
		}
		if (!comment.deleted) {
			action(R.string.community_like) { vote(comment, 1) }
			action(R.string.community_dislike) { vote(comment, -1) }
			action(R.string.community_reply) { showReplyDialog(comment) }
			if (comment.isMine) {
				action(R.string.community_edit) { showEditDialog(comment) }
				action(R.string.community_delete_comment) { confirmDelete(comment) }
			}
		}
		root.addView(actions)
		return root
	}

	private fun vote(comment: CommunityComment, value: Int) {
		lifecycleScope.launch {
			try {
				withContext(Dispatchers.IO) { community.vote(comment.id, if (comment.myVote == value) 0 else value) }
				load()
			} catch (error: Throwable) {
				status.text = error.getDisplayMessage(resources)
			}
		}
	}

	private fun showReplyDialog(parent: CommunityComment) {
		showCommentEditor(R.string.community_reply, parent.body, parentId = parent.id)
	}

	private fun showEditDialog(comment: CommunityComment) {
		showCommentEditor(R.string.community_edit, comment.body, commentId = comment.id)
	}

	private fun showCommentEditor(title: Int, initial: String, parentId: Long? = null, commentId: Long? = null) {
		val input = EditText(this).apply {
			setText(initial)
			minLines = 4
			gravity = Gravity.TOP or Gravity.START
			inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
		}
		MaterialAlertDialogBuilder(this)
			.setTitle(title)
			.setView(input)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.community_post) { _, _ ->
				val body = input.text.toString().trim()
				if (body.length < minimumLength) {
					status.text = getString(R.string.community_comment_too_short, minimumLength)
					return@setPositiveButton
				}
				lifecycleScope.launch {
					try {
						withContext(Dispatchers.IO) {
							if (commentId != null) community.editComment(commentId, body)
							else community.postComment(manga, body, parentId = parentId)
						}
						load()
					} catch (error: Throwable) {
						status.text = error.getDisplayMessage(resources)
					}
				}
			}
			.show()
	}

	private fun confirmDelete(comment: CommunityComment) {
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.community_delete_comment)
			.setMessage(R.string.community_delete_comment_confirm)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.delete) { _, _ ->
				lifecycleScope.launch {
					try {
						withContext(Dispatchers.IO) { community.deleteComment(comment.id) }
						load()
					} catch (error: Throwable) {
						status.text = error.getDisplayMessage(resources)
					}
				}
			}
			.show()
	}
}
