package com.iptvplayer.tv.ui.browse

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.Presenter
import com.iptvplayer.tv.R
import com.iptvplayer.tv.data.model.ContentType

class CategoryPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val category = item as CategoryItem
        val view = viewHolder.view

        val iconView = view.findViewById<ImageView>(R.id.category_icon)
        val nameView = view.findViewById<TextView>(R.id.category_name)
        val typeView = view.findViewById<TextView>(R.id.category_type)

        nameView.text = category.name

        // Set type label and icon based on content type
        when (category.type) {
            ContentType.LIVE -> {
                typeView.text = "TV"
                iconView.setImageResource(category.iconRes ?: R.drawable.ic_live)
                iconView.setColorFilter(ContextCompat.getColor(view.context, R.color.error))
            }
            ContentType.VOD -> {
                typeView.text = "Film"
                iconView.setImageResource(category.iconRes ?: R.drawable.ic_movie)
                iconView.setColorFilter(ContextCompat.getColor(view.context, R.color.primary))
            }
            ContentType.SERIES -> {
                typeView.text = "Série"
                iconView.setImageResource(category.iconRes ?: R.drawable.ic_series)
                iconView.setColorFilter(ContextCompat.getColor(view.context, R.color.success))
            }
        }

        // Focus handling
        view.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) 1.05f else 1.0f
            v.animate().scaleX(scale).scaleY(scale).setDuration(150).start()

            val bgColor = if (hasFocus) R.color.primary else R.color.card_background
            v.setBackgroundColor(ContextCompat.getColor(v.context, bgColor))

            val textColor = if (hasFocus) R.color.background else R.color.text_primary
            nameView.setTextColor(ContextCompat.getColor(v.context, textColor))
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        // Nothing to unbind
    }
}
