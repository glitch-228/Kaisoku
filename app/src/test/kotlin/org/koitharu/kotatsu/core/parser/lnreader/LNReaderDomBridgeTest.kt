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
}
