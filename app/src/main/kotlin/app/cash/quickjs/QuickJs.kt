/*
 * Copyright 2026 Kototoro contributors (https://github.com/skepsun/kototoro)
 * Licensed under Apache License, Version 2.0. Ported for LNReader plugin support;
 * keeps the app.cash.quickjs API surface alive for hosted Mihon/Tachiyomi
 * extensions that declare it compileOnly, on top of the dokar3 QuickJS engine.
 */
package app.cash.quickjs

import com.dokar.quickjs.QuickJs as DokarQuickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.Closeable

class QuickJs private constructor(
	private val delegate: DokarQuickJs,
) : Closeable {

	companion object {
		@JvmStatic
		fun create(): QuickJs {
			QuickJsNativeLoader.load()
			return QuickJs(DokarQuickJs.create(jobDispatcher = Dispatchers.Default))
		}
	}

	fun evaluate(script: String, fileName: String): Any? {
		return runBlocking {
			delegate.evaluate<Any?>(script, fileName, false)
		}
	}

	fun evaluate(script: String): Any? = evaluate(script, "main.js")

	fun <T> set(name: String, type: Class<T>, value: T) {
		val literal = toJsLiteral(value)
		evaluate("globalThis[${name.quote()}] = $literal;", "set.js")
	}

	@Suppress("UNCHECKED_CAST")
	fun <T> get(name: String, type: Class<T>): T? {
		val value = evaluate("globalThis[${name.quote()}];", "get.js")
		return if (type.isInstance(value)) value as T else null
	}

	fun compile(script: String, fileName: String): ByteArray {
		return delegate.compile(script, fileName, false)
	}

	fun execute(bytecode: ByteArray): Any? {
		return runBlocking {
			delegate.evaluate<Any?>(bytecode)
		}
	}

	override fun close() {
		delegate.close()
	}

	@Suppress("ProtectedInFinal")
	protected fun finalize() {
		close()
	}

	private fun String.quote(): String = buildString(length + 2) {
		append('"')
		for (ch in this@quote) {
			when (ch) {
				'\\' -> append("\\\\")
				'"' -> append("\\\"")
				'\n' -> append("\\n")
				'\r' -> append("\\r")
				'\t' -> append("\\t")
				else -> append(ch)
			}
		}
		append('"')
	}

	private fun toJsLiteral(value: Any?): String = when (value) {
		null -> "null"
		is String -> value.quote()
		is Boolean -> value.toString()
		is Number -> value.toString()
		else -> throw QuickJsException("Unsupported value type: ${value::class.java.name}")
	}
}
