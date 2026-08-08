package com.claude.musicplayer

object DuplicateFinder {

    data class DuplicateGroup(val key: String, val songs: List<Song>)

    /**
     * Groups songs that are almost certainly the same track appearing more
     * than once (e.g. copied into multiple folders, or downloaded twice).
     * Matched on normalized title + artist + exact duration, which is far
     * more reliable than filename (which varies a lot between sources).
     */
    fun findDuplicates(songs: List<Song>): List<DuplicateGroup> {
        return songs
            .groupBy { "${it.title.trim().lowercase()}|${it.artist.trim().lowercase()}|${it.duration}" }
            .filter { it.value.size > 1 }
            .map { DuplicateGroup(it.key, it.value.sortedBy { song -> song.path }) }
            .sortedBy { it.songs.first().title.lowercase() }
    }
}
