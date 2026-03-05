package com.iptvplayer.tv.ui.browse

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.iptvplayer.tv.R
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class CategoryBrowseActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_category_browse)

        if (savedInstanceState == null) {
            val categoryId = intent.getStringExtra(EXTRA_CATEGORY_ID) ?: "all"
            val categoryName = intent.getStringExtra(EXTRA_CATEGORY_NAME) ?: ""
            val contentType = intent.getStringExtra(EXTRA_CONTENT_TYPE) ?: "LIVE"

            val fragment = CategoryBrowseFragment.newInstance(categoryId, categoryName, contentType)
            supportFragmentManager.beginTransaction()
                .replace(R.id.category_browse_fragment, fragment)
                .commitNow()
        }
    }

    companion object {
        const val EXTRA_CATEGORY_ID = "category_id"
        const val EXTRA_CATEGORY_NAME = "category_name"
        const val EXTRA_CONTENT_TYPE = "content_type"
    }
}
