package com.iptvplayer.tv.ui.browse

import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.BaseCardView
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import coil.load
import coil.transform.RoundedCornersTransformation
import com.iptvplayer.tv.R
import com.iptvplayer.tv.data.cache.FavoritesCache
import com.iptvplayer.tv.data.model.ContentType
import com.iptvplayer.tv.data.model.LiveStream
import com.iptvplayer.tv.data.model.Series
import com.iptvplayer.tv.data.model.VodStream

class CardPresenter(
    private val cardWidth: Int = DEFAULT_CARD_WIDTH,
    private val cardHeight: Int = DEFAULT_CARD_HEIGHT,
    private val posterHeight: Int = DEFAULT_POSTER_HEIGHT
) : Presenter() {

    private var defaultCardImage: Drawable? = null
    private var favoriteIcon: Drawable? = null
    private var selectedBackgroundColor: Int = 0
    private var defaultBackgroundColor: Int = 0

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        defaultBackgroundColor = ContextCompat.getColor(parent.context, R.color.card_background)
        selectedBackgroundColor = ContextCompat.getColor(parent.context, R.color.primary)
        defaultCardImage = ContextCompat.getDrawable(parent.context, R.drawable.default_card)
        favoriteIcon = ContextCompat.getDrawable(parent.context, R.drawable.ic_favorite)

        val cardView = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(cardWidth, posterHeight)
            setMainImageScaleType(ImageView.ScaleType.CENTER_CROP)
            // Always show title + subtitle below the image
            setInfoVisibility(BaseCardView.CARD_REGION_VISIBLE_ALWAYS)
            // Orange border on focus
            foreground = ContextCompat.getDrawable(parent.context, R.drawable.card_focus_foreground)
        }

        // Allow title to wrap to 2 lines instead of truncating
        val titleId = parent.context.resources.getIdentifier("title_text", "id", parent.context.packageName)
        if (titleId != 0) {
            cardView.findViewById<TextView>(titleId)?.apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
        }

        updateCardBackgroundColor(cardView, false)
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val cardView = viewHolder.view as ImageCardView

        when (item) {
            is LiveStream -> bindLiveStream(cardView, item)
            is VodStream -> bindVodStream(cardView, item)
            is Series -> bindSeries(cardView, item)
        }

        // Focus: zoom + color change
        cardView.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) 1.08f else 1.0f
            v.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
            updateCardBackgroundColor(cardView, hasFocus)
        }
    }

    private fun bindLiveStream(cardView: ImageCardView, stream: LiveStream) {
        cardView.titleText = stream.name
        cardView.contentText = "TV en direct"
        cardView.setMainImageDimensions(cardWidth, cardHeight)

        loadImage(cardView, stream.streamIcon, rating = null)

        if (FavoritesCache.isFavorite(ContentType.LIVE, stream.streamId)) {
            cardView.badgeImage = favoriteIcon
        } else {
            cardView.badgeImage = null
        }
    }

    private fun bindVodStream(cardView: ImageCardView, vod: VodStream) {
        cardView.titleText = vod.name
        cardView.contentText = "Film"
        cardView.setMainImageDimensions(cardWidth, posterHeight)

        loadImage(cardView, vod.streamIcon, rating = vod.rating)

        if (FavoritesCache.isFavorite(ContentType.VOD, vod.streamId)) {
            cardView.badgeImage = favoriteIcon
        } else {
            cardView.badgeImage = null
        }
    }

    private fun bindSeries(cardView: ImageCardView, series: Series) {
        cardView.titleText = series.name
        cardView.contentText = series.genre ?: "Série"
        cardView.setMainImageDimensions(cardWidth, posterHeight)

        loadImage(cardView, series.cover, rating = series.rating)

        if (FavoritesCache.isFavorite(ContentType.SERIES, series.seriesId)) {
            cardView.badgeImage = favoriteIcon
        } else {
            cardView.badgeImage = null
        }
    }

    private fun loadImage(cardView: ImageCardView, url: String?, rating: String?) {
        if (!url.isNullOrEmpty()) {
            val transformations = buildList {
                add(RoundedCornersTransformation(12f))
                // Add rating overlay for VOD/Series with valid rating
                val ratingValue = rating?.toDoubleOrNull()
                if (ratingValue != null && ratingValue > 0) {
                    add(RatingOverlayTransformation(rating))
                }
            }
            cardView.mainImageView.load(url) {
                crossfade(true)
                placeholder(R.drawable.default_card)
                error(R.drawable.default_card)
                transformations(transformations)
            }
        } else {
            cardView.mainImage = defaultCardImage
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        cardView.badgeImage = null
        cardView.mainImage = null
    }

    private fun updateCardBackgroundColor(view: ImageCardView, selected: Boolean) {
        val color = if (selected) selectedBackgroundColor else defaultBackgroundColor
        view.setInfoAreaBackgroundColor(color)
    }

    companion object {
        const val DEFAULT_CARD_WIDTH = 260
        const val DEFAULT_CARD_HEIGHT = 195
        const val DEFAULT_POSTER_HEIGHT = 390

        // Grid sizes for VOD / Series (3 columns)
        const val GRID_CARD_WIDTH = 300
        const val GRID_CARD_HEIGHT = 170
        const val GRID_POSTER_HEIGHT = 420

        // Grid sizes for live channels (3 columns)
        const val GRID_LIVE_WIDTH = 400
        const val GRID_LIVE_HEIGHT = 260
    }
}
