package com.iptvplayer.tv.ui.browse

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.Presenter
import coil.load
import coil.transform.RoundedCornersTransformation
import com.iptvplayer.tv.R
import com.iptvplayer.tv.data.model.WatchHistory
import com.iptvplayer.tv.data.model.ContentType

class ContinueWatchingPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_continue_watching, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val history = item as WatchHistory
        val view = viewHolder.view

        val imageView = view.findViewById<ImageView>(R.id.thumbnail)
        val titleView = view.findViewById<TextView>(R.id.title)
        val subtitleView = view.findViewById<TextView>(R.id.subtitle)
        val progressBar = view.findViewById<ProgressBar>(R.id.progress_bar)
        val typeIcon = view.findViewById<ImageView>(R.id.type_icon)

        titleView.text = history.name

        // Set subtitle based on content type
        when (history.contentType) {
            ContentType.LIVE -> {
                subtitleView.text = "TV en direct"
                typeIcon.setImageResource(R.drawable.ic_live)
            }
            ContentType.VOD -> {
                val remaining = ((1 - history.progressPercent) * history.totalDurationMs / 60000).toInt()
                subtitleView.text = "${remaining} min restantes"
                typeIcon.setImageResource(R.drawable.ic_movie)
            }
            ContentType.SERIES -> {
                if (history.seasonNumber != null && history.episodeNumber != null) {
                    subtitleView.text = "S${history.seasonNumber}E${history.episodeNumber}"
                } else {
                    subtitleView.text = "Série"
                }
                typeIcon.setImageResource(R.drawable.ic_series)
            }
        }

        // Progress bar
        progressBar.max = 100
        progressBar.progress = (history.progressPercent * 100).toInt()

        // Load image
        if (!history.imageUrl.isNullOrEmpty()) {
            imageView.load(history.imageUrl) {
                crossfade(true)
                placeholder(R.drawable.default_card)
                error(R.drawable.default_card)
                transformations(RoundedCornersTransformation(12f))
            }
        } else {
            imageView.setImageResource(R.drawable.default_card)
        }

        // Focus handling
        view.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) 1.08f else 1.0f
            v.animate().scaleX(scale).scaleY(scale).setDuration(150).start()

            val borderColor = if (hasFocus) R.color.primary else android.R.color.transparent
            v.setBackgroundColor(ContextCompat.getColor(v.context, borderColor))
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val view = viewHolder.view
        view.findViewById<ImageView>(R.id.thumbnail)?.setImageDrawable(null)
    }
}
