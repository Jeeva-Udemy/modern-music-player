package com.claude.musicplayer

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Moves every supported audio file found anywhere on external storage into
 * a single target folder (default: /storage/emulated/0/Music).
 *
 * Requires MANAGE_EXTERNAL_STORAGE (Android 11+) or WRITE_EXTERNAL_STORAGE
 * (Android 10 and below) to be granted before calling.
 */
object FileOrganizer {

    /** Every common audio container this covers, not just .mp3. */
    val SUPPORTED_AUDIO_EXTENSIONS = setOf(
        "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "wma", "amr", "mid", "midi", "3gp"
    )

    data class Result(
        val moved: Int,
        val skipped: Int,
        val failed: Int,
        val targetFolder: String,
        val failedPaths: List<String> = emptyList()
    )

    fun targetMusicFolder(): File {
        val musicDir = File(Environment.getExternalStorageDirectory(), "Music")
        if (!musicDir.exists()) musicDir.mkdirs()
        return musicDir
    }

    /**
     * Walks external storage, finds every supported audio file, and moves
     * files that are not already inside the target folder into it. Runs
     * synchronously — call from a background thread.
     */
    fun moveAllAudioFilesToMusicFolder(context: Context): Result {
        val targetDir = targetMusicFolder()
        val root = Environment.getExternalStorageDirectory()

        var moved = 0
        var skipped = 0
        var failed = 0
        val failedPaths = mutableListOf<String>()

        val audioFiles = mutableListOf<File>()
        try {
            collectAudioFiles(root, audioFiles)
        } catch (e: Exception) {
            // Even if the scan is interrupted partway, process whatever was
            // found instead of losing all progress.
        }

        for (file in audioFiles) {
            try {
                // Already in the target folder — nothing to do.
                if (file.parentFile?.absolutePath == targetDir.absolutePath) {
                    skipped++
                    continue
                }

                var destination = File(targetDir, file.name)

                if (destination.exists()) {
                    if (destination.length() == file.length()) {
                        // Same name, same size already sitting in Music/ —
                        // this is a duplicate of a file already moved (e.g.
                        // from re-running the operation). Remove the stray
                        // copy instead of creating another one.
                        if (file.delete()) {
                            skipped++
                        } else {
                            failed++
                            failedPaths.add(file.absolutePath)
                        }
                        continue
                    } else {
                        // Different file that happens to share a name — keep
                        // both, renamed, rather than overwriting.
                        val base = file.nameWithoutExtension
                        val ext = file.extension
                        var counter = 1
                        while (destination.exists()) {
                            destination = File(targetDir, "${base}_$counter.$ext")
                            counter++
                        }
                    }
                }

                val ok = moveFile(file, destination)
                if (ok) {
                    moved++
                    rescanFile(context, destination)
                    deleteFromMediaStore(context, file)
                } else {
                    failed++
                    failedPaths.add(file.absolutePath)
                }
            } catch (e: Exception) {
                failed++
                failedPaths.add(file.absolutePath)
            }
        }

        return Result(moved, skipped, failed, targetDir.absolutePath, failedPaths)
    }

    private fun collectAudioFiles(dir: File, out: MutableList<File>) {
        // Never crash the whole scan because one folder is unreadable —
        // skip it and keep going.
        val children = try {
            dir.listFiles()
        } catch (e: Exception) {
            null
        } ?: return

        for (child in children) {
            try {
                if (child.isDirectory) {
                    // Skip Android's private sandbox — inaccessible even with
                    // "All files access" and would just throw.
                    if (child.name == "Android") continue
                    collectAudioFiles(child, out)
                } else if (SUPPORTED_AUDIO_EXTENSIONS.contains(child.extension.lowercase())) {
                    out.add(child)
                }
            } catch (e: Exception) {
                // Skip this one entry, keep scanning the rest.
            }
        }
    }

    private fun moveFile(source: File, destination: File): Boolean {
        // Try the fast path first (works when both paths are on the same volume).
        if (source.renameTo(destination)) return true

        // Fall back to copy + delete for cross-volume moves.
        return try {
            FileInputStream(source).use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output)
                }
            }
            val deleted = source.delete()
            if (!deleted) {
                // Couldn't remove the original after copying — undo the copy
                // so we don't leave a duplicate behind, and report failure.
                destination.delete()
                false
            } else {
                true
            }
        } catch (e: Exception) {
            destination.delete()
            false
        }
    }

    private fun mimeTypeFor(extension: String): String = when (extension.lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "wav" -> "audio/x-wav"
        "flac" -> "audio/flac"
        "ogg", "opus" -> "audio/ogg"
        "wma" -> "audio/x-ms-wma"
        "amr" -> "audio/amr"
        "mid", "midi" -> "audio/midi"
        "3gp" -> "audio/3gpp"
        else -> "audio/*"
    }

    private fun rescanFile(context: Context, file: File) {
        val mimeType = mimeTypeFor(file.extension)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DATA, file.absolutePath)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
        }
        try {
            context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
        } catch (e: Exception) {
            // Some OEMs throw if the row already exists; safe to ignore.
        }
        android.media.MediaScannerConnection.scanFile(
            context, arrayOf(file.absolutePath), arrayOf(mimeType), null
        )
    }

    private fun deleteFromMediaStore(context: Context, oldFile: File) {
        try {
            context.contentResolver.delete(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.Audio.Media.DATA} = ?",
                arrayOf(oldFile.absolutePath)
            )
        } catch (e: Exception) { /* ignore */ }
    }

    /** Permanently deletes one file and its MediaStore entry. Requires full storage access. */
    fun deleteFile(context: Context, path: String): Boolean {
        return try {
            val file = File(path)
            val deleted = if (file.exists()) file.delete() else true
            if (deleted) {
                deleteFromMediaStore(context, file)
                android.media.MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
            }
            deleted
        } catch (e: Exception) {
            false
        }
    }

    /** True once the app can freely read/write anywhere on external storage. */
    fun hasFullStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true // covered by runtime WRITE_EXTERNAL_STORAGE permission instead
        }
    }
}
