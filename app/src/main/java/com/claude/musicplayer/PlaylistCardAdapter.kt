package com.claude.musicplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** One playlist shown as a card: its cover art (if any), name and song count. */
data class PlaylistCardInfo(
    val name: String,
    val songs: List<Song>,
    /** Album art URI that is known to decode, or null for the gradient placeholder. */
    val coverUri: String?
)

class PlaylistCardAdapter(
    private val onOpen: (PlaylistCardInfo) -> Unit,
    private val onPlay: (PlaylistCardInfo) -> Unit,
    private val onMore: (PlaylistCardInfo, View) -> Unit
) : RecyclerView.Adapter<PlaylistCardAdapter.Holder>() {

    private var items: List<PlaylistCardInfo> = emptyList()

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ImageView = view.findViewById(R.id.playlistCover)
        val placeholderIcon: ImageView = view.findViewById(R.id.playlistPlaceholderIcon)
        val name: TextView = view.findViewById(R.id.playlistName)
        val count: TextView = view.findViewById(R.id.playlistCount)
        val play: ImageButton = view.findViewById(R.id.playlistPlay)
        val playCard: View = view.findViewById(R.id.playlistPlayCard)
        val more: ImageButton = view.findViewById(R.id.playlistMore)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_playlist_card, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context
        holder.name.text = item.name
        holder.count.text = when (item.songs.size) {
            1 -> "1 song"
            else -> "${item.songs.size} songs"
        }

        val bitmap = MusicRepository.loadAlbumArt(ctx, item.coverUri, 400)
        if (bitmap != null) {
            holder.cover.setImageBitmap(bitmap)
            holder.placeholderIcon.visibility = View.GONE
        } else {
            holder.cover.setImageDrawable(null)
            holder.placeholderIcon.visibility = View.VISIBLE
        }

        holder.playCard.visibility = if (item.songs.isEmpty()) View.GONE else View.VISIBLE
        holder.itemView.setOnClickListener { onOpen(item) }
        holder.play.setOnClickListener { onPlay(item) }
        holder.more.setOnClickListener { onMore(item, it) }

        // Cards rise into place with a slight 3D tilt the first time they appear.
        if (holder.itemView.tag != item.name) {
            holder.itemView.tag = item.name
            holder.itemView.cameraDistance = 8000f * ctx.resources.displayMetrics.density
            holder.itemView.rotationX = 25f
            holder.itemView.translationY = 40f * ctx.resources.displayMetrics.density
            holder.itemView.alpha = 0f
            holder.itemView.animate()
                .rotationX(0f).translationY(0f).alpha(1f)
                .setStartDelay((position % 6) * 50L)
                .setDuration(420)
                .start()
        }
    }

    override fun getItemCount(): Int = items.size

    fun submit(newItems: List<PlaylistCardInfo>) {
        items = newItems
        notifyDataSetChanged()
    }
}
