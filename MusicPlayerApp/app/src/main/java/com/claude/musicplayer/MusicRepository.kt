package com.claude.musicplayer

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import java.io.File

/**
 * Reads every audio track visible to MediaStore and exposes it either as a
 * flat list (Songs tab) or grouped by parent folder (Folders tab).
 */
object MusicRepository {

    fun loadAllSongs(context: Context): List<Song> {
        val songs = mutableListOf<Song>()

        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

        try {
            context.contentResolver.query(
                collection, projection, selection, null,
                "${MediaStore.Audio.Media.TITLE} ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val path = cursor.getString(dataCol) ?: continue
                    val albumId = cursor.getLong(albumIdCol)
                    val artUri = ContentUris.withAppendedId(
                        android.net.Uri.parse("content://media/external/audio/albumart"), albumId
                    ).toString()

                    songs.add(
                        Song(
                            id = id,
                            title = cursor.getString(titleCol) ?: File(path).nameWithoutExtension,
                            artist = cursor.getString(artistCol) ?: "Unknown Artist",
                            album = cursor.getString(albumCol) ?: "Unknown Album",
                            duration = cursor.getLong(durationCol),
                            path = path,
                            folder = File(path).parent ?: "/",
                            albumArtUri = artUri
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            // Queried before the READ_MEDIA_AUDIO / READ_EXTERNAL_STORAGE
            // permission was granted (e.g. right on first launch, before the
            // user has answered the permission dialog). Returning an empty
            // list here — instead of letting this crash — is what the
            // fragments' onResume() refresh recovers from once permission
            // is actually granted.
            CrashLogger.log(context, "loadAllSongs: permission not yet granted", e)
        } catch (e: Exception) {
            CrashLogger.log(context, "loadAllSongs failed", e)
        }
        return songs
    }

    /** Groups songs by their containing folder for the Folders tab. */
    fun loadFolders(context: Context): Map<String, List<Song>> {
        return loadAllSongs(context).groupBy { it.folder }
    }
}
