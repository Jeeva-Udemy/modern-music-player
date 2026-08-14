package com.claude.musicplayer

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream

/**
 * Copies audio files out of a folder the user explicitly picked via the
 * system folder picker (Storage Access Framework), into Music/.
 *
 * This exists because folders like another app's
 * Android/media/<package>/... (e.g. WhatsApp's voice notes) are blocked to
 * every other app's regular file APIs on Android 11+, even with "All files
 * access" granted — that restriction can't be bypassed by this app or any
 * other. SAF, with the user picking the exact folder and explicitly
 * granting access through the system picker, is the one Android-sanctioned
 * way around it.
 */
object SafImporter {

    fun importAudioFrom(context: Context, treeUri: Uri): Int {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return 0
        val targetDir = FileOrganizer.targetMusicFolder()
        var count = 0
        copyRecursively(context, root, targetDir) { count++ }
        return count
    }

    private fun copyRecursively(context: Context, dir: DocumentFile, targetDir: File, onCopied: () -> Unit) {
        val children = try {
            dir.listFiles()
        } catch (e: Exception) {
            return
        }

        for (doc in children) {
            try {
                if (doc.isDirectory) {
                    copyRecursively(context, doc, targetDir, onCopied)
                    continue
                }

                val name = doc.name ?: continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in FileOrganizer.SUPPORTED_AUDIO_EXTENSIONS) continue

                var destination = File(targetDir, name)
                if (destination.exists()) {
                    if (destination.length() == doc.length()) {
                        continue // same name + size already there — treat as already imported
                    }
                    val base = destination.nameWithoutExtension
                    var counter = 1
                    while (destination.exists()) {
                        destination = File(targetDir, "${base}_$counter.$ext")
                        counter++
                    }
                }

                context.contentResolver.openInputStream(doc.uri)?.use { input ->
                    FileOutputStream(destination).use { output -> input.copyTo(output) }
                }
                android.media.MediaScannerConnection.scanFile(
                    context, arrayOf(destination.absolutePath), null, null
                )
                onCopied()
            } catch (e: Exception) {
                // Skip this one file, keep going with the rest.
            }
        }
    }
}
