package org.koitharu.kotatsu.community.ui

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RatingBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
	private var loadJob: Job? = null
	private lateinit var postButton: Button
	private lateinit var saveButton: Button
	private val displayedComments = mutableListOf<CommunityComment>()
	private var restoringRating = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		manga = intent.getParcelableExtraCompat<ParcelableManga>(AppRouter.KEY_MANGA)?.manga ?: run {
			finish()
			return
		}
		title = manga.title
		val view = createView()
		setContentView(view)
		ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
			val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
			v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
			insets
		}
		editor.setText(savedInstanceState?.getString("draft").orEmpty())
		spoiler.isChecked = savedInstanceState?.getBoolean("spoiler") ?: false
		savedInstanceState?.getFloat("rating")?.let {
			rating.rating = it
			restoringRating = true
		}
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
			setOnRatingBarChangeListener { _, _, fromUser ->
				if (fromUser) restoringRating = true
			}
			layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
		}
		content.addView(rating)
		content.addView(Button(context).apply {
			text = getString(R.string.save)
			saveButton = this
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
			inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
			hint = getString(R.string.community_comment_hint)
			minLines = 3
			gravity = Gravity.TOP or Gravity.START
		}
		content.addView(editor)
		spoiler = CheckBox(context).apply { text = getString(R.string.community_spoiler) }
		content.addView(spoiler)
		content.addView(Button(context).apply {
			text = getString(R.string.community_post)
			postButton = this
			setOnClickListener { postComment() }
		})
		comments = LinearLayout(context).apply {
			orientation = LinearLayout.VERTICAL
		}
		content.addView(comments)
		return ScrollView(context).apply { addView(content) }
	}

	override fun onSaveInstanceState(outState: Bundle) {
		outState.putString("draft", editor.text.toString())
		outState.putBoolean("spoiler", spoiler.isChecked)
		outState.putFloat("rating", rating.rating)
		super.onSaveInstanceState(outState)
	}

	private fun load(append: Boolean = false) {
		loadJob?.cancel()
		val offset = if (append) displayedComments.size else 0
		loadJob = lifecycleScope.launch {
			try {
				val data = withContext(Dispatchers.IO) {
					community.ensureIdentity()
					community.getRating(manga) to community.getCommentsPage(manga, offset = offset)
				}
				if (!append && !restoringRating) rating.rating = data.first.mine ?: 0f
				minimumLength = data.second.minimumLength
				status.text = getString(R.string.community_comments_count, data.second.total)
				showComments(data.second, append)
			} catch (error: Throwable) {
				if (error is CancellationException) throw error
				status.text = getString(R.string.community_load_failed, error.getDisplayMessage(resources))
			}
		}
	}

	private fun saveRating() {
		val stars = rating.rating
		saveButton.isEnabled = false
		lifecycleScope.launch {
			try {
				withContext(Dispatchers.IO) { community.setRating(manga, stars) }
				status.text = getString(R.string.community_rating_saved)
			} catch (error: Throwable) {
				if (error is CancellationException) throw error
				status.text = error.getDisplayMessage(resources)
			} finally {
				saveButton.isEnabled = true
			}
		}
	}

	private fun postComment() {
		val body = editor.text.toString().trim()
		if (body.length < minimumLength) {
			status.text = getString(R.string.community_comment_too_short, minimumLength)
			return
		}
		val isSpoiler = spoiler.isChecked
		postButton.isEnabled = false
		lifecycleScope.launch {
			try {
				withContext(Dispatchers.IO) {
					community.postComment(manga, body, spoiler = isSpoiler)
				}
				if (editor.text.toString().trim() == body) {
					editor.text?.clear()
					spoiler.isChecked = false
				}
				load()
				status.text = getString(R.string.community_comment_posted)
			} catch (error: Throwable) {
				if (error is CancellationException) throw error
				status.text = error.getDisplayMessage(resources)
			} finally {
				postButton.isEnabled = true
			}
		}
	}

	private fun showComments(page: CommunityCommentPage, append: Boolean) {
		if (!append) displayedComments.clear()
		val seen = displayedComments.mapTo(HashSet()) { it.id }
		displayedComments.addAll(page.comments.filter { seen.add(it.id) })
		comments.removeAllViews()
		displayedComments.forEach { comments.addView(commentView(it)) }
		if (page.comments.isNotEmpty() && displayedComments.size < page.total) {
			comments.addView(Button(this).apply {
				text = getString(R.string.community_load_more)
				setOnClickListener { load(append = true) }
			})
		}
	}

	private fun commentView(comment: CommunityComment): LinearLayout {
		val root = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(comment.depth.coerceIn(0, 3) * resources.getDimensionPixelSize(R.dimen.screen_padding), 12, 0, 12)
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
				if (comment.isSpoiler) getString(R.string.community_show_spoiler) else comment.body
			}
			setTextColor(if (comment.deleted) Color.GRAY else currentTextColor)
			if (comment.isSpoiler && !comment.deleted) {
				setOnClickListener { text = comment.body }
			}
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
		root.addView(HorizontalScrollView(this).apply { addView(actions) })
		return root
	}

	private fun vote(comment: CommunityComment, value: Int) {
		lifecycleScope.launch {
			try {
				withContext(Dispatchers.IO) { community.vote(comment.id, if (comment.myVote == value) 0 else value) }
				load()
			} catch (error: Throwable) {
				if (error is CancellationException) throw error
				status.text = error.getDisplayMessage(resources)
			}
		}
	}

	private fun showReplyDialog(parent: CommunityComment) {
		showCommentEditor(R.string.community_reply, "", parentId = parent.id)
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
		val dialog = MaterialAlertDialogBuilder(this)
			.setTitle(title)
			.setView(input)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.community_post, null)
			.create()
		dialog.setOnShowListener {
			val submit = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
			submit.setOnClickListener {
				val body = input.text.toString().trim()
				if (body.length < minimumLength) {
					input.error = getString(R.string.community_comment_too_short, minimumLength)
					return@setOnClickListener
				}
				submit.isEnabled = false
				lifecycleScope.launch {
					try {
						withContext(Dispatchers.IO) {
							if (commentId != null) community.editComment(commentId, body)
							else community.postComment(manga, body, parentId = parentId)
						}
						dialog.dismiss()
						load()
					} catch (error: Throwable) {
						if (error is CancellationException) throw error
						input.error = error.getDisplayMessage(resources)
					} finally {
						submit.isEnabled = true
					}
				}
			}
		}
		dialog.show()
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
						if (error is CancellationException) throw error
						status.text = error.getDisplayMessage(resources)
					}
				}
			}
			.show()
	}
}
