package com.iptvplayer.tv.ui.browse

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.leanback.widget.Presenter
import com.iptvplayer.tv.R
import com.iptvplayer.tv.data.cache.ContentCache
import com.iptvplayer.tv.data.model.Category
import com.iptvplayer.tv.data.model.ContentType

class CategoryCardPresenter(
    private val contentType: ContentType = ContentType.LIVE
) : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category_grid, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val category = item as Category
        val nameView = viewHolder.view.findViewById<TextView>(R.id.category_name)
        val countView = viewHolder.view.findViewById<TextView>(R.id.category_count)
        val accentView = viewHolder.view.findViewById<View>(R.id.category_accent)

        nameView.text = category.categoryName

        // Show item count if available
        val count = getItemCount(category)
        if (count > 0) {
            countView.visibility = View.VISIBLE
            countView.text = when (contentType) {
                ContentType.LIVE -> "$count chaînes"
                ContentType.VOD -> "$count films"
                ContentType.SERIES -> "$count séries"
            }
        } else {
            countView.visibility = View.GONE
        }

        // Focus animation with scale + accent bar
        val card = viewHolder.view.findViewById<ViewGroup>(R.id.category_card)
        card.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) 1.05f else 1.0f
            v.animate()
                .scaleX(scale)
                .scaleY(scale)
                .setDuration(200)
                .start()

            // Animate accent bar
            accentView.animate().alpha(if (hasFocus) 1.0f else 0.4f).setDuration(200).start()

            // Title stays white, count becomes brighter on focus
            val countColor = if (hasFocus) {
                v.context.getColor(R.color.text_secondary)
            } else {
                v.context.getColor(R.color.text_muted)
            }
            countView.setTextColor(countColor)
        }

        // Set initial accent bar alpha
        accentView.alpha = 0.4f
    }

    private fun getItemCount(category: Category): Int {
        return when (contentType) {
            ContentType.LIVE -> ContentCache.getLiveStreams(category.categoryId)?.size ?: 0
            ContentType.VOD -> ContentCache.getVodStreams(category.categoryId)?.size ?: 0
            ContentType.SERIES -> ContentCache.getSeries(category.categoryId)?.size ?: 0
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {}
}
