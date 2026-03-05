package com.iptvplayer.tv.ui.epg

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.iptvplayer.tv.R

class EpgCategoryAdapter(
    private val onCategorySelected: (CategoryItem) -> Unit
) : RecyclerView.Adapter<EpgCategoryAdapter.ViewHolder>() {

    data class CategoryItem(val id: String?, val name: String, val isFavorites: Boolean = false)

    private var items: List<CategoryItem> = emptyList()
    var selectedPosition = 0
        private set

    fun setItems(newItems: List<CategoryItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun selectPosition(position: Int) {
        val old = selectedPosition
        selectedPosition = position
        notifyItemChanged(old)
        notifyItemChanged(position)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_epg_category, parent, false) as TextView
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val isSelected = position == selectedPosition
        holder.bind(item, isSelected)

        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                val old = selectedPosition
                selectedPosition = pos
                notifyItemChanged(old)
                notifyItemChanged(pos)
                onCategorySelected(items[pos])
            }
        }
    }

    override fun getItemCount() = items.size

    class ViewHolder(private val textView: TextView) : RecyclerView.ViewHolder(textView) {
        fun bind(item: CategoryItem, isSelected: Boolean) {
            textView.text = item.name
            textView.isSelected = isSelected
            if (isSelected) {
                textView.setTextColor(0xFF6366F1.toInt())
                textView.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            } else {
                textView.setTextColor(0xFF9CA3AF.toInt())
                textView.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
        }
    }
}
