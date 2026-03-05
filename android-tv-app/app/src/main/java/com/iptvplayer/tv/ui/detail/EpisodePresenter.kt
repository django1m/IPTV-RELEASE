package com.iptvplayer.tv.ui.detail

import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import coil.load
import coil.transform.RoundedCornersTransformation
import com.iptvplayer.tv.R
import com.iptvplayer.tv.data.model.Episode

class EpisodePresenter : Presenter() {

    private var selectedBackgroundColor: Int = 0
    private var defaultBackgroundColor: Int = 0

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        defaultBackgroundColor = ContextCompat.getColor(parent.context, R.color.card_background)
        selectedBackgroundColor = ContextCompat.getColor(parent.context, R.color.primary)

        val cardView = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)
            setMainImageScaleType(ImageView.ScaleType.CENTER_CROP)
        }

        updateCardBackgroundColor(cardView, false)
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val cardView = viewHolder.view as ImageCardView
        val episode = item as Episode

        cardView.titleText = episode.title
        cardView.contentText = "Épisode ${episode.episodeNum}"

        val imageUrl = episode.info?.movieImage
        if (!imageUrl.isNullOrEmpty()) {
            cardView.mainImageView.load(imageUrl) {
                crossfade(true)
                placeholder(R.drawable.default_card)
                error(R.drawable.default_card)
                transformations(RoundedCornersTransformation(8f))
            }
        } else {
            cardView.mainImage = ContextCompat.getDrawable(cardView.context, R.drawable.default_card)
        }

        cardView.setOnFocusChangeListener { _, hasFocus ->
            updateCardBackgroundColor(cardView, hasFocus)
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        cardView.mainImage = null
    }

    private fun updateCardBackgroundColor(view: ImageCardView, selected: Boolean) {
        val color = if (selected) selectedBackgroundColor else defaultBackgroundColor
        view.setInfoAreaBackgroundColor(color)
    }

    companion object {
        private const val CARD_WIDTH = 200
        private const val CARD_HEIGHT = 120
    }
}
