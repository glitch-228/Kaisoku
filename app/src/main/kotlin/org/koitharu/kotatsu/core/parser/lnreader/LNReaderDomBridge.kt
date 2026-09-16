package org.koitharu.kotatsu.core.parser.lnreader

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.util.IdentityHashMap

/** One live DOM per plugin context. Selections hold node identities, never stale HTML snapshots. */
internal class LNReaderDomBridge {

	private val nodes = ArrayList<Node>()
	private val ids = IdentityHashMap<Node, Int>()

	private fun id(node: Node): Int = ids[node] ?: nodes.size.also {
		nodes.add(node)
		ids[node] = it
	}

	fun call(operation: String, arguments: String): String = try {
		val args = JSONArray(arguments)
		val result: Any? = if (operation == "parse") {
			listOf(id(Jsoup.parse(args.getString(0), args.optString(1))))
		} else {
			val selected = args.getJSONArray(0).let { array ->
				(0 until array.length()).map { nodes[array.getInt(it)] }
			}
			val elements = selected.filterIsInstance<Element>()
			val value = args.optString(1)
			when (operation) {
				"find" -> elements.flatMap { root -> root.select(value).filter { it !== root } }.distinct().map(::id)
				"filter" -> elements.filter { it.`is`(value) }.map(::id)
				"parent" -> selected.mapNotNull { it.parentNode() }.distinct().map(::id)
				"next" -> elements.mapNotNull { it.nextElementSibling() }.distinct().map(::id)
				"prev" -> elements.mapNotNull { it.previousElementSibling() }.distinct().map(::id)
				"children" -> elements.flatMap { it.children() }.distinct().map(::id)
				"contents" -> selected.flatMap { it.childNodes() }.distinct().map(::id)
				"text" -> selected.joinToString("") { text(it) }
				"html" -> elements.firstOrNull()?.html()
				"outerHtml" -> selected.joinToString("") { it.outerHtml() }
				"attr" -> selected.firstOrNull()?.takeIf { it.hasAttr(value) }?.attr(value)
				"remove" -> { selected.forEach { if (it.parentNode() != null) it.remove() }; null }
				"replaceWith" -> {
					selected.forEach { node ->
						val parent = node.parentNode() as? Element
						if (parent != null) {
							Parser.parseFragment(value, parent, parent.baseUri()).forEach { node.before(it) }
							node.remove()
						}
					}
					null
				}
				"setAttr" -> { selected.forEach { it.attr(value, args.getString(2)) }; null }
				"removeAttr" -> { selected.forEach { it.removeAttr(value) }; null }
				"setText" -> { elements.forEach { it.text(value) }; null }
				"setHtml" -> { elements.forEach { it.html(value) }; null }
				"append" -> { elements.forEach { it.append(value) }; null }
				"prepend" -> { elements.forEach { it.prepend(value) }; null }
				else -> error("Unsupported DOM operation: $operation")
			}
		}
		JSONObject().put("value", JSONObject.wrap(result)).toString()
	} catch (e: Exception) {
		JSONObject().put("error", e.message ?: e.javaClass.simpleName).toString()
	}

	private fun text(node: Node): String = when (node) {
		is TextNode -> node.wholeText
		else -> node.childNodes().joinToString("") { text(it) }
	}
}
