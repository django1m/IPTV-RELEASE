package com.iptvplayer.tv.ui.browse

import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.iptvplayer.tv.R

class SettingsPresenter : Presenter() {

    private var selectedBackgroundColor: Int = 0
    private var defaultBackgroundColor: Int = 0

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        defaultBackgroundColor = ContextCompat.getColor(parent.context, R.color.card_background)
        selectedBackgroundColor = ContextCompat.getColor(parent.context, R.color.primary)

        val cardView = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(CARD_SIZE, CARD_SIZE)
            setMainImageScaleType(ImageView.ScaleType.CENTER)
        }

        updateCardBackgroundColor(cardView, false)
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val cardView = viewHolder.view as ImageCardView
        val settingsItem = item as SettingsItem

        cardView.titleText = settingsItem.title
        cardView.contentText = ""
        cardView.mainImage = ContextCompat.getDrawable(cardView.context, settingsItem.iconRes)

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
        if (selected) {
            view.setBackgroundColor(selectedBackgroundColor)
        } else {
            view.setBackgroundColor(defaultBackgroundColor)
        }
    }

    companion object {
        private const val CARD_SIZE = 150
    }
}
