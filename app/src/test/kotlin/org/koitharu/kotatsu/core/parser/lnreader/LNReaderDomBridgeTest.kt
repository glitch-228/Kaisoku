package org.koitharu.kotatsu.core.parser.lnreader

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LNReaderDomBridgeTest {
	private val dom = LNReaderDomBridge()
	private fun call(op: String, vararg args: Any): Any? {
		val response = JSONObject(dom.call(op, JSONArray(args).toString()))
		assertFalse(response.optString("error"), response.has("error"))
		return response.get("value")
	}

	@Test fun arbitraryAttributesAndFindStayWithinSelectedNodes() {
		val root = call("parse", "<div id='a'><a title='One' href='/one'><img alt='Cover' data-original='image'></a></div><div id='b'><a title='Two'></a></div>")!!
		val first = call("find", root, "#a")!!
		val link = call("find", first, "a")!!
		assertEquals("One", call("attr", link, "title"))
		val image = call("find", link, "img")!!
		assertEquals("Cover", call("attr", image, "alt"))
		assertEquals("image", call("attr", image, "data-original"))
		assertEquals(0, (call("find", first, "#a") as JSONArray).length())
	}

	@Test fun mutationsAreVisibleToPreviouslyCreatedSelections() {
		val root = call("parse", "<p>first<br>second<span>remove me</span></p>")!!
		val paragraph = call("find", root, "p")!!
		call("replaceWith", call("find", paragraph, "br")!!, "\n")
		call("remove", call("find", paragraph, "span")!!)
		assertEquals("first\nsecond", call("text", paragraph))
		assertFalse(call("html", paragraph).toString().contains("remove me"))
	}

	@Test fun contentsIncludeTextAndSiblingsFollowTheDom() {
		val root = call("parse", "<p>Author:<b>Writer</b>tail</p>")!!
		val paragraph = call("find", root, "p")!!
		assertEquals(3, (call("contents", paragraph) as JSONArray).length())
		assertEquals("Writer", call("text", call("find", paragraph, "b")!!))
	}

	@Test fun siblingInsertionsPreserveMarkupOrderAndSelectedNodeIdentities() {
		val root = call("parse", "<main><p>A</p><p>B</p></main>")!!
		val paragraphs = call("find", root, "p")!!
		call("before", paragraphs, "<b>1</b><i>2</i>")
		call("after", paragraphs, "<b>3</b><i>4</i>")
		assertEquals("12A3412B34", call("text", root))
		assertEquals(paragraphs.toString(), call("find", root, "p").toString())
		assertEquals("AB", call("text", paragraphs))
		call("setText", paragraphs, "Updated")
		assertEquals("12Updated3412Updated34", call("text", root))
	}

	@Test fun synopsisBreaksRemainTextInsteadOfEscapedMarkup() {
		val root = call("parse", "<div><p>First<br>line</p><p>Second &amp; third</p></div>")!!
		call("replaceWith", call("find", root, "br")!!, "\n")
		call("after", call("find", root, "p, div")!!, "\n\n")
		assertEquals("First\nline\n\nSecond & third\n\n\n\n", call("text", root))
	}

	@Test fun siblingInsertionsHandleTextNodesEmptySelectionsAndDetachedNodes() {
		val root = call("parse", "<p>Text<b>End</b></p>")!!
		val paragraph = call("find", root, "p")!!
		val textNode = JSONArray().put((call("contents", paragraph) as JSONArray).getInt(0))
		call("before", textNode, "<i>Before</i>")
		call("after", textNode, "After")
		assertEquals("BeforeTextAfterEnd", call("text", root))
		for (op in listOf("before", "after")) {
			call(op, JSONArray(), "Ignored")
			call(op, root, "Ignored")
		}
		call("remove", paragraph)
		call("after", paragraph, "Ignored")
		call("before", paragraph, "Ignored")
		assertEquals("BeforeTextAfterEnd", call("text", paragraph))
		assertEquals("", call("text", root))
	}
}
