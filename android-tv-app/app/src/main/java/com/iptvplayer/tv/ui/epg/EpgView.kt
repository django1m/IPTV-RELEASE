package com.iptvplayer.tv.ui.epg

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.LruCache
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import coil.ImageLoader
import coil.request.ImageRequest
import coil.size.Size
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.iptvplayer.tv.R
import com.iptvplayer.tv.data.cache.FavoritesCache
import com.iptvplayer.tv.data.model.ContentType
import com.iptvplayer.tv.data.model.EpgChannel
import com.iptvplayer.tv.data.model.EpgProgram
import com.iptvplayer.tv.data.model.LiveStream
import java.text.SimpleDateFormat
import java.util.*

class EpgView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Dimensions (dp → px) ──
    private val density = resources.displayMetrics.density
    private val channelColWidth = (180 * density).toInt()
    private val rowHeight = (72 * density).toInt()
    private val timeRulerHeight = (44 * density).toInt()
    private val pxPerMinute = 4f * density // 4dp per minute
    private val pxPerSecond = pxPerMinute / 60f
    private val cellPadding = (6 * density).toInt()
    private val cellRadius = 8f * density
    private val cellGap = (2 * density).toInt()
    private val focusBorderWidth = 3f * density
    private val nowLineWidth = 2.5f * density
    private val logoSize = (40 * density).toInt()
    private val logoPadding = (8 * density).toInt()

    // ── Scroll state ──
    private var scrollXPx = 0f
    private var scrollYPx = 0f
    private var maxScrollX = 0f
    private var maxScrollY = 0f

    // ── Focus state ──
    var focusedChannelIndex = 0
        private set
    var focusedProgramIndex = 0
        private set

    // ── Data ──
    private var channels: List<EpgChannel> = emptyList()
    private var timeWindowStart: Long = 0L // epoch seconds
    private var timeWindowHours = 4 // visible hours window

    // ── Paints (all created once) ──
    private val bgPaint = Paint().apply { color = 0xFF1E293B.toInt() }
    private val channelBgPaint = Paint().apply { color = 0xFF111827.toInt() }
    private val channelBgAltPaint = Paint().apply { color = 0xFF0F172A.toInt() }
    private val rulerBgPaint = Paint().apply { color = 0xFF0F172A.toInt() }
    private val rulerTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9CA3AF.toInt(); textSize = 13f * density; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val cellPastPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1F2937.toInt() }
    private val cellCurrentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF334155.toInt() }
    private val cellFuturePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF283548.toInt() }
    private val cellFocusedBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x336366F1.toInt() }
    private val cellFocusBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6366F1.toInt(); style = Paint.Style.STROKE; strokeWidth = focusBorderWidth
    }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 14f * density; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val titlePastPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6B7280.toInt(); textSize = 14f * density; typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val titleFuturePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9CA3AF.toInt(); textSize = 14f * density; typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6B7280.toInt(); textSize = 11f * density
    }
    private val channelNamePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 13f * density; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val nowLinePaint = Paint().apply {
        color = 0xFF6366F1.toInt(); strokeWidth = nowLineWidth; style = Paint.Style.STROKE
    }
    private val nowDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6366F1.toInt() }
    private val dividerPaint = Paint().apply { color = 0x1AFFFFFF.toInt() }
    private val archivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6366F1.toInt(); textSize = 10f * density }
    private val rulerLinePaint = Paint().apply { color = 0x33FFFFFF.toInt(); strokeWidth = 1f * density }
    private val cornerBgPaint = Paint().apply { color = 0xFF0D1117.toInt() }
    private val logoPlaceholderBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF374151.toInt() }
    private val logoPlaceholderTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9CA3AF.toInt(); textSize = 16f * density; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    // ── Logo cache ──
    private val logoCache = LruCache<String, Bitmap>(60)
    private val logoLoadingSet = mutableSetOf<String>()
    private val imageLoader = ImageLoader.Builder(context).build()

    // ── Time format ──
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEEE d MMMM", Locale.FRENCH)

    // ── Channel focus mode (LEFT on first program → highlight channel) ──
    var isChannelFocusMode = false
        private set

    // ── Heart icon for favorites ──
    private val heartSize = (18 * density).toInt()
    private val heartBitmap: Bitmap = ContextCompat.getDrawable(context, R.drawable.ic_heart_filled)!!
        .toBitmap(heartSize, heartSize)

    // ── Long press tracking ──
    private var longPressHandled = false

    // ── Callbacks ──
    var onFocusChanged: ((channel: LiveStream, program: EpgProgram?) -> Unit)? = null
    var onProgramSelected: ((channel: LiveStream, program: EpgProgram?) -> Unit)? = null
    var onRequestMoreData: ((streamIds: List<Int>) -> Unit)? = null
    var onExitLeft: (() -> Unit)? = null
    var onChannelLongPress: ((channel: LiveStream) -> Unit)? = null

    // ── Now line refresh ──
    private val nowHandler = Handler(Looper.getMainLooper())
    private val nowRunnable = object : Runnable {
        override fun run() {
            invalidate()
            nowHandler.postDelayed(this, 60_000)
        }
    }

    // ── Temp objects (avoid allocation in onDraw) ──
    private val cellRect = RectF()
    private val clipRect = RectF()
    private val tempRect = RectF()

    init {
        isFocusable = true
        isFocusableInTouchMode = true

        // Default time window: start 1h before now
        val now = System.currentTimeMillis() / 1000
        timeWindowStart = now - 3600 // 1 hour before now

        nowHandler.postDelayed(nowRunnable, 60_000)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        nowHandler.removeCallbacks(nowRunnable)
    }

    // ── Public API ──

    fun setChannels(newChannels: List<EpgChannel>, resetScroll: Boolean = true) {
        channels = newChannels
        if (resetScroll) {
            focusedChannelIndex = 0
            focusedProgramIndex = 0
            scrollYPx = 0f
        } else {
            // Clamp focus to valid range
            focusedChannelIndex = focusedChannelIndex.coerceIn(0, (channels.size - 1).coerceAtLeast(0))
        }
        updateMaxScroll()
        invalidate()
        if (channels.isNotEmpty()) {
            notifyFocusChanged()
        }
    }

    fun updateChannelEpg(streamId: Int, programs: List<EpgProgram>) {
        val idx = channels.indexOfFirst { it.stream.streamId == streamId }
        if (idx >= 0) {
            val updated = channels.toMutableList()
            updated[idx] = channels[idx].copy(programs = programs)
            channels = updated
            invalidate()
            if (idx == focusedChannelIndex) notifyFocusChanged()
        }
    }

    fun jumpToNow() {
        val now = System.currentTimeMillis() / 1000
        val targetScrollX = ((now - timeWindowStart) * pxPerSecond - (width - channelColWidth) / 3f).coerceAtLeast(0f)
        animateScrollX(targetScrollX)

        // Also find the current program for focused channel
        if (channels.isNotEmpty()) {
            val channel = channels[focusedChannelIndex]
            val progIdx = channel.programs.indexOfFirst { it.isCurrentlyAiring }
            if (progIdx >= 0) {
                focusedProgramIndex = progIdx
                notifyFocusChanged()
            }
        }
    }

    fun getVisibleChannelRange(): IntRange {
        val first = (scrollYPx / rowHeight).toInt().coerceAtLeast(0)
        val last = ((scrollYPx + height - timeRulerHeight) / rowHeight).toInt().coerceAtMost(channels.size - 1)
        return first..last
    }

    // ── Drawing ──

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val viewW = width.toFloat()
        val viewH = height.toFloat()

        // Background
        canvas.drawRect(0f, 0f, viewW, viewH, bgPaint)

        // Save & clip for program area (right of channel col, below ruler)
        canvas.save()
        canvas.clipRect(channelColWidth.toFloat(), timeRulerHeight.toFloat(), viewW, viewH)
        drawProgramCells(canvas)
        drawNowLine(canvas, viewH)
        canvas.restore()

        // Draw channel sidebar (over programs, fixed left)
        canvas.save()
        canvas.clipRect(0f, timeRulerHeight.toFloat(), channelColWidth.toFloat(), viewH)
        drawChannelSidebar(canvas)
        canvas.restore()

        // Draw time ruler (over everything, fixed top)
        canvas.save()
        canvas.clipRect(channelColWidth.toFloat(), 0f, viewW, timeRulerHeight.toFloat())
        drawTimeRuler(canvas)
        canvas.restore()

        // Corner piece (top-left, covers channel col + ruler overlap)
        canvas.drawRect(0f, 0f, channelColWidth.toFloat(), timeRulerHeight.toFloat(), cornerBgPaint)
        // Draw current date in corner
        val dateStr = dateFormat.format(Date())
        val dateWidth = rulerTextPaint.measureText(dateStr)
        canvas.drawText(dateStr, (channelColWidth - dateWidth) / 2f, timeRulerHeight / 2f + rulerTextPaint.textSize / 3f, rulerTextPaint)

        // Draw now line over everything (including ruler)
        drawNowLineTop(canvas)
    }

    private fun drawTimeRuler(canvas: Canvas) {
        val rulerH = timeRulerHeight.toFloat()
        canvas.drawRect(channelColWidth.toFloat(), 0f, width.toFloat(), rulerH, rulerBgPaint)

        // Draw 30-minute intervals
        val startSec = timeWindowStart
        // Round to next 30min
        val firstSlot = ((startSec + 1799) / 1800) * 1800

        var t = firstSlot
        val endSec = timeWindowStart + (24 * 3600) // up to 24h range
        while (t < endSec) {
            val x = channelColWidth + ((t - startSec) * pxPerSecond) - scrollXPx
            if (x > width) break
            if (x >= channelColWidth) {
                // Vertical tick
                canvas.drawLine(x, rulerH * 0.6f, x, rulerH, rulerLinePaint)
                // Time text
                val label = timeFormat.format(Date(t * 1000))
                canvas.drawText(label, x + 4 * density, rulerH * 0.45f, rulerTextPaint)
            }
            t += 1800 // 30 minutes
        }

        // Bottom divider
        canvas.drawLine(channelColWidth.toFloat(), rulerH - 1, width.toFloat(), rulerH - 1, dividerPaint)
    }

    private fun drawChannelSidebar(canvas: Canvas) {
        val colW = channelColWidth.toFloat()

        val firstVisible = (scrollYPx / rowHeight).toInt().coerceAtLeast(0)
        val lastVisible = ((scrollYPx + height - timeRulerHeight) / rowHeight).toInt()
            .coerceAtMost(channels.size - 1)

        for (i in firstVisible..lastVisible) {
            val channel = channels[i]
            val y = timeRulerHeight + (i * rowHeight) - scrollYPx
            val isFocused = i == focusedChannelIndex

            // Background
            val paint = if (isFocused) cellFocusedBgPaint else if (i % 2 == 0) channelBgPaint else channelBgAltPaint
            canvas.drawRect(0f, y, colW, y + rowHeight, paint)

            if (isFocused && isChannelFocusMode) {
                // Prominent amber border in channel focus mode
                val borderRect = RectF(3 * density, y + 2 * density, colW - 2 * density, y + rowHeight - 2 * density)
                cellFocusBorderPaint.style = Paint.Style.STROKE
                canvas.drawRoundRect(borderRect, cellRadius, cellRadius, cellFocusBorderPaint)
            } else if (isFocused) {
                canvas.drawRect(colW - 3 * density, y, colW, y + rowHeight, cellFocusBorderPaint.apply { style = Paint.Style.FILL })
            }

            // Logo
            val logoUrl = channel.stream.streamIcon
            val logoX = logoPadding.toFloat()
            val logoY = y + (rowHeight - logoSize) / 2f
            val logoRect = RectF(logoX, logoY, logoX + logoSize, logoY + logoSize)

            var logoDrawn = false
            if (!logoUrl.isNullOrEmpty()) {
                val bmp = logoCache.get(logoUrl)
                if (bmp != null) {
                    canvas.drawBitmap(bmp, null, logoRect, null)
                    logoDrawn = true
                } else {
                    loadLogo(logoUrl)
                }
            }

            // Placeholder: circle with first letter when no logo
            if (!logoDrawn) {
                val cx = logoRect.centerX()
                val cy = logoRect.centerY()
                val radius = logoSize / 2f
                canvas.drawCircle(cx, cy, radius, logoPlaceholderBgPaint)
                val letter = (channel.stream.name.firstOrNull() ?: '?').uppercaseChar().toString()
                val textW = logoPlaceholderTextPaint.measureText(letter)
                canvas.drawText(letter, cx - textW / 2f, cy + logoPlaceholderTextPaint.textSize / 3f, logoPlaceholderTextPaint)
            }

            // Heart icon for favorites
            val isFav = FavoritesCache.isFavorite(ContentType.LIVE, channel.stream.streamId)
            if (isFav) {
                val hx = colW - heartSize - 6 * density
                val hy = y + 4 * density
                canvas.drawBitmap(heartBitmap, hx, hy, null)
            }

            // Channel name
            val nameX = (logoPadding + logoSize + logoPadding).toFloat()
            val nameMaxW = colW - nameX - (if (isFav) heartSize + 10 * density else 8 * density)
            val name = TextUtils.ellipsize(channel.stream.name, channelNamePaint, nameMaxW, TextUtils.TruncateAt.END).toString()
            val nameY = y + rowHeight / 2f + channelNamePaint.textSize / 3f
            canvas.drawText(name, nameX, nameY, channelNamePaint)

            // Divider
            canvas.drawLine(0f, y + rowHeight, colW, y + rowHeight, dividerPaint)
        }
    }

    private fun drawProgramCells(canvas: Canvas) {
        val now = System.currentTimeMillis() / 1000
        val firstVisible = (scrollYPx / rowHeight).toInt().coerceAtLeast(0)
        val lastVisible = ((scrollYPx + height - timeRulerHeight) / rowHeight).toInt()
            .coerceAtMost(channels.size - 1)

        val visibleStartSec = timeWindowStart + (scrollXPx / pxPerSecond).toLong()
        val visibleEndSec = visibleStartSec + ((width - channelColWidth) / pxPerSecond).toLong()

        for (i in firstVisible..lastVisible) {
            val channel = channels[i]
            val rowTop = timeRulerHeight + (i * rowHeight) - scrollYPx

            if (channel.programs.isEmpty()) {
                // Draw empty row placeholder
                cellRect.set(
                    channelColWidth + cellGap.toFloat(),
                    rowTop + cellGap,
                    width.toFloat() - cellGap,
                    rowTop + rowHeight - cellGap
                )
                canvas.drawRoundRect(cellRect, cellRadius, cellRadius, cellPastPaint)
                val emptyText = "Pas de programme"
                canvas.drawText(emptyText, cellRect.left + cellPadding * 2, cellRect.centerY() + timePaint.textSize / 3, timePaint)
                continue
            }

            for ((pIdx, program) in channel.programs.withIndex()) {
                if (program.stopTimestampLong < visibleStartSec - 1800) continue
                if (program.startTimestampLong > visibleEndSec + 1800) break

                val isFocusedCell = !isChannelFocusMode && i == focusedChannelIndex && pIdx == focusedProgramIndex

                val cellLeft = channelColWidth + ((program.startTimestampLong - timeWindowStart) * pxPerSecond) - scrollXPx + cellGap
                val cellRight = channelColWidth + ((program.stopTimestampLong - timeWindowStart) * pxPerSecond) - scrollXPx - cellGap
                val cellTop = rowTop + cellGap
                val cellBottom = rowTop + rowHeight - cellGap

                // Clamp left side to channel column
                val drawLeft = cellLeft.coerceAtLeast(channelColWidth.toFloat() + cellGap)
                if (cellRight <= channelColWidth) continue

                cellRect.set(drawLeft, cellTop, cellRight, cellBottom)

                // Choose paint based on state
                val isPast = program.stopTimestampLong < now
                val isCurrent = program.isCurrentlyAiring
                val cellBgPaint = when {
                    isFocusedCell -> cellFocusedBgPaint
                    isCurrent -> cellCurrentPaint
                    isPast -> cellPastPaint
                    else -> cellFuturePaint
                }

                canvas.drawRoundRect(cellRect, cellRadius, cellRadius, cellBgPaint)

                if (isFocusedCell) {
                    cellFocusBorderPaint.style = Paint.Style.STROKE
                    canvas.drawRoundRect(cellRect, cellRadius, cellRadius, cellFocusBorderPaint)
                }

                // Title
                val textPaint = when {
                    isFocusedCell -> titlePaint
                    isCurrent -> titlePaint
                    isPast -> titlePastPaint
                    else -> titleFuturePaint
                }
                val titleMaxW = (cellRect.width() - cellPadding * 3).coerceAtLeast(0f)
                if (titleMaxW > 20 * density) {
                    val title = TextUtils.ellipsize(program.decodedTitle, textPaint, titleMaxW, TextUtils.TruncateAt.END).toString()
                    canvas.drawText(title, cellRect.left + cellPadding * 1.5f, cellRect.top + cellPadding + textPaint.textSize, textPaint)
                }

                // Time
                if (cellRect.width() > 60 * density) {
                    val startStr = if (program.startTimestampLong > 0) timeFormat.format(Date(program.startTimestampLong * 1000)) else ""
                    val endStr = if (program.stopTimestampLong > 0) timeFormat.format(Date(program.stopTimestampLong * 1000)) else ""
                    val timeStr = "$startStr - $endStr"
                    canvas.drawText(timeStr, cellRect.left + cellPadding * 1.5f, cellRect.bottom - cellPadding - 2 * density, timePaint)
                }

                // Archive indicator
                if (isPast && program.hasArchive == 1 && cellRect.width() > 40 * density) {
                    val archX = cellRect.right - cellPadding * 2 - 8 * density
                    val archY = cellRect.top + cellPadding + 10 * density
                    canvas.drawCircle(archX, archY, 4 * density, archivePaint)
                }
            }

            // Row divider
            canvas.drawLine(channelColWidth.toFloat(), rowTop + rowHeight, width.toFloat(), rowTop + rowHeight, dividerPaint)
        }
    }

    private fun drawNowLine(canvas: Canvas, viewH: Float) {
        val now = System.currentTimeMillis() / 1000
        val x = channelColWidth + ((now - timeWindowStart) * pxPerSecond) - scrollXPx
        if (x >= channelColWidth && x <= width) {
            canvas.drawLine(x, timeRulerHeight.toFloat(), x, viewH, nowLinePaint)
        }
    }

    private fun drawNowLineTop(canvas: Canvas) {
        val now = System.currentTimeMillis() / 1000
        val x = channelColWidth + ((now - timeWindowStart) * pxPerSecond) - scrollXPx
        if (x >= channelColWidth && x <= width) {
            // Draw a small triangle/dot at the top of the now line
            canvas.drawCircle(x, timeRulerHeight.toFloat(), 5 * density, nowDotPaint)
        }
    }

    // ── Logo loading ──

    private fun loadLogo(url: String) {
        if (logoLoadingSet.contains(url)) return
        logoLoadingSet.add(url)

        val request = ImageRequest.Builder(context)
            .data(url)
            .size(Size(logoSize, logoSize))
            .target(
                onSuccess = { drawable ->
                    val bmp = Bitmap.createBitmap(logoSize, logoSize, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    drawable.setBounds(0, 0, logoSize, logoSize)
                    drawable.draw(canvas)
                    logoCache.put(url, bmp)
                    logoLoadingSet.remove(url)
                    postInvalidate()
                },
                onError = {
                    logoLoadingSet.remove(url)
                }
            )
            .build()
        imageLoader.enqueue(request)
    }

    // ── D-pad navigation ──

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (focusedChannelIndex > 0) {
                    focusedChannelIndex--
                    if (!isChannelFocusMode) adjustProgramFocusForChannel()
                    ensureFocusedChannelVisible()
                    notifyFocusChanged()
                    triggerPrefetchIfNeeded()
                    invalidate()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (focusedChannelIndex < channels.size - 1) {
                    focusedChannelIndex++
                    if (!isChannelFocusMode) adjustProgramFocusForChannel()
                    ensureFocusedChannelVisible()
                    notifyFocusChanged()
                    triggerPrefetchIfNeeded()
                    invalidate()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isChannelFocusMode) {
                    // Already on channel sidebar, request category list
                    onExitLeft?.invoke()
                    return true
                }
                if (moveFocusLeft()) {
                    ensureFocusedProgramVisible()
                    notifyFocusChanged()
                    invalidate()
                    return true
                }
                // At first program, enter channel focus mode
                isChannelFocusMode = true
                notifyFocusChanged()
                invalidate()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isChannelFocusMode) {
                    // Exit channel focus mode, go to programs
                    isChannelFocusMode = false
                    adjustProgramFocusForChannel()
                    ensureFocusedProgramVisible()
                    notifyFocusChanged()
                    invalidate()
                    return true
                }
                moveFocusRight()
                ensureFocusedProgramVisible()
                notifyFocusChanged()
                invalidate()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (channels.isNotEmpty()) {
                    if (isChannelFocusMode) {
                        // Track for long press detection
                        if (event?.repeatCount == 0) {
                            longPressHandled = false
                            event.startTracking()
                            return true
                        }
                    } else {
                        val channel = channels[focusedChannelIndex]
                        val program = channel.programs.getOrNull(focusedProgramIndex)
                        onProgramSelected?.invoke(channel.stream, program)
                        return true
                    }
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && isChannelFocusMode) {
            if (channels.isNotEmpty()) {
                longPressHandled = true
                val channel = channels[focusedChannelIndex]
                onChannelLongPress?.invoke(channel.stream)
                invalidate()
                return true
            }
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && isChannelFocusMode) {
            if (!longPressHandled && event?.isTracking == true && !event.isCanceled) {
                // Short press in channel mode → launch live
                if (channels.isNotEmpty()) {
                    val channel = channels[focusedChannelIndex]
                    onProgramSelected?.invoke(channel.stream, null)
                }
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    fun exitChannelFocusMode() {
        isChannelFocusMode = false
        adjustProgramFocusForChannel()
        ensureFocusedProgramVisible()
        notifyFocusChanged()
        invalidate()
    }

    private fun moveFocusLeft(): Boolean {
        if (channels.isEmpty()) return false
        val channel = channels[focusedChannelIndex]
        if (channel.programs.isEmpty()) {
            return false // No programs, let focus go to category sidebar
        }
        if (focusedProgramIndex > 0) {
            focusedProgramIndex--
            return true
        }
        return false // At first program, let focus go to category sidebar
    }

    private fun moveFocusRight() {
        if (channels.isEmpty()) return
        val channel = channels[focusedChannelIndex]
        if (channel.programs.isEmpty()) {
            animateScrollX((scrollXPx + 30 * pxPerMinute).coerceAtMost(maxScrollX))
            return
        }
        if (focusedProgramIndex < channel.programs.size - 1) {
            focusedProgramIndex++
        } else {
            animateScrollX((scrollXPx + 30 * pxPerMinute).coerceAtMost(maxScrollX))
        }
    }

    private fun adjustProgramFocusForChannel() {
        if (channels.isEmpty()) return
        val channel = channels[focusedChannelIndex]
        if (channel.programs.isEmpty()) {
            focusedProgramIndex = 0
            return
        }

        // Find program at the center of the visible time
        val visibleCenterSec = timeWindowStart + ((scrollXPx + (width - channelColWidth) / 2f) / pxPerSecond).toLong()
        var bestIdx = 0
        var bestDist = Long.MAX_VALUE
        for ((idx, prog) in channel.programs.withIndex()) {
            val progCenter = (prog.startTimestampLong + prog.stopTimestampLong) / 2
            val dist = kotlin.math.abs(progCenter - visibleCenterSec)
            if (dist < bestDist) {
                bestDist = dist
                bestIdx = idx
            }
        }
        focusedProgramIndex = bestIdx
    }

    private fun ensureFocusedChannelVisible() {
        val targetTop = focusedChannelIndex * rowHeight.toFloat()
        val visibleTop = scrollYPx
        val visibleBottom = scrollYPx + (height - timeRulerHeight)

        val newScrollY = when {
            targetTop < visibleTop -> targetTop
            targetTop + rowHeight > visibleBottom -> targetTop + rowHeight - (visibleBottom - visibleTop) + rowHeight
            else -> return
        }
        animateScrollY(newScrollY.coerceIn(0f, maxScrollY))
    }

    private fun ensureFocusedProgramVisible() {
        if (channels.isEmpty()) return
        val channel = channels[focusedChannelIndex]
        val program = channel.programs.getOrNull(focusedProgramIndex) ?: return

        val cellLeft = ((program.startTimestampLong - timeWindowStart) * pxPerSecond)
        val cellRight = ((program.stopTimestampLong - timeWindowStart) * pxPerSecond)
        val visibleLeft = scrollXPx
        val visibleRight = scrollXPx + (width - channelColWidth)

        val newScrollX = when {
            cellLeft < visibleLeft -> cellLeft - 20 * density
            cellRight > visibleRight -> cellRight - (visibleRight - visibleLeft) + 20 * density
            else -> return
        }
        animateScrollX(newScrollX.coerceIn(0f, maxScrollX))
    }

    // ── Scroll animations ──

    private var scrollXAnimator: ValueAnimator? = null
    private var scrollYAnimator: ValueAnimator? = null

    private fun animateScrollX(target: Float) {
        scrollXAnimator?.cancel()
        scrollXAnimator = ValueAnimator.ofFloat(scrollXPx, target).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                scrollXPx = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun animateScrollY(target: Float) {
        scrollYAnimator?.cancel()
        scrollYAnimator = ValueAnimator.ofFloat(scrollYPx, target).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                scrollYPx = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun updateMaxScroll() {
        maxScrollY = (channels.size * rowHeight - (height - timeRulerHeight)).toFloat().coerceAtLeast(0f)
        // 24h of content
        maxScrollX = (24 * 60 * pxPerMinute - (width - channelColWidth)).coerceAtLeast(0f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateMaxScroll()
    }

    // ── Helpers ──

    private fun notifyFocusChanged() {
        if (channels.isEmpty()) return
        val channel = channels.getOrNull(focusedChannelIndex) ?: return
        val program = channel.programs.getOrNull(focusedProgramIndex)
        onFocusChanged?.invoke(channel.stream, program)
    }

    private fun triggerPrefetchIfNeeded() {
        if (channels.isEmpty()) return
        val visibleEnd = ((scrollYPx + height - timeRulerHeight) / rowHeight).toInt()
        val threshold = 3

        if (focusedChannelIndex >= visibleEnd - threshold || focusedChannelIndex >= channels.size - threshold) {
            val startIdx = (focusedChannelIndex + 1).coerceAtMost(channels.size - 1)
            val endIdx = (startIdx + 10).coerceAtMost(channels.size)
            val streamIds = channels.subList(startIdx, endIdx)
                .filter { it.programs.isEmpty() }
                .map { it.stream.streamId }
            if (streamIds.isNotEmpty()) {
                onRequestMoreData?.invoke(streamIds)
            }
        }
    }

    fun getFocusedChannel(): LiveStream? = channels.getOrNull(focusedChannelIndex)?.stream
    fun getFocusedProgram(): EpgProgram? = channels.getOrNull(focusedChannelIndex)?.programs?.getOrNull(focusedProgramIndex)
}
