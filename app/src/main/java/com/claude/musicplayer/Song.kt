package com.claude.musicplayer

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val path: String,
    val folder: String,
    val albumArtUri: String?,
    /** Seconds since epoch when this file was added to the media store — used for Newest/Oldest sorting. */
    val dateAdded: Long
) : Parcelable
