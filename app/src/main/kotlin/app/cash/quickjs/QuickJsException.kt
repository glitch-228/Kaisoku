/*
 * Copyright 2026 Kototoro contributors (https://github.com/skepsun/kototoro)
 * Licensed under Apache License, Version 2.0. Ported for LNReader plugin support.
 */
package app.cash.quickjs

class QuickJsException : RuntimeException {
	constructor(message: String) : super(message)
	constructor(message: String, jsStack: String) : super("$message\n$jsStack")
}
