package org.koitharu.kotatsu.local.data

import java.io.File

private fun isZipExtension(ext: String?): Boolean {
	return ext.equals("cbz", ignoreCase = true) || ext.equals("zip", ignoreCase = true)
}

private fun isRarComicExtension(ext: String?): Boolean {
	return ext.equals("cbr", ignoreCase = true)
}

fun hasZipExtension(string: String): Boolean {
	val ext = string.substringAfterLast('.', "")
	return isZipExtension(ext)
}

fun hasImageExtension(string: String): Boolean {
	val ext = string.substringAfterLast('.', "")
	return IMAGE_EXTENSIONS.contains(ext)
}

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "heif", "bmp")

fun hasRarComicExtension(string: String): Boolean {
	val ext = string.substringAfterLast('.', "")
	return isRarComicExtension(ext)
}

val File.isZipArchive: Boolean
	get() = isFile && isZipExtension(extension)
