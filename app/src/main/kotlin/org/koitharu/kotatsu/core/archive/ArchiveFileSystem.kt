package org.koitharu.kotatsu.core.archive

import okio.FileHandle
import okio.FileMetadata
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.Source
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/** Read-only Okio view preserving the paths used by existing local index/novel parsers. */
class ArchiveFileSystem(private val file: File, private val archives: ArchiveRepository) : FileSystem() {
    private val files = archives.entries(file).associateBy { ("/" + it.name.removeSuffix("/")).toPath() }
    private val directories = HashSet<Path>().apply {
        add("/".toPath())
        for ((path, metadata) in files) {
            if (metadata.isDirectory) add(path)
            var parent = path.parent
            while (parent != null) { add(parent); parent = parent.parent }
        }
    }
    private val handles = CopyOnWriteArrayList<FileHandle>()
    @Volatile private var closed = false

    override fun canonicalize(path: Path): Path = rooted(path).also {
        ensureOpen()
        if (it !in files && it !in directories) throw FileNotFoundException("Archive entry is missing")
    }

    override fun metadataOrNull(path: Path): FileMetadata? {
        ensureOpen()
        val rooted = rooted(path)
        if (rooted in directories) return FileMetadata(isRegularFile = false, isDirectory = true)
        val entry = files[rooted] ?: return null
        return FileMetadata(isRegularFile = true, isDirectory = false, size = entry.size)
    }

    override fun list(dir: Path): List<Path> = listOrNull(dir) ?: throw FileNotFoundException("Archive directory is missing")
    override fun listOrNull(dir: Path): List<Path>? {
        ensureOpen()
        val rooted = rooted(dir)
        if (rooted !in directories) return null
        return (directories.asSequence() + files.keys.asSequence()).filter { it.parent == rooted }.distinct().sorted().toList()
    }

    override fun openReadOnly(file: Path): FileHandle {
        ensureOpen()
        val entry = files[rooted(file)]?.takeUnless { it.isDirectory } ?: throw FileNotFoundException("Archive entry is missing")
        if ((entry.name.substringAfterLast('/') == "index.json" && entry.size > 2L * 1024 * 1024) ||
            ((entry.name.endsWith(".html", true) || entry.name.endsWith(".htm", true)) && entry.size > 4L * 1024 * 1024)) {
            throw ArchiveAccessException.Limit("Archive text metadata exceeds safe reading limits")
        }
        val data = archives.openSeekable(this.file, entry.name)
        val handle = object : FileHandle(readWrite = false) {
            override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int): Int =
                data.readAt(fileOffset, array, arrayOffset, byteCount)
            override fun protectedSize(): Long = data.size
            override fun protectedClose() { handles.remove(this); data.close() }
            override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int): Unit = readOnly()
            override fun protectedFlush(): Unit = readOnly()
            override fun protectedResize(size: Long): Unit = readOnly()
        }
        handles += handle
        if (closed) { handle.close(); throw IOException("Archive filesystem is closed") }
        return handle
    }

    override fun source(file: Path): Source = openReadOnly(file).let { handle ->
        try { handle.source().also { handle.close() } } catch (error: Throwable) { handle.close(); throw error }
    }

    override fun close() { closed = true; handles.toList().forEach { runCatching { it.close() } }; handles.clear() }
    override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle = readOnly()
    override fun sink(file: Path, mustCreate: Boolean): Sink = readOnly()
    override fun appendingSink(file: Path, mustExist: Boolean): Sink = readOnly()
    override fun createDirectory(dir: Path, mustCreate: Boolean): Unit = readOnly()
    override fun atomicMove(source: Path, target: Path): Unit = readOnly()
    override fun delete(path: Path, mustExist: Boolean): Unit = readOnly()
    override fun createSymlink(source: Path, target: Path): Unit = readOnly()

    private fun ensureOpen() { if (closed) throw IOException("Archive filesystem is closed") }
    private fun rooted(path: Path): Path {
        val name = path.toString().removePrefix("/")
        if (name.isNotEmpty()) validateArchivePath(name)
        return ("/" + name).toPath()
    }
    private fun readOnly(): Nothing = throw IOException("Archive access is read-only; export a separate copy to change protection")
}
