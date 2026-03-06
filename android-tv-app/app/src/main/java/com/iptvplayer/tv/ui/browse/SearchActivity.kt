package com.iptvplayer.tv.ui.browse

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.fragment.app.FragmentActivity
import com.iptvplayer.tv.R
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SearchActivity : FragmentActivity() {

    private var searchFragment: SearchFragment? = null

    // Long press detection for favorites
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var isLongPressTriggered = false
    private val longPressDelay = 800L
    private val longPressRunnable = Runnable {
        isLongPressTriggered = true
        handleLongPress()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        if (savedInstanceState == null) {
            searchFragment = SearchFragment()
            supportFragmentManager.beginTransaction()
                .replace(R.id.search_fragment, searchFragment!!)
                .commitNow()
        } else {
            searchFragment = supportFragmentManager.findFragmentById(R.id.search_fragment) as? SearchFragment
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode

        // Handle long press for OK/Center button to add favorites
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        isLongPressTriggered = false
                        longPressHandler.postDelayed(longPressRunnable, longPressDelay)
                    }
                    // Don't consume - let it propagate for normal handling
                }
                KeyEvent.ACTION_UP -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    if (isLongPressTriggered) {
                        // Long press was handled, consume the event
                        isLongPressTriggered = false
                        return true
                    }
                    // Short press - let it through normally
                }
            }
        }

        return super.dispatchKeyEvent(event)
    }

    private fun handleLongPress() {
        searchFragment?.onLongPress()
    }
}
