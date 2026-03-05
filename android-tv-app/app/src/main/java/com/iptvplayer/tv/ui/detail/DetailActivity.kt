package com.iptvplayer.tv.ui.detail

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.iptvplayer.tv.R
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class DetailActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.detail_fragment, DetailFragment())
                .commitNow()
        }
    }

    companion object {
        const val EXTRA_CONTENT_TYPE = "content_type"
        const val EXTRA_CONTENT_ID = "content_id"
        const val EXTRA_CONTENT_NAME = "content_name"
        const val EXTRA_CONTENT_IMAGE = "content_image"
        const val EXTRA_CONTENT_EXTENSION = "content_extension"
    }
}
