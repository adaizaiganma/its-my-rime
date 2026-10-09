package com.kingzcheung.xime

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ClipboardManager
import android.content.ClipDescription
import android.content.ClipData
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Process
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.InputType
import android.text.style.ReplacementSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.widget.FrameLayout
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.giphy.sdk.core.models.Media
import com.giphy.sdk.ui.Giphy
import com.giphy.sdk.ui.pagination.GPHContent
import com.giphy.sdk.ui.themes.GPHCustomTheme
import com.giphy.sdk.ui.themes.GPHTheme
import com.giphy.sdk.ui.views.GPHGridCallback
import com.giphy.sdk.ui.views.GiphyGridView
import com.kingzcheung.xime.rime.RimeCandidate
import com.kingzcheung.xime.rime.RimeProcessResult
import kotlin.math.abs
import kotlin.math.roundToInt
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

private const val BACKSPACE = 0xff08
private const val RETURN = 0xff0d
private const val RIME_CHAR_LEFT = 0xff96
private const val RIME_CHAR_RIGHT = 0xff98
private const val SHIFT_MASK = 1
private const val DELETE_REPEAT_INTERVAL_MS = 65L
private const val CARET_BLINK_INTERVAL_MS = 530L
private const val VOICE_RELEASE_DELAY_MS = 60_000L
private const val FLOATING_KEY = "floating_keyboard"
private const val FLOATING_X_KEY = "floating_x"
private const val FLOATING_Y_KEY = "floating_y"
private const val FLOATING_SCALE_KEY = "floating_scale"
private const val FLOATING_MIN_SCALE = 0.6f
private const val VOICE_MODEL_LABEL = "SenseVoice 中英辨識"
private const val VOICE_DOT_REST = 0.3f
private val VOICE_DOT_COLORS = listOf(0xFFF2A7A4, 0xFFF3B9A6, 0xFFF4C48E, 0xFFF5D3A6, 0xFFF3E7BF,
    0xFF9FE6D2, 0xFFA9DBC5, 0xFFA0C4F2, 0xFFAA93E0).map { it.toInt() }
private const val QUICK_PASTE_LIFETIME_MS = 10L * 60 * 1000
private const val KEY_CELL_HEIGHT_DP = 55
// Five Bopomofo rows share the height of four letter rows.
private const val ZHUYIN_CELL_HEIGHT_DP = 44
private val ZHUYIN_LABELS: Map<String, String> = "1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/-6347"
    .map(Char::toString)
    .zip("ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦˊˇˋ˙".map(Char::toString))
    .toMap()
private const val KEY_HORIZONTAL_INSET_DP = 1
private const val KEY_VERTICAL_INSET_DP = 2
private val PINYIN_COMMENT = Regex("［([A-Za-z0-9üÜ'\\s]+)］")
private val LETTER_SYMBOLS = mapOf(
    'a' to "@", 's' to "#", 'd' to "$", 'f' to "_", 'g' to "&",
    'h' to "-", 'j' to "+", 'k' to "(", 'l' to ")",
    'z' to "*", 'x' to "\"", 'c' to "'", 'v' to ":", 'b' to ";",
    'n' to "!", 'm' to "?"
)

private enum class ShiftState { OFF, ONCE, LOCKED }
private enum class EmojiTab { EMOJI, GIF, MYGO }
private enum class ClipboardTab { RECENT, PINNED }

private data class KeyOutcome(
    val state: RimeProcessResult? = null,
    val direct: String? = null,
    val delete: Boolean = false,
    val enter: Boolean = false
)

private data class SymbolChoices(val values: List<String>, val preferred: Int)

// Symbol pages: ten keys, nine keys, then seven keys between the page key and delete.
private val SYMBOL_PAGES = listOf(
    listOf(
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
        listOf("@", "#", "$", "%", "&", "-", "+", "(", ")"),
        listOf("*", "\"", ":", ";", "!", "?", "/"),
    ),
    listOf(
        listOf("「", "」", "『", "』", "【", "】", "《", "》", "、", "…"),
        listOf("~", "`", "|", "\\", "^", "_", "=", "<", ">"),
        listOf("•", "°", "×", "÷", "¥", "€", "£"),
    ),
)

private data class MygoCardViews(val card: LinearLayout, val preview: ImageView, val title: TextView)
private data class MygoRowViews(val cards: List<MygoCardViews>)

private data class KeyLayoutState(
    val symbols: Boolean,
    val shift: ShiftState,
    val ascii: Boolean,
    val fullWidth: Boolean,
    val dark: Boolean,
    val mediaEditing: Boolean,
    val mediaTab: EmojiTab,
    val symbolKey: SymbolKeyConfig,
    val zhuyin: Boolean,
    val symbolPage: Int
)

class IceInputMethodService : InputMethodService() {
    private var darkMode = false
    private val palette get() = UiTheme.palette(darkMode)
    private val ink get() = palette.ink
    private val muted get() = palette.muted
    private val accent get() = if (darkMode) palette.accent else palette.action
    private val actionColor get() = palette.action
    private val candidateInk get() = ink
    private val keyboardSurface get() = if (darkMode) palette.canvas else palette.surface
    private val keySurface get() = palette.key
    private val specialSurface get() = palette.inset
    private var root: LinearLayout? = null
    private var inputFrame: FrameLayout? = null
    private var topBar: FrameLayout? = null
    private var emptyToolbar: LinearLayout? = null
    private var toolbarLead: FrameLayout? = null
    private var toolbarMenu: HorizontalScrollView? = null
    private var toolbarMenuButton: ImageView? = null
    private var toolbarMenuOpen = false
    private var candidateBar: LinearLayout? = null
    private var caption: TextView? = null
    private var quickPasteButton: LinearLayout? = null
    private var quickPasteText: TextView? = null
    private var traditionalButton: TextView? = null
    private var punctuationWidthButton: TextView? = null
    private var clipboardButton: ImageView? = null
    private var candidateRow: LinearLayout? = null
    private var candidateScroll: HorizontalScrollView? = null
    private var moreButton: ImageView? = null
    private var rows: LinearLayout? = null
    private var holdPopup: LinearLayout? = null
    private var preeditPopup: PopupWindow? = null
    private var preeditPreview: TextView? = null
    private var preeditScroll: HorizontalScrollView? = null
    private var holdOptions: List<View> = emptyList()
    private var holdValues: List<String> = emptyList()
    private var expandedPanel: LinearLayout? = null
    private var expandedList: CandidateFlowLayout? = null
    private var clipboardPanel: LinearLayout? = null
    private var clipboardList: LinearLayout? = null
    private var clipboardScroll: ScrollView? = null
    private var clipboardRecentTab: TextView? = null
    private var clipboardPinnedTab: TextView? = null
    private var clipboardClearButton: ImageView? = null
    private var editButton: ImageView? = null
    private var editPanel: LinearLayout? = null
    private var editSelectButton: ImageView? = null
    private var editOpen = false
    private var voicePanel: FrameLayout? = null
    private var keyboardCard: FrameLayout? = null
    private var floatHandle: FrameLayout? = null
    private var floatButton: ImageView? = null
    private var floatingKeyboard = false
    // Original heights of the IME window's own views, restored when the keyboard docks again.
    private val dockedWindowHeights = mutableMapOf<View, Int>()
    private var voiceTitle: TextView? = null
    private var voiceHint: TextView? = null
    private var voiceCancel: FrameLayout? = null
    private var voiceSend: FrameLayout? = null
    private var voiceArc: VoiceArcView? = null
    private var voiceDots: List<View> = emptyList()
    private var voiceHold: VoiceHold? = null
    private var voiceOpen = false
    private var voiceLevel = 0f
    // The ~250 MB recognizer stays loaded briefly so reopening the panel is instant.
    private val voiceRelease = Runnable { VoiceEngine.release() }
    // While on, arrow and line keys extend the selection instead of moving the cursor.
    private var editSelecting = false
    private var emojiToolbar: LinearLayout? = null
    private var emojiCategoryScroll: HorizontalScrollView? = null
    private var emojiBackButton: ImageView? = null
    private var emojiTitle: TextView? = null
    private var mediaSearchButton: LinearLayout? = null
    private var mediaSearchText: TextView? = null
    private var emojiPanel: LinearLayout? = null
    private var emojiBody: LinearLayout? = null
    private var expanded = false
    private var clipboardOpen = false
    private var clipboardTab = ClipboardTab.RECENT
    private var emojiOpen = false
    private var emojiTab = EmojiTab.EMOJI
    private var emojiCategory = -1
    private var emojiCategoryTransitioning = false
    private val emojiGrids = mutableMapOf<Int, GridView>()
    // Recent emoji order is frozen while the panel is open so cells don't shift under the finger.
    private var emojiRecentSnapshot: List<String>? = null
    private var emojiReadyCallbackPending = false
    private var serviceDestroyed = false
    private var emojiVariantPage: EmojiVariantChoices? = null
    private var emojiReturnPosition = 0
    private var mediaQueryEditing = false
    private var mediaQueryTab = EmojiTab.MYGO
    private var gifQuery = ""
    private var giphyConfiguredKey: String? = null
    private var mygoQuery = ""
    private var mediaPreedit = ""
    private val mygoResults = mutableListOf<MyGoImage>()
    private var mygoPage = 0
    private var mygoHasNext = false
    private var mygoLoading = false
    private var mygoError: String? = null
    @Volatile private var mygoRequest = 0
    private var mygoScrollPosition = 0
    private var mygoScrollOffset = 0
    private var mygoResultsScroll: ListView? = null
    private val mediaThreadFactory = ThreadFactory { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "ime-media").apply { isDaemon = true }
    }
    private val mygoExecutor = Executors.newFixedThreadPool(2, mediaThreadFactory)
    private val thumbnailExecutor = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(48), mediaThreadFactory, ThreadPoolExecutor.DiscardOldestPolicy())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaCaret: PreeditCaretSpan? = null
    private val mediaCaretBlink = object : Runnable {
        override fun run() {
            val caret = mediaCaret ?: return
            val view = mediaSearchText ?: return
            // Stop while candidates cover the search bar; the next title update restarts it.
            if (!mediaQueryEditing || !view.isShown) return
            caret.visible = !caret.visible
            view.invalidate()
            mainHandler.postDelayed(this, CARET_BLINK_INTERVAL_MS)
        }
    }
    private var expandRequest = 0
    private var latestState: RimeProcessResult? = null
    private var symbols = false
    private var secure = false
    private var ascii = false

    private var chineseFullPunctuation = true

    private var symbolKey = SymbolKeySettings.DEFAULT
    private var symbolPage = 0
    private var inputScheme = InputScheme.PINYIN
    // Passwords and English mode keep QWERTY; Bopomofo only replaces the Chinese letter rows.
    private val zhuyinLayout: Boolean
        get() = inputScheme == InputScheme.ZHUYIN && !ascii && !symbols && !secure
    private var traditional = false
    private var shiftState = ShiftState.OFF
    private var editorComposing = false
    private var clearedText: String? = null
    private var clearUsedClipboard = false
    private var lastShiftTap = 0L
    @Volatile private var generation = 0
    private val appearancePreferences by lazy {
        getSharedPreferences(AppearanceSettings.PREFS_NAME, MODE_PRIVATE)
    }
    private val inputPreferences by lazy { getSharedPreferences("input_modes", MODE_PRIVATE) }
    private val clipboardManager by lazy { getSystemService(CLIPBOARD_SERVICE) as ClipboardManager }
    private var lastCapturedClipboardText: String? = null
    private var quickPasteEntry: ClipboardEntry? = null
    private var clipboardCaptureRequest = 0
    @Volatile private var clipboardRenderRequest = 0
    private var lastCapturedClipboardUri: String? = null
    private var inputViewActive = false
    private val quickPasteExpiry = Runnable { updateQuickPasteSuggestion() }
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        Handler(Looper.getMainLooper()).post { captureClipboard(fromChange = true) }
    }
    private val appearanceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AppearanceSettings.DARK_MODE) refreshAppearance()
    }
    // Symbols saved on the settings page apply even while the keyboard stays open on that page.
    private val symbolKeyListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || SymbolKeySettings.isSymbolKeyPreference(key)) {
            symbolKey = SymbolKeySettings.read(this)
            renderKeys()
        }
        if (key == null || key == InputSchemeSettings.KEY) {
            inputScheme = InputSchemeSettings.read(this)
            caption?.text = modeCaption()
            renderKeys()
        }
    }

    private val statusObserver: (EngineStatus) -> Unit = { status ->
        if (!status.ready) caption?.text = status.message
        else if (caption?.text?.contains("部署") == true || caption?.text?.contains("啟動") == true) {
            caption?.text = modeCaption()
        }
    }

    override fun onCreate() {
        super.onCreate()
        appearancePreferences.registerOnSharedPreferenceChangeListener(appearanceListener)
        inputPreferences.registerOnSharedPreferenceChangeListener(symbolKeyListener)
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        ClipboardHistory.startCleanup(this)
        captureClipboard()
        RimeManager.observe(statusObserver)
        RimeManager.ensureReady(this)
        EmojiCatalog.prepare(this)
    }

    override fun onDestroy() {
        serviceDestroyed = true
        generation++
        mygoRequest++
        emojiOpen = false
        dismissPreeditPreview()
        mainHandler.removeCallbacks(quickPasteExpiry)
        mainHandler.removeCallbacks(mediaCaretBlink)
        mainHandler.removeCallbacks(voiceRelease)
        VoiceEngine.stop()
        appearancePreferences.unregisterOnSharedPreferenceChangeListener(appearanceListener)
        inputPreferences.unregisterOnSharedPreferenceChangeListener(symbolKeyListener)
        clipboardManager.removePrimaryClipChangedListener(clipboardListener)
        mygoExecutor.shutdownNow()
        thumbnailExecutor.shutdownNow()
        RimeManager.removeObserver(statusObserver)
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        ThumbnailCache.trimMemory(level)
        if (!voiceOpen) VoiceEngine.release()
    }

    override fun onCreateInputView(): View {
        emojiGrids.clear()
        dismissPreeditPreview()
        darkMode = AppearanceSettings.isDark(this)
        window?.window?.let { imeWindow ->
            imeWindow.navigationBarColor = keyboardSurface
            androidx.core.view.WindowCompat.getInsetsController(imeWindow, imeWindow.decorView)
                .isAppearanceLightNavigationBars = !darkMode
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(8), dp(5), dp(8))
            setBackgroundColor(keyboardSurface)
        }
        topBar = FrameLayout(this)
        emptyToolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        caption = uiTextView().apply {
            text = if (RimeManager.status.ready) modeCaption() else RimeManager.status.message
            setTextColor(ink)
            textSize = 13f
            typeface = UiFonts.displayTypeface(this@IceInputMethodService)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
        }
        val toolbarLead = FrameLayout(this)
        this.toolbarLead = toolbarLead
        toolbarLead.addView(caption, FrameLayout.LayoutParams(-1, dp(40), Gravity.CENTER_VERTICAL))
        quickPasteButton = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = keyBackground(specialSurface)
            addView(ImageView(this@IceInputMethodService).apply {
                setImageResource(R.drawable.ic_clipboard_history)
                imageTintList = ColorStateList.valueOf(ink)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { leftMargin = dp(4) })
            quickPasteText = uiTextView().apply {
                setTextColor(ink)
                textSize = 14f
                typeface = UiFonts.bodyTypeface(this@IceInputMethodService, mediumWeight = true)
                includeFontPadding = false
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
            }
            addView(quickPasteText, LinearLayout.LayoutParams(0, -1, 1f))
            addView(ImageView(this@IceInputMethodService).apply {
                setImageResource(R.drawable.ic_close_outline)
                contentDescription = "關閉快速貼上"
                imageTintList = ColorStateList.valueOf(muted)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(8), dp(8), dp(8), dp(8))
                onHapticClick { dismissQuickPasteSuggestion() }
            }, LinearLayout.LayoutParams(dp(32), -1))
            onHapticClick { pasteQuickSuggestion() }
        }
        toolbarLead.addView(quickPasteButton, FrameLayout.LayoutParams(-1, dp(40), Gravity.CENTER_VERTICAL))
        val toolbarButtons = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }
        toolbarMenu = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            visibility = View.GONE
            addView(toolbarButtons, FrameLayout.LayoutParams(-2, -1))
        }
        val toolbarSlot = FrameLayout(this).apply {
            addView(toolbarLead, FrameLayout.LayoutParams(-1, -1))
            addView(toolbarMenu, FrameLayout.LayoutParams(-1, -1))
        }
        emptyToolbar?.addView(toolbarSlot, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            rightMargin = dp(4)
        })
        clipboardButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_clipboard_history)
            imageTintList = ColorStateList.valueOf(ink)
            contentDescription = "開啟剪貼簿"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = keyBackground(specialSurface)
            visibility = if (secure) View.GONE else View.VISIBLE
            onHapticClick { toggleClipboardPanel() }
        }
        toolbarButtons.addView(clipboardButton, LinearLayout.LayoutParams(dp(44), dp(40)).apply {
            rightMargin = dp(4)
        })
        editButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_text_edit)
            imageTintList = ColorStateList.valueOf(ink)
            contentDescription = "開啟文字編輯"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = keyBackground(specialSurface)
            onHapticClick { toggleEditPanel() }
        }
        toolbarButtons.addView(editButton, LinearLayout.LayoutParams(dp(44), dp(40)).apply {
            rightMargin = dp(4)
        })
        floatButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_float)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            onHapticClick { toggleFloatingKeyboard() }
        }
        toolbarButtons.addView(floatButton, LinearLayout.LayoutParams(dp(44), dp(40)).apply {
            rightMargin = dp(4)
        })
        punctuationWidthButton = uiTextView().apply {
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = UiFonts.bodyTypeface(this@IceInputMethodService, mediumWeight = true)
            setTextColor(ink)
            background = keyBackground(specialSurface)
            onHapticClick { togglePunctuationWidth() }
        }
        updatePunctuationWidthButton()
        toolbarButtons.addView(punctuationWidthButton, LinearLayout.LayoutParams(dp(44), dp(40)).apply {
            rightMargin = dp(4)
        })
        traditionalButton = uiTextView().apply {
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = UiFonts.bodyTypeface(this@IceInputMethodService, mediumWeight = true)
            setTextColor(ink)
            background = keyBackground(specialSurface)
            onHapticClick { toggleTraditional() }
        }
        updateTraditionalButton()
        toolbarButtons.addView(traditionalButton, LinearLayout.LayoutParams(dp(44), dp(40)).apply {
            rightMargin = dp(4)
        })
        toolbarButtons.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_settings)
            imageTintList = ColorStateList.valueOf(ink)
            contentDescription = "開啟設定"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = keyBackground(specialSurface)
            onHapticClick { openSettings() }
        }, LinearLayout.LayoutParams(dp(44), dp(40)))
        toolbarMenuButton = ImageView(this).apply {
            imageTintList = ColorStateList.valueOf(ink)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(9), dp(9), dp(9), dp(9))
            background = keyBackground(specialSurface)
            onHapticClick {
                // With a panel open, × first returns to the keys and leaves the menu expanded.
                if (clipboardOpen || editOpen) {
                    closeClipboardPanel()
                    closeEditPanel()
                } else setToolbarMenuOpen(!toolbarMenuOpen, animate = true)
            }
        }
        emptyToolbar?.addView(toolbarMenuButton, LinearLayout.LayoutParams(dp(44), dp(40)))
        setToolbarMenuOpen(false)
        topBar?.addView(emptyToolbar, FrameLayout.LayoutParams(-1, -1))

        candidateBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }

        val candidateControls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        candidateScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
        }
        candidateRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        candidateScroll?.addView(candidateRow)
        candidateControls.addView(candidateScroll, LinearLayout.LayoutParams(0, dp(44), 1f))
        moreButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_candidates_expand)
            imageTintList = ColorStateList.valueOf(ink)
            contentDescription = "展開所有候選詞"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = keyBackground(specialSurface)
            onHapticClick { toggleExpandedCandidates() }
        }
        candidateControls.addView(moreButton, LinearLayout.LayoutParams(dp(44), dp(40)).apply {
            rightMargin = dp(8)
        })
        candidateBar?.addView(candidateControls, LinearLayout.LayoutParams(-1, dp(44)))
        topBar?.addView(candidateBar, FrameLayout.LayoutParams(-1, -1))
        emojiToolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(8), 0, dp(8), 0)
        }
        emojiCategoryScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
        }
        emojiToolbar?.addView(emojiCategoryScroll, LinearLayout.LayoutParams(0, dp(40), 1f))
        emojiBackButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_undo)
            imageTintList = ColorStateList.valueOf(ink)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = keyBackground(specialSurface)
            contentDescription = "返回字母鍵盤"
            onHapticClick {
                if (emojiVariantPage != null) {
                    emojiVariantPage = null
                    renderEmojiPage()
                } else closeEmojiPanel()
            }
        }
        emojiTitle = uiTextView().apply {
            text = "表情符號"
            setTextColor(ink)
            textSize = 15f
            typeface = UiFonts.bodyTypeface(this@IceInputMethodService, mediumWeight = true)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, 0, 0)
            includeFontPadding = false
        }
        emojiToolbar?.addView(emojiTitle, LinearLayout.LayoutParams(0, dp(40), 1f))
        mediaSearchButton = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(12), 0, dp(12), 0)
            background = keyBackground(keySurface)
            onHapticClick { enterMediaQueryEditing(emojiTab) }
        }
        mediaSearchButton?.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_search_outline)
            imageTintList = ColorStateList.valueOf(muted)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }, LinearLayout.LayoutParams(dp(22), dp(22)))
        mediaSearchText = uiTextView().apply {
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8), 0, 0, 0)
            includeFontPadding = false
        }
        mediaSearchButton?.addView(mediaSearchText, LinearLayout.LayoutParams(0, -2, 1f))
        emojiToolbar?.addView(mediaSearchButton, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            rightMargin = dp(8)
        })
        emojiToolbar?.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_delete_outline)
            imageTintList = ColorStateList.valueOf(ink)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = keyBackground(specialSurface)
            contentDescription = "刪除前一個字元"
            onHapticClick { handleKey("DEL") }
        }, LinearLayout.LayoutParams(dp(44), dp(40)).apply { rightMargin = dp(4) })
        emojiToolbar?.addView(emojiBackButton, LinearLayout.LayoutParams(dp(44), dp(40)))
        topBar?.addView(emojiToolbar, FrameLayout.LayoutParams(-1, -1))
        root?.addView(topBar, LinearLayout.LayoutParams(-1, dp(50)))

        val keyboardArea = FrameLayout(this)
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        keyboardArea.addView(rows, FrameLayout.LayoutParams(-1, -1))
        expandedPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = rounded(keyboardSurface, 12)
        }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        expandedList = CandidateFlowLayout(this, dp(8), dp(8)).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        scroll.addView(expandedList, FrameLayout.LayoutParams(-1, -2))
        expandedPanel?.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        keyboardArea.addView(expandedPanel, FrameLayout.LayoutParams(-1, -1))
        clipboardPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = rounded(keyboardSurface, 12)
        }
        val clipboardTabs = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), dp(4))
        }
        clipboardRecentTab = uiTextView().apply {
            gravity = Gravity.CENTER
            textSize = 13f
            onHapticClick {
                clipboardTab = ClipboardTab.RECENT
                renderClipboardHistory()
                clipboardScroll?.scrollTo(0, 0)
            }
        }
        clipboardPinnedTab = uiTextView().apply {
            gravity = Gravity.CENTER
            textSize = 13f
            onHapticClick {
                clipboardTab = ClipboardTab.PINNED
                renderClipboardHistory()
                clipboardScroll?.scrollTo(0, 0)
            }
        }
        clipboardTabs.addView(clipboardRecentTab, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            rightMargin = dp(8)
        })
        clipboardTabs.addView(clipboardPinnedTab, LinearLayout.LayoutParams(0, dp(40), 1f))
        clipboardClearButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_clear_all)
            imageTintList = ColorStateList.valueOf(accent)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(9), dp(9), dp(9), dp(9))
            background = keyBackground(specialSurface)
            contentDescription = "清空最近的剪貼記錄，保留收藏"
            onHapticClick {
                dismissQuickPasteSuggestion()
                val app = applicationContext
                val clearedAt = System.currentTimeMillis()
                ClipboardHistory.runAsync({ ClipboardHistory.clearRecent(app, clearedAt) }) {
                    if (!serviceDestroyed && clipboardOpen) renderClipboardHistory()
                }
            }
        }
        clipboardTabs.addView(clipboardClearButton, LinearLayout.LayoutParams(dp(44), dp(40)).apply {
            leftMargin = dp(8)
        })
        clipboardPanel?.addView(clipboardTabs, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(4) })
        val clipboardScrollView = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        clipboardScroll = clipboardScrollView
        clipboardList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        clipboardScrollView.addView(clipboardList)
        clipboardPanel?.addView(clipboardScrollView, LinearLayout.LayoutParams(-1, 0, 1f))
        keyboardArea.addView(clipboardPanel, FrameLayout.LayoutParams(-1, -1))
        editPanel = buildEditPanel()
        keyboardArea.addView(editPanel, FrameLayout.LayoutParams(-1, -1))
        emojiPanel =LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = rounded(keyboardSurface, 12)
        }
        emojiBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        emojiPanel?.addView(emojiBody, LinearLayout.LayoutParams(-1, 0, 1f))
        keyboardArea.addView(emojiPanel, FrameLayout.LayoutParams(-1, -1))
        root?.addView(keyboardArea, LinearLayout.LayoutParams(-1, dp(228)))
        floatHandle = buildFloatHandle()
        root?.addView(floatHandle, LinearLayout.LayoutParams(-1, dp(22)))
        keyboardCard = FrameLayout(this).apply {
            addView(root, FrameLayout.LayoutParams(-1, -2))
        }
        voicePanel = buildVoicePanel()
        keyboardCard?.addView(voicePanel, FrameLayout.LayoutParams(-1, -1))
        inputFrame = FrameLayout(this).apply {
            addView(keyboardCard, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            // Rotation or a new screen size moves the card back inside the visible area.
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (floatingKeyboard) positionFloatingCard() }
        }
        holdPopup = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = rounded(keySurface, 12).apply { setStroke(dp(1), palette.outline) }
            elevation = dp(4).toFloat()
            visibility = View.GONE
        }
        inputFrame?.addView(holdPopup, FrameLayout.LayoutParams(dp(144), dp(52)))
        preeditPreview = uiTextView().apply {
            setTextColor(ink)
            textSize = 18f
            typeface = UiFonts.bodyTypeface(this@IceInputMethodService, mediumWeight = true)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            isSingleLine = true
            setPadding(dp(8), 0, dp(8), 0)
        }
        preeditScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(preeditPreview, FrameLayout.LayoutParams(-2, -1))
        }
        val previewSurface = FrameLayout(this).apply {
            background = rounded(keySurface, 8).apply { setStroke(dp(1), palette.outline) }
            elevation = dp(4).toFloat()
            addView(preeditScroll, FrameLayout.LayoutParams(-1, -1))
        }
        preeditPopup = PopupWindow(previewSurface, dp(48), dp(42), false).apply {
            isTouchable = false
            isOutsideTouchable = false
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            isClippingEnabled = false
        }
        renderCandidates(null)
        updateQuickPasteSuggestion()
        renderKeys()
        floatingKeyboard = inputPreferences.getBoolean(FLOATING_KEY, false)
        applyFloatingLayout()
        return inputFrame!!
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        inputViewActive = true
        refreshAppearance()
        captureClipboard()
        updateQuickPasteSuggestion()
    }

    private fun refreshAppearance() {
        val selectedMode = AppearanceSettings.isDark(this)
        if (darkMode == selectedMode) return
        val state = latestState
        darkMode = selectedMode
        if (root != null) {
            closeExpandedCandidates()
            closeClipboardPanel()
            closeEditPanel()
            closeVoicePanel()
            closeEmojiPanel()
            setInputView(onCreateInputView())
            state?.let(::renderCandidates)
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        inputViewActive = false
        generation++
        val variation = attribute?.inputType?.and(InputType.TYPE_MASK_VARIATION) ?: 0
        val inputClass = attribute?.inputType?.and(InputType.TYPE_MASK_CLASS) ?: 0
        secure = (inputClass == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        )) || (inputClass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        updateQuickPasteSuggestion()
        symbols = false
        symbolPage = 0
        ascii = false
        chineseFullPunctuation = inputPreferences.getBoolean("chinese_full_punctuation", true)
        symbolKey = SymbolKeySettings.read(this)
        inputScheme = InputSchemeSettings.read(this)
        shiftState = ShiftState.OFF
        editorComposing = false
        clearedText = null
        clearUsedClipboard = false
        closeExpandedCandidates()
        closeClipboardPanel()
        closeEditPanel()
        closeVoicePanel()
        closeEmojiPanel()
        mygoRequest++
        gifQuery = ""
        mygoQuery = ""
        mediaPreedit = ""
        mygoResults.clear()
        mygoPage = 0
        mygoHasNext = false
        mygoLoading = false
        mygoError = null
        latestState = null
        dismissPreeditPreview()
        refreshAppearance()
        updatePunctuationWidthButton()
        val token = generation
        val preferredTraditional = inputPreferences.getBoolean("traditional", false)
        RimeManager.run(this, { engine ->
            engine.clearComposition()
            engine.setOption("ascii_mode", false)
            engine.setOption("traditionalization", preferredTraditional)
            engine.getProcessResult(false) to engine.getOption("traditionalization")
        }) { outcome ->
            if (token == generation) {
                outcome.getOrNull()?.let { (state, isTraditional) ->
                    traditional = isTraditional
                    updateTraditionalButton()
                    renderCandidates(state)
                }
                renderKeys()
            }
        }
    }

    // Closing the keyboard, even without leaving the field, brings it back to the letter keys:
    // clipboard, text-editing and emoji panels close, the tool menu collapses and symbols return to letters.
    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        closeClipboardPanel()
        closeEditPanel()
        // Also covers the GIF and MyGO pages; a MyGO search still loading is dropped.
        closeEmojiPanel()
        mygoRequest++
        setToolbarMenuOpen(false)
        if (symbols || symbolPage != 0) {
            symbols = false
            symbolPage = 0
            renderKeys()
        }
    }

    override fun onFinishInput() {
        inputViewActive = false
        updateQuickPasteSuggestion()
        generation++
        if (editorComposing) currentInputConnection?.finishComposingText()
        editorComposing = false
        clearedText = null
        clearUsedClipboard = false
        holdPopup?.visibility = View.GONE
        closeExpandedCandidates()
        closeClipboardPanel()
        closeEditPanel()
        closeVoicePanel()
        closeEmojiPanel()
        dismissPreeditPreview()
        mygoRequest++
        super.onFinishInput()
    }

    private fun handleKey(key: String) {
        when (key) {
            "EMOJI" -> {
                if (mediaQueryEditing) {
                    leaveMediaQueryEditing(search = false)
                    emojiTab = EmojiTab.EMOJI
                }
                else openEmojiPanel()
                return
            }
            "MODE" -> {
                symbols = !symbols
                symbolPage = 0
                shiftState = ShiftState.OFF
                renderKeys()
                return
            }
            "PAGE" -> {
                symbolPage = (symbolPage + 1) % SYMBOL_PAGES.size
                renderKeys()
                return
            }
            "SHIFT" -> {
                val now = SystemClock.uptimeMillis()
                shiftState = when {
                    shiftState == ShiftState.LOCKED -> ShiftState.OFF
                    shiftState == ShiftState.ONCE && now - lastShiftTap < 400 -> ShiftState.LOCKED
                    shiftState == ShiftState.ONCE -> ShiftState.OFF
                    else -> ShiftState.ONCE
                }
                lastShiftTap = now
                renderKeys()
                return
            }
        }
        clearedText = null
        clearUsedClipboard = false
        val shiftedLetter = !zhuyinLayout && !symbols && key.length == 1 && key[0].isLetter() &&
            shiftState != ShiftState.OFF
        // A tone key outside a syllable types its mark rather than the digit it maps to.
        val unprocessedText = if (zhuyinLayout) ZHUYIN_LABELS[key] else null
        val textKey = if (shiftedLetter) key.uppercase() else key
        if (shiftedLetter && shiftState == ShiftState.ONCE) {
            shiftState = ShiftState.OFF
            renderKeys()
        }
        if (mediaQueryEditing && key == "ENTER") {
            leaveMediaQueryEditing(search = true)
            return
        }
        if (secure && !mediaQueryEditing) {
            when (key) {
                "DEL" -> currentInputConnection?.deleteSurroundingTextInCodePoints(1, 0)
                "ENTER" -> commitEnter()
                "SPACE" -> currentInputConnection?.commitText(" ", 1)
                else -> currentInputConnection?.commitText(textKey, 1)
            }
            return
        }
        if (symbols && key.length == 1 && !key[0].isLetterOrDigit()) {
            commitLiteral(punctuationText(key))
            return
        }
        val token = generation
        RimeManager.run(this, { engine ->
            when (key) {
                "DEL" -> if (engine.getInput().isEmpty()) KeyOutcome(delete = true)
                    else KeyOutcome(state = engine.processKeyAndGetResult(BACKSPACE, 0))
                "ENTER" -> if (engine.getInput().isEmpty()) KeyOutcome(enter = true)
                    else KeyOutcome(state = engine.processKeyAndGetResult(RETURN, 0))
                "SPACE" -> if (engine.getInput().isEmpty()) KeyOutcome(direct = " ")
                    else KeyOutcome(state = engine.processKeyAndGetResult(' '.code, 0))
                else -> {
                    val result = engine.processKeyAndGetResult(textKey.codePointAt(0), if (shiftedLetter) SHIFT_MASK else 0)
                    if (!result.processed && result.committedText.isEmpty())
                        KeyOutcome(state = result, direct = unprocessedText ?: textKey)
                    else KeyOutcome(state = result)
                }
            }
        }) { result ->
            if (token != generation) return@run
            result.onSuccess { outcome ->
                outcome.state?.let { state ->
                    applyRimeResult(state)
                }
                outcome.direct?.let {
                    if (mediaQueryEditing) appendMediaQuery(it)
                    else currentInputConnection?.commitText(it, 1)
                }
                if (outcome.delete) {
                    if (mediaQueryEditing) deleteMediaQueryCharacter()
                    else currentInputConnection?.deleteSurroundingTextInCodePoints(1, 0)
                }
                if (outcome.enter) {
                    if (mediaQueryEditing) leaveMediaQueryEditing(search = true)
                    else commitEnter()
                }
            }.onFailure { caption?.text = it.message ?: "Rime 尚未就緒" }
        }
    }

    private fun clearAllText() {
        val connection = currentInputConnection ?: return
        val text = readEditorText(connection)?.takeIf(String::isNotEmpty) ?: return
        val token = ++generation
        connection.beginBatchEdit()
        val cut: Boolean
        try {
            connection.finishComposingText()
            editorComposing = false
            connection.performContextMenuAction(android.R.id.selectAll)
            cut = connection.performContextMenuAction(android.R.id.cut)
        } finally {
            connection.endBatchEdit()
        }
        var remaining = readEditorText(connection)
        var usedClipboard = cut && clipboardText() == text
        if (remaining?.isNotEmpty() == true) {
            connection.setSelection(0, remaining.length)
            connection.commitText("", 1)
            remaining = readEditorText(connection)
            usedClipboard = false
        }
        if (remaining == null || remaining.isEmpty()) {
            clearedText = text
            clearUsedClipboard = usedClipboard
        } else {
            clearedText = null
            clearUsedClipboard = false
        }
        if (!secure) {
            RimeManager.run(this, { engine ->
                engine.clearComposition()
                engine.getProcessResult(false)
            }) { result ->
                if (token == generation) result.onSuccess(::renderCandidates)
            }
        } else renderCandidates(null)
    }

    private fun restoreClearedText() {
        val text = clearedText ?: return
        val connection = currentInputConnection ?: return
        if (readEditorText(connection)?.isNotEmpty() == true) {
            clearedText = null
            clearUsedClipboard = false
            return
        }
        val pasted = clearUsedClipboard && clipboardText() == text &&
            connection.performContextMenuAction(android.R.id.paste)
        if (!pasted || readEditorText(connection)?.isEmpty() == true) {
            connection.commitText(text, 1)
        }
        clearedText = null
        clearUsedClipboard = false
    }

    private fun readEditorText(connection: InputConnection): String? {
        connection.getExtractedText(ExtractedTextRequest(), 0)?.text?.let { return it.toString() }
        val before = connection.getTextBeforeCursor(1_000_000, 0)
        val selected = connection.getSelectedText(0)
        val after = connection.getTextAfterCursor(1_000_000, 0)
        return if (before == null && selected == null && after == null) null
            else "${before?.toString().orEmpty()}${selected?.toString().orEmpty()}${after?.toString().orEmpty()}"
    }

    private fun clipboardText(): String? {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        return clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.text?.toString()
    }

    private fun updateQuickPasteSuggestion() {
        mainHandler.removeCallbacks(quickPasteExpiry)
        val entry = quickPasteEntry
        val remaining = if (entry == null) 0L
            else entry.copiedAt + QUICK_PASTE_LIFETIME_MS - System.currentTimeMillis()
        val show = entry != null && inputViewActive && !secure && !emojiOpen && !clipboardOpen && remaining > 0 &&
            entry.copiedAt > inputPreferences.getLong("quick_paste_dismissed_at", 0)
        quickPasteButton?.visibility = if (show) View.VISIBLE else View.GONE
        caption?.visibility = if (show) View.GONE else View.VISIBLE
        if (show) {
            val preview = entry.text?.replace('\n', ' ')?.replace('\r', ' ')?.trim()
                ?.take(80)?.ifEmpty { "空白文字" } ?: entry.label?.take(80) ?: "圖片"
            quickPasteText?.text = preview
            quickPasteButton?.contentDescription = "貼上剛複製的內容：$preview"
            mainHandler.postDelayed(quickPasteExpiry, remaining)
        }
    }

    private fun dismissQuickPasteSuggestion() {
        quickPasteEntry?.let {
            inputPreferences.edit().putLong("quick_paste_dismissed_at", it.copiedAt).apply()
        }
        updateQuickPasteSuggestion()
    }

    private fun pasteQuickSuggestion() {
        val entry = quickPasteEntry ?: return
        if (secure || entry.copiedAt + QUICK_PASTE_LIFETIME_MS <= System.currentTimeMillis()) return
        dismissQuickPasteSuggestion()
        val image = ClipboardHistory.imageFile(this, entry)
        if (image != null) insertImageFile(image, entry.mimeType ?: "image/jpeg", entry.label ?: "圖片")
        else entry.text?.let(::commitLiteral)
    }

    private fun captureClipboard(fromChange: Boolean = false) {
        val request = ++clipboardCaptureRequest
        if (secure) {
            quickPasteEntry = null
            updateQuickPasteSuggestion()
            return
        }
        val clip = runCatching { clipboardManager.primaryClip }.getOrNull()
        if (clip == null || clip.itemCount == 0) {
            lastCapturedClipboardText = null
            lastCapturedClipboardUri = null
            quickPasteEntry = null
            updateQuickPasteSuggestion()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true) {
            quickPasteEntry = null
            updateQuickPasteSuggestion()
            return
        }
        val item = clip.getItemAt(0)
        val text = item.text?.toString()
        val uri = item.uri
        val copiedAt = ClipboardHistory.captureTime(this, clip.description.timestamp, fromChange)
        if (copiedAt == null) {
            if (text != quickPasteEntry?.text ||
                (text == null && uri?.toString() != lastCapturedClipboardUri)) quickPasteEntry = null
            updateQuickPasteSuggestion()
            return
        }
        if (text != null) {
            if (!fromChange && text == lastCapturedClipboardText &&
                quickPasteEntry?.text == text && quickPasteEntry?.copiedAt == copiedAt) {
                updateQuickPasteSuggestion()
                return
            }
            lastCapturedClipboardText = text
            lastCapturedClipboardUri = null
            quickPasteEntry = null
            updateQuickPasteSuggestion()
            val app = applicationContext
            ClipboardHistory.runAsync({ ClipboardHistory.record(app, text, copiedAt) }) { result ->
                if (serviceDestroyed) return@runAsync
                val recorded = result.getOrDefault(false)
                if (request == clipboardCaptureRequest && !secure) {
                    quickPasteEntry = if (recorded) ClipboardEntry(text = text, pinned = false, copiedAt = copiedAt) else null
                    updateQuickPasteSuggestion()
                }
                if (recorded && clipboardOpen) renderClipboardHistory()
            }
            return
        }
        quickPasteEntry = null
        updateQuickPasteSuggestion()
        if (uri == null) return
        lastCapturedClipboardText = null
        lastCapturedClipboardUri = uri.toString()
        val declaredMime = (0 until clip.description.mimeTypeCount).map { clip.description.getMimeType(it) }
                .firstOrNull { it.startsWith("image/") }
        val label = clip.description.label?.toString().orEmpty().ifBlank { "圖片" }
        val app = applicationContext
        ClipboardHistory.runImageAsync({
            val mime = runCatching { app.contentResolver.getType(uri) }.getOrNull()?.takeIf { it.startsWith("image/") }
                ?: declaredMime ?: return@runImageAsync null
            ClipboardHistory.recordImageUri(app, uri, mime, label, copiedAt)
        }) { result ->
            val stored = result.getOrNull()
            if (stored != null && !serviceDestroyed) {
                if (request == clipboardCaptureRequest && !secure) {
                    quickPasteEntry = stored
                    updateQuickPasteSuggestion()
                }
                if (clipboardOpen) renderClipboardHistory()
            }
        }
    }

    private fun selectCandidate(index: Int, global: Boolean = false) {
        clearedText = null
        clearUsedClipboard = false
        val token = generation
        RimeManager.run(this, { engine ->
            if (global) engine.selectCandidateByGlobalIndex(index) else engine.selectCandidate(index)
            engine.getProcessResult(true)
        }) { result ->
            if (token != generation) return@run
            result.onSuccess { state ->
                closeExpandedCandidates()
                applyRimeResult(state)
            }
        }
    }

    private fun commitEnter() {
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
        } else {
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    private fun toggleLanguage() {
        if (secure) return
        val token = generation
        RimeManager.run(this, { engine ->
            engine.toggleAsciiMode()
            engine.getProcessResult(false)
        }) { result ->
            if (token != generation) return@run
            result.onSuccess { state ->
                applyRimeResult(state)
                renderKeys()
            }.onFailure { caption?.text = it.message ?: "切換輸入模式失敗" }
        }
    }

    private fun modeCaption(): String = getString(R.string.brand_caption) +
        if (ascii) " · En" else if (inputScheme == InputScheme.ZHUYIN) " · 注音" else " · 中文"

    private fun updateTraditionalButton() {
        traditionalButton?.apply {
            text = if (traditional) "繁" else "简"
            contentDescription = if (traditional) "目前繁體，切換簡體" else "目前簡體，切換繁體"
            visibility = if (secure || ascii) View.GONE else View.VISIBLE
        }
        clipboardButton?.visibility = if (secure) View.GONE else View.VISIBLE
        updatePunctuationWidthButton()
    }

    // English mode always types half-width punctuation; only Chinese mode offers the full/half toggle.
    private val fullWidthPunctuation: Boolean
        get() = !secure && !ascii && chineseFullPunctuation

    private fun updatePunctuationWidthButton() {
        punctuationWidthButton?.apply {
            text = if (fullWidthPunctuation) "全" else "半"
            contentDescription = if (fullWidthPunctuation) "目前全形標點，切換半形" else "目前半形標點，切換全形"
            visibility = if (secure || ascii) View.GONE else View.VISIBLE
        }
    }

    private fun togglePunctuationWidth() {
        if (secure || ascii) return
        chineseFullPunctuation = !chineseFullPunctuation
        inputPreferences.edit().putBoolean("chinese_full_punctuation", chineseFullPunctuation).apply()
        updatePunctuationWidthButton()
        renderKeys()
    }

    private fun punctuationText(value: String): String {
        if (!fullWidthPunctuation) return value
        return buildString(value.length) {
            value.forEach { char ->
                append(when (char) {
                    ',' -> '，'
                    '.' -> '。'
                    in '!'..'/', in ':'..'@', in '['..'`', in '{'..'~' -> (char.code + 0xFEE0).toChar()
                    else -> char
                })
            }
        }
    }

    private fun toggleTraditional() {
        if (secure) return
        val token = generation
        val next = !traditional
        RimeManager.run(this, { engine ->
            engine.setOption("traditionalization", next)
            engine.getProcessResult(false) to engine.getOption("traditionalization")
        }) { result ->
            if (token != generation) return@run
            result.onSuccess { (state, isTraditional) ->
                traditional = isTraditional
                updateTraditionalButton()
                if (isTraditional == next) {
                    inputPreferences.edit().putBoolean("traditional", isTraditional).apply()
                }
                renderCandidates(state)
            }.onFailure { caption?.text = it.message ?: "切換簡繁失敗" }
        }
    }

    private fun commitLiteral(value: String) {
        clearedText = null
        clearUsedClipboard = false
        val searchingMedia = mediaQueryEditing
        if (secure && !searchingMedia) {
            currentInputConnection?.commitText(value, 1)
            return
        }
        val token = generation
        RimeManager.run(this, { engine ->
            val rawInput = engine.getInput()
            val previous = if (rawInput.isNotEmpty()) {
                val committed = engine.commit()
                engine.clearComposition()
                committed.ifEmpty { rawInput }
            } else ""
            previous to engine.getProcessResult(false)
        }) { result ->
            if (token != generation) return@run
            result.onSuccess { (previous, state) ->
                if (searchingMedia) {
                    if (!mediaQueryEditing) return@onSuccess
                    // Clear the pinyin first so the redraw doesn't show it after the committed text.
                    mediaPreedit = ""
                    appendMediaQuery(previous + value)
                } else {
                    if (previous.isNotEmpty()) currentInputConnection?.commitText(previous, 1)
                    editorComposing = false
                    currentInputConnection?.commitText(value, 1)
                }
                renderCandidates(state)
            }.onFailure {
                if (searchingMedia && mediaQueryEditing) appendMediaQuery(value)
                else if (!searchingMedia) {
                    currentInputConnection?.commitText(value, 1)
                    editorComposing = false
                }
            }
        }
    }

    private fun applyRimeResult(state: RimeProcessResult) {
        val preedit = state.preeditText.ifBlank { state.inputText }.filterNot(Char::isWhitespace)
        if (mediaQueryEditing) {
            if (state.committedText.isNotEmpty()) appendMediaQuery(state.committedText)
            mediaPreedit = preedit
            updateMediaQueryTitle()
            renderCandidates(state)
            return
        }
        if (editorComposing || state.committedText.isNotEmpty()) currentInputConnection?.let { connection ->
            connection.beginBatchEdit()
            try {
                if (editorComposing) {
                    connection.setComposingText("", 1)
                    editorComposing = false
                }
                if (state.committedText.isNotEmpty()) {
                    connection.commitText(state.committedText, 1)
                }
            } finally {
                connection.endBatchEdit()
            }
        }
        if (state.committedText.isNotEmpty()) closeExpandedCandidates()
        renderCandidates(state)
    }

    private fun formattedPreedit(state: RimeProcessResult): String {
        val source = state.preeditText.ifBlank { state.inputText }
        // Bopomofo syllables read clearly without the apostrophes used to split pinyin.
        if (inputScheme == InputScheme.ZHUYIN && !ascii) return source.filterNot(Char::isWhitespace)
        val tailStart = source.indexOfLast { ch ->
            !(ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' ||
                ch == 'ü' || ch == 'Ü' || ch == '\'' || ch.isWhitespace())
        } + 1
        val prefix = separatePreedit(source.substring(0, tailStart))
        val rawTail = source.substring(tailStart)
        val plainTail = rawTail.filter { it.isLetterOrDigit() }.lowercase()
        val candidateSpelling = if (plainTail.isNotEmpty()) {
            state.candidates.asSequence().mapNotNull { candidate ->
                PINYIN_COMMENT.find(candidate.comment)?.groupValues?.getOrNull(1)
            }.firstOrNull { spelling ->
                spelling.filter { it.isLetterOrDigit() }.lowercase() == plainTail
            }
        } else null
        val tail = separatePreedit(candidateSpelling ?: rawTail)
        val boundary = if (prefix.isNotEmpty() && tail.isNotEmpty() &&
            prefix.last() != '\'' && tail.first() != '\'') "'" else ""
        return prefix + boundary + tail
    }

    private fun separatePreedit(source: String): String = buildString {
        var separator = false
        source.forEach { ch ->
            if (ch.isWhitespace()) separator = true
            else {
                if (separator && isNotEmpty() && last() != '\'' && ch != '\'') append('\'')
                append(ch)
                separator = false
            }
        }
    }

    private fun preeditCursorIndex(state: RimeProcessResult, display: String): Int {
        val source = state.preeditText.ifBlank { state.inputText }
        val visible = source.filterNot(Char::isWhitespace)
        if (state.inputText.isNotEmpty() &&
            state.caretPos >= state.inputText.toByteArray(Charsets.UTF_8).size) {
            return display.length
        }
        // Raw input tracks the cursor through unconverted pinyin more reliably.
        val useRaw = visible == state.inputText.filterNot(Char::isWhitespace)
        val cursorSource = if (useRaw) state.inputText else source
        val byteLimit = if (useRaw) state.caretPos else state.preeditCursorPos
        var byteOffset = 0
        var charOffset = 0
        while (charOffset < cursorSource.length) {
            val codePoint = cursorSource.codePointAt(charOffset)
            val chars = Character.charCount(codePoint)
            val bytes = when {
                codePoint < 0x80 || codePoint in 0xD800..0xDFFF -> 1
                codePoint < 0x800 -> 2
                codePoint < 0x10000 -> 3
                else -> 4
            }
            if (byteOffset + bytes > byteLimit) break
            byteOffset += bytes
            charOffset += chars
        }
        var displayed = 0
        cursorSource.substring(0, charOffset).forEach { ch ->
            if (ch.isWhitespace()) {
                if (display.getOrNull(displayed) == '\'') displayed++
            } else {
                while (display.getOrNull(displayed) == '\'' && ch != '\'') displayed++
                if (displayed < display.length) displayed++
            }
        }
        return displayed.coerceIn(0, display.length)
    }

    private fun dismissPreeditPreview() {
        preeditPopup?.dismiss()
    }

    // Inserts a zero-width placeholder at index and draws the accent caret over it.
    private fun SpannableStringBuilder.insertCaret(index: Int): PreeditCaretSpan =
        PreeditCaretSpan(accent, dp(3), dp(2), dp(2)).also {
            insert(index, "\u200b")
            setSpan(it, index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    private fun updatePreeditPreview(state: RimeProcessResult?) {
        val popup = preeditPopup ?: return
        val frame = inputFrame ?: return
        val preview = preeditPreview ?: return
        val preedit = state?.let(::formattedPreedit).orEmpty()
        if (secure || mediaQueryEditing || emojiOpen || clipboardOpen || editOpen || voiceOpen || preedit.isEmpty()) {
            dismissPreeditPreview()
            return
        }
        val cursor = preeditCursorIndex(state!!, preedit).coerceIn(0, preedit.length)
        val display = SpannableStringBuilder(preedit).apply { insertCaret(cursor) }
        preview.text = display
        preview.contentDescription = "目前輸入：$preedit"
        if (!frame.isAttachedToWindow || frame.width == 0) {
            frame.post { if (latestState === state) updatePreeditPreview(state) }
            return
        }
        val anchor = keyboardCard ?: frame
        val maxWidth = (anchor.width * cardScale - dp(24)).toInt().coerceAtLeast(dp(48))
        val width = (preview.paint.measureText(preedit).roundToInt() + dp(21))
            .coerceIn(dp(40), maxWidth)
        val location = IntArray(2)
        anchor.getLocationInWindow(location)
        val x = location[0] + dp(12)
        val y = location[1] - dp(50)
        if (popup.isShowing) popup.update(x, y, width, dp(42))
        else {
            popup.width = width
            popup.showAtLocation(frame, Gravity.NO_GRAVITY, x, y)
        }
        val scrollX = (preview.paint.measureText(preedit.substring(0, cursor)).roundToInt() - width / 2)
            .coerceAtLeast(0)
        preeditScroll?.post { preeditScroll?.scrollTo(scrollX, 0) }
    }

    private fun renderCandidates(state: RimeProcessResult?) {
        latestState = state
        if (state != null) ascii = state.isAsciiMode
        updatePreeditPreview(state)
        updatePunctuationWidthButton()
        val showCandidates = state != null && state.candidates.isNotEmpty() && (!secure || mediaQueryEditing)
        emojiToolbar?.visibility = if (emojiOpen && !(mediaQueryEditing && showCandidates)) View.VISIBLE else View.GONE
        val strip = candidateRow ?: return
        if (!showCandidates) {
            for (index in 0 until strip.childCount) strip.getChildAt(index).visibility = View.GONE
            closeExpandedCandidates()
            emptyToolbar?.visibility = if (emojiOpen) View.GONE else View.VISIBLE
            candidateBar?.visibility = View.GONE
            caption?.text = when {
                !RimeManager.status.ready -> RimeManager.status.message
                else -> modeCaption()
            }
            updateQuickPasteSuggestion()
            return
        }
        closeClipboardPanel()
        closeEditPanel()
        closeVoicePanel()
        emptyToolbar?.visibility = View.GONE
        candidateBar?.visibility = if (!emojiOpen || mediaQueryEditing) View.VISIBLE else View.GONE
        var changed = false
        val count = minOf(9, state.candidates.size)
        for (index in 0 until count) {
            val candidate = state.candidates[index]
            val text = (strip.getChildAt(index) as? TextView) ?: uiTextView().apply {
                setTextColor(candidateInk)
                textSize = 19f
                typeface = UiFonts.bodyTypeface(this@IceInputMethodService, mediumWeight = true)
                gravity = Gravity.CENTER
                background = keyBackground(Color.TRANSPARENT)
                setPadding(dp(12), 0, dp(12), 0)
                includeFontPadding = false
                onHapticClick { selectCandidate(index) }
            }.also { cell ->
                strip.addView(cell, LinearLayout.LayoutParams(-2, dp(40)).apply {
                    rightMargin = dp(8)
                })
            }
            if (!TextUtils.equals(text.text, candidate.text) || text.visibility != View.VISIBLE) {
                text.text = candidate.text
                text.contentDescription = "候選詞 ${index + 1}：${candidate.text}"
                text.visibility = View.VISIBLE
                changed = true
            }
        }
        for (index in count until strip.childCount) {
            if (strip.getChildAt(index).visibility != View.GONE) changed = true
            strip.getChildAt(index).visibility = View.GONE
        }
        moreButton?.setImageResource(if (expanded) R.drawable.ic_candidates_collapse else R.drawable.ic_candidates_expand)
        moreButton?.contentDescription = if (expanded) "收起候選詞清單" else "展開所有候選詞"
        if (changed) candidateScroll?.scrollTo(0, 0)
    }

    private fun toggleExpandedCandidates() {
        if (expanded) {
            closeExpandedCandidates()
            return
        }
        if (latestState?.candidates.isNullOrEmpty()) return
        closeClipboardPanel()
        closeEditPanel()
        closeVoicePanel()
        expanded = true
        rows?.visibility = View.GONE
        expandedPanel?.visibility = View.VISIBLE
        expandedPanel?.riseIn()
        moreButton?.setImageResource(R.drawable.ic_candidates_collapse)
        moreButton?.contentDescription = "收起候選詞清單"
        expandedList?.removeAllViews()
        expandedList?.addView(uiTextView().apply {
            text = "載入候選詞…"
            setTextColor(muted)
            textSize = 14f
            setPadding(dp(14), dp(12), 0, 0)
        }, android.view.ViewGroup.LayoutParams(-1, -2))
        val token = generation
        val request = ++expandRequest
        RimeManager.run(this, { engine -> engine.getAllCandidates(200).toList() }) { result ->
            if (token != generation || request != expandRequest || !expanded) return@run
            result.onSuccess(::renderExpandedCandidates)
                .onFailure { renderExpandedCandidates(emptyList()) }
        }
    }

    private fun closeExpandedCandidates() {
        expanded = false
        expandRequest++
        rows?.visibility = if (clipboardOpen || editOpen || (emojiOpen && !mediaQueryEditing)) View.GONE else View.VISIBLE
        expandedPanel?.visibility = View.GONE
        moreButton?.setImageResource(R.drawable.ic_candidates_expand)
        moreButton?.contentDescription = "展開所有候選詞"
    }

    private fun toggleClipboardPanel() {
        if (clipboardOpen) {
            closeClipboardPanel()
            return
        }
        if (secure) return
        closeExpandedCandidates()
        closeEditPanel()
        closeVoicePanel()
        captureClipboard()
        clipboardTab = ClipboardTab.RECENT
        clipboardOpen = true
        updateQuickPasteSuggestion()
        dismissPreeditPreview()
        rows?.visibility = View.GONE
        clipboardPanel?.visibility = View.VISIBLE
        clipboardPanel?.riseIn()
        clipboardButton?.apply {
            background = keyBackground(actionColor)
            imageTintList = ColorStateList.valueOf(palette.onAction)
            contentDescription = "收起剪貼簿"
        }
        renderClipboardHistory()
    }

    private fun closeClipboardPanel() {
        if (!clipboardOpen) return
        clipboardOpen = false
        clipboardRenderRequest++
        updateQuickPasteSuggestion()
        clipboardPanel?.visibility = View.GONE
        updatePreeditPreview(latestState)
        rows?.visibility = if (expanded || editOpen || (emojiOpen && !mediaQueryEditing)) View.GONE else View.VISIBLE
        clipboardButton?.apply {
            background = keyBackground(specialSurface)
            imageTintList = ColorStateList.valueOf(ink)
            contentDescription = "開啟剪貼簿"
        }
    }

    private fun buildEditPanel(): LinearLayout {
        editOpen = false
        editSelecting = false
        fun cell(view: View, special: Boolean = true) = view.apply {
            background = InsetDrawable(
                keyBackground(if (special) specialSurface else keySurface),
                dp(KEY_HORIZONTAL_INSET_DP), dp(KEY_VERTICAL_INSET_DP),
                dp(KEY_HORIZONTAL_INSET_DP), dp(KEY_VERTICAL_INSET_DP)
            )
        }
        fun icon(resource: Int) = ImageView(this).apply {
            setImageResource(resource)
            imageTintList = ColorStateList.valueOf(ink)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        fun iconKey(resource: Int, description: String, special: Boolean = false, action: () -> Unit) =
            cell(icon(resource).apply {
                contentDescription = description
                setOnTouchListener { touched, event -> handleRepeatKeyTouch(touched, event, action) }
            }, special)
        fun shortcutKey(shortcut: String, description: String, action: () -> Unit) =
            cell(uiTextView().apply {
                text = shortcut
                gravity = Gravity.CENTER
                textSize = 18f
                typeface = UiFonts.displayTypeface(this@IceInputMethodService)
                includeFontPadding = false
                setTextColor(ink)
                contentDescription = "$description（$shortcut）"
                onHapticClick(action)
            })
        fun column(vararg views: View) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            views.forEach { addView(it, LinearLayout.LayoutParams(-1, 0, 1f)) }
        }
        fun row(vararg cells: Pair<View, Float>) = LinearLayout(this).apply {
            cells.forEach { (view, weight) -> addView(view, LinearLayout.LayoutParams(0, -1, weight)) }
        }
        editSelectButton = icon(R.drawable.ic_select).apply {
            onHapticClick { setEditSelecting(!editSelecting, collapse = true) }
        }
        updateEditSelectButton()
        val arrows = row(
            iconKey(R.drawable.ic_arrow_left, "游標左移") { sendEditKey(KeyEvent.KEYCODE_DPAD_LEFT) } to 1f,
            column(
                iconKey(R.drawable.ic_arrow_up, "游標上移") { sendEditKey(KeyEvent.KEYCODE_DPAD_UP) },
                editSelectButton!!,
                iconKey(R.drawable.ic_arrow_down, "游標下移") { sendEditKey(KeyEvent.KEYCODE_DPAD_DOWN) }
            ) to 1.4f,
            iconKey(R.drawable.ic_arrow_right, "游標右移") { sendEditKey(KeyEvent.KEYCODE_DPAD_RIGHT) } to 1f
        )
        val lineKeys = row(
            iconKey(R.drawable.ic_line_start, "移到行首") { sendEditKey(KeyEvent.KEYCODE_MOVE_HOME) } to 1f,
            iconKey(R.drawable.ic_delete_outline, "刪除", special = true) {
                sendEditKey(KeyEvent.KEYCODE_DEL, extend = false)
            } to 1.4f,
            iconKey(R.drawable.ic_line_end, "移到行尾") { sendEditKey(KeyEvent.KEYCODE_MOVE_END) } to 1f
        )
        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(arrows, LinearLayout.LayoutParams(-1, 0, 3f))
            addView(lineKeys, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val actions = column(
            shortcutKey("Ctrl+A", "全選") {
                currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
                setEditSelecting(true)
            },
            shortcutKey("Ctrl+X", "剪下") {
                currentInputConnection?.performContextMenuAction(android.R.id.cut)
                setEditSelecting(false)
            },
            shortcutKey("Ctrl+C", "複製") {
                currentInputConnection?.performContextMenuAction(android.R.id.copy)
                setEditSelecting(false)
            },
            shortcutKey("Ctrl+V", "貼上") {
                currentInputConnection?.performContextMenuAction(android.R.id.paste)
                setEditSelecting(false)
            }
        )
        return LinearLayout(this).apply {
            visibility = View.GONE
            background = rounded(keyboardSurface, 12)
            addView(navigation, LinearLayout.LayoutParams(0, -1, 3.4f))
            addView(actions, LinearLayout.LayoutParams(0, -1, 1.3f).apply { leftMargin = dp(6) })
        }
    }

    private fun toggleEditPanel() {
        if (editOpen) {
            closeEditPanel()
            return
        }
        closeExpandedCandidates()
        closeClipboardPanel()
        closeVoicePanel()
        editOpen = true
        setEditSelecting(false)
        updateQuickPasteSuggestion()
        dismissPreeditPreview()
        rows?.visibility = View.GONE
        editPanel?.visibility = View.VISIBLE
        editPanel?.riseIn()
        editButton?.apply {
            background = keyBackground(actionColor)
            imageTintList = ColorStateList.valueOf(palette.onAction)
            contentDescription = "收起文字編輯"
        }
    }

    private fun closeEditPanel() {
        if (!editOpen) return
        editOpen = false
        editSelecting = false
        editPanel?.visibility = View.GONE
        updatePreeditPreview(latestState)
        rows?.visibility = if (expanded || clipboardOpen || (emojiOpen && !mediaQueryEditing)) View.GONE else View.VISIBLE
        editButton?.apply {
            background = keyBackground(specialSurface)
            imageTintList = ColorStateList.valueOf(ink)
            contentDescription = "開啟文字編輯"
        }
    }

    private fun setEditSelecting(selecting: Boolean, collapse: Boolean = false) {
        if (!selecting && editSelecting && collapse) {
            // Turning selection off by hand drops the highlighted range, leaving the cursor at its end.
            currentInputConnection?.let { connection ->
                val extracted = connection.getExtractedText(ExtractedTextRequest(), 0)
                if (extracted != null && extracted.selectionStart != extracted.selectionEnd) {
                    val end = extracted.startOffset + maxOf(extracted.selectionStart, extracted.selectionEnd)
                    connection.setSelection(end, end)
                }
            }
        }
        editSelecting = selecting
        updateEditSelectButton()
    }

    private fun updateEditSelectButton() {
        editSelectButton?.apply {
            imageTintList = ColorStateList.valueOf(if (editSelecting) palette.onAction else ink)
            background = InsetDrawable(
                keyBackground(if (editSelecting) actionColor else specialSurface),
                dp(KEY_HORIZONTAL_INSET_DP), dp(KEY_VERTICAL_INSET_DP),
                dp(KEY_HORIZONTAL_INSET_DP), dp(KEY_VERTICAL_INSET_DP)
            )
            contentDescription = if (editSelecting) "選取中，點選取消選取" else "開始選取，方向鍵會擴大選取範圍"
        }
    }

    private fun sendEditKey(keyCode: Int, extend: Boolean = editSelecting) {
        val connection = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        // Holding Shift around the key lets TextView, WebView and Compose editors all extend the selection.
        val meta = if (extend) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        if (extend) connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 0, meta))
        connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
        if (extend) connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0))
    }

    private fun handleRepeatKeyTouch(view: View, event: MotionEvent, action: () -> Unit): Boolean {
        updateKeyPressed(view, event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                action()
                val token = generation
                val repeat = object : Runnable {
                    override fun run() {
                        if (view.tag !== this || token != generation || !view.isAttachedToWindow) return
                        action()
                        view.postDelayed(this, DELETE_REPEAT_INTERVAL_MS)
                    }
                }
                view.tag = repeat
                view.postDelayed(repeat, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                (view.tag as? Runnable)?.let(view::removeCallbacks)
                view.tag = null
            }
        }
        return true
    }

    // Push-to-talk overlay shown while the space bar is held: cancel on the left, send on the right.
    private fun buildVoicePanel(): FrameLayout = FrameLayout(this).apply {
        voiceOpen = false
        voiceHold = null
        visibility = View.GONE
        setBackgroundColor(keyboardSurface)
        isClickable = true
        voiceTitle = uiTextView().apply {
            gravity = Gravity.CENTER
            textSize = 16f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.START
            setTextColor(muted)
            setPadding(dp(24), 0, dp(24), 0)
        }
        addView(voiceTitle, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dp(30) })

        fun circle(icon: Int, description: String) = FrameLayout(this@IceInputMethodService).apply {
            contentDescription = description
            addView(ImageView(this@IceInputMethodService).apply {
                setImageResource(icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        }
        voiceCancel = circle(R.drawable.ic_undo, "取消語音輸入")
        voiceSend = circle(R.drawable.ic_arrow_up, "輸入並傳送")
        val pill = LinearLayout(this@IceInputMethodService).apply {
            gravity = Gravity.CENTER
            background = rounded(specialSurface, 32)
        }
        voiceDots = VOICE_DOT_COLORS.map { color ->
            View(this@IceInputMethodService).apply {
                background = rounded(color, 4)
                scaleY = VOICE_DOT_REST
            }.also { dot -> pill.addView(dot, LinearLayout.LayoutParams(dp(9), dp(30)).apply {
                leftMargin = dp(3); rightMargin = dp(3)
            }) }
        }
        addView(LinearLayout(this@IceInputMethodService).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
            addView(voiceCancel, LinearLayout.LayoutParams(dp(64), dp(64)))
            addView(pill, LinearLayout.LayoutParams(0, dp(64), 1f).apply { leftMargin = dp(14); rightMargin = dp(14) })
            addView(voiceSend, LinearLayout.LayoutParams(dp(64), dp(64)))
        }, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dp(98) })

        voiceArc = VoiceArcView(this@IceInputMethodService)
        addView(voiceArc, FrameLayout.LayoutParams(-1, dp(96), Gravity.BOTTOM))
        voiceHint = uiTextView().apply {
            gravity = Gravity.CENTER
            textSize = 18f
            typeface = UiFonts.bodyTypeface(this@IceInputMethodService, mediumWeight = true)
            setTextColor(ink)
        }
        addView(voiceHint, FrameLayout.LayoutParams(-1, dp(64), Gravity.BOTTOM).apply { bottomMargin = dp(10) })
    }

    /** Starts push-to-talk; returns false when voice input can't be used in this field. */
    private fun startVoiceHold(): Boolean {
        if (secure || mediaQueryEditing || emojiOpen) return false
        closeExpandedCandidates()
        closeClipboardPanel()
        closeEditPanel()
        dismissPreeditPreview()
        mainHandler.removeCallbacks(voiceRelease)
        val problem = when {
            !VoiceModel.isReady(this) -> "尚未下載語音模型"
            !VoiceEngine.hasMicPermission(this) -> "需要麥克風權限"
            else -> null
        }
        val hold = VoiceHold(generation, problem)
        voiceHold = hold
        voiceOpen = true
        voiceLevel = 0f
        voiceDots.forEach { it.scaleY = VOICE_DOT_REST }
        voiceTitle?.text = if (problem != null) "$problem，鬆開後前往設定" else VOICE_MODEL_LABEL
        setVoiceZone(hold, VoiceZone.FINISH, haptic = false)
        voicePanel?.visibility = View.VISIBLE
        voicePanel?.riseIn(distance = 0f, duration = 120)
        if (problem == null) startVoiceEngine(hold)
        return true
    }

    private fun startVoiceEngine(hold: VoiceHold) {
        if (voiceHold !== hold || hold.released) return
        // A previous session is still recognizing its last sentence; begin once it has stopped.
        if (VoiceEngine.isRunning) {
            mainHandler.postDelayed({ startVoiceEngine(hold) }, 120)
            return
        }
        hold.started = true
        val toTraditional = traditional
        val app = applicationContext
        VoiceEngine.start(this, transform = { text ->
            if (toTraditional) ChineseConverter.toTaiwan(app, text) else text
        }) { event -> handleVoiceEvent(hold, event) }
    }

    private fun handleVoiceEvent(hold: VoiceHold, event: VoiceEngine.Event) {
        if (voiceHold !== hold) return
        when (event) {
            VoiceEngine.Event.Loading -> if (hold.text.isEmpty()) voiceTitle?.text = "載入語音模型…"
            is VoiceEngine.Event.Listening -> {
                if (hold.text.isEmpty()) voiceTitle?.text = VOICE_MODEL_LABEL
                animateVoiceDots(event.level)
            }
            VoiceEngine.Event.Recognizing -> Unit
            is VoiceEngine.Event.Text -> {
                hold.text.append(event.text)
                voiceTitle?.text = hold.text
                voiceTitle?.setTextColor(ink)
            }
            is VoiceEngine.Event.Error -> voiceTitle?.text = event.message
            VoiceEngine.Event.Stopped -> {
                hold.stopped = true
                if (hold.released) finishVoiceHold(hold)
            }
        }
    }

    // The dots breathe with the voice level, each with its own phase so the row looks like a waveform.
    private fun animateVoiceDots(level: Float) {
        voiceLevel = voiceLevel * 0.55f + (level * 7f).coerceAtMost(1f) * 0.45f
        val time = SystemClock.uptimeMillis() / 1000f
        val middle = (voiceDots.size - 1) / 2f
        voiceDots.forEachIndexed { index, dot ->
            val profile = 1f - abs(index - middle) / (middle + 1f) * 0.6f
            val wobble = 0.7f + 0.3f * kotlin.math.sin(time * 9f + index * 0.8f)
            dot.scaleY = (VOICE_DOT_REST + (1f - VOICE_DOT_REST) * voiceLevel * profile * wobble)
                .coerceIn(VOICE_DOT_REST, 1f)
        }
    }

    /** Follows the held finger: left over the cancel button, right over send, otherwise finish. */
    private fun updateVoiceZone(rawX: Float) {
        val hold = voiceHold ?: return
        if (hold.released) return
        val margin = dp(16)
        val zone = when {
            voiceCancel?.let { rawX < it.screenRight() + margin } == true -> VoiceZone.CANCEL
            voiceSend?.let { rawX > it.screenLeft() - margin } == true -> VoiceZone.SEND
            else -> VoiceZone.FINISH
        }
        setVoiceZone(hold, zone, haptic = true)
    }

    private fun setVoiceZone(hold: VoiceHold, zone: VoiceZone, haptic: Boolean) {
        if (haptic && zone == hold.zone) return
        hold.zone = zone
        if (haptic) voicePanel?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        fun paint(circle: FrameLayout?, active: Boolean) = circle?.apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (active) actionColor else keySurface)
            }
            (getChildAt(0) as? ImageView)?.imageTintList = ColorStateList.valueOf(if (active) palette.onAction else muted)
        }
        paint(voiceCancel, zone == VoiceZone.CANCEL)
        paint(voiceSend, zone == VoiceZone.SEND)
        voiceArc?.setColor(if (zone == VoiceZone.FINISH) keySurface else specialSurface)
        voiceHint?.text = when (zone) {
            VoiceZone.CANCEL -> "鬆開取消"
            VoiceZone.SEND -> "鬆開傳送"
            VoiceZone.FINISH -> "鬆開結束"
        }
    }

    /** The space bar was released (or the gesture was cancelled by the system). */
    private fun releaseVoiceHold(cancelled: Boolean) {
        val hold = voiceHold ?: return
        hold.released = true
        if (cancelled) hold.zone = VoiceZone.CANCEL
        when {
            hold.problem != null -> {
                closeVoicePanel()
                if (!cancelled) openSettings()
            }
            hold.zone == VoiceZone.CANCEL -> closeVoicePanel()
            !hold.started || hold.stopped -> finishVoiceHold(hold)
            else -> {
                // Speech still in the buffer is recognized before the text is inserted.
                VoiceEngine.stop()
                voiceHint?.text = "辨識中…"
            }
        }
    }

    private fun finishVoiceHold(hold: VoiceHold) {
        if (voiceHold !== hold) return
        val text = hold.text.toString()
        if (hold.token == generation && hold.zone != VoiceZone.CANCEL) {
            val connection = currentInputConnection
            if (text.isNotEmpty() && connection != null) {
                clearedText = null
                clearUsedClipboard = false
                connection.commitText(text, 1)
            }
            if (hold.zone == VoiceZone.SEND) commitEnter()
        }
        closeVoicePanel()
    }

    /** Hides the overlay and drops any session still running (used when panels switch or input ends). */
    private fun closeVoicePanel() {
        if (!voiceOpen) return
        voiceOpen = false
        voiceHold = null
        VoiceEngine.stop()
        mainHandler.removeCallbacks(voiceRelease)
        mainHandler.postDelayed(voiceRelease, VOICE_RELEASE_DELAY_MS)
        voicePanel?.visibility = View.GONE
        voiceTitle?.setTextColor(muted)
    }

    private fun View.screenLeft(): Int = IntArray(2).also(::getLocationOnScreen)[0]
    private fun View.screenRight(): Int = screenLeft() + (width * cardScale).toInt()

    private enum class VoiceZone { CANCEL, FINISH, SEND }

    private class VoiceHold(val token: Int, val problem: String?) {
        val text = StringBuilder()
        var zone = VoiceZone.FINISH
        var started = false
        var stopped = false
        var released = false
    }

    /** The wide dome at the bottom of the push-to-talk overlay. */
    private class VoiceArcView(context: android.content.Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val oval = android.graphics.RectF()

        fun setColor(color: Int) {
            if (paint.color == color) return
            paint.color = color
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            oval.set(-width * 0.25f, 0f, width * 1.25f, height * 3.2f)
            canvas.drawOval(oval, paint)
        }
    }

    // Floating mode: the IME window spans the screen, but only the card is touchable and the app keeps its full size.
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val card = keyboardCard ?: return
        val decor = window?.window?.decorView ?: return
        if (!floatingKeyboard || !card.isLaidOut) return
        outInsets.contentTopInsets = decor.height
        outInsets.visibleTopInsets = decor.height
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        val origin = IntArray(2).also(card::getLocationInWindow)
        outInsets.touchableRegion.set(origin[0], origin[1],
            origin[0] + (card.width * card.scaleX).toInt(), origin[1] + (card.height * card.scaleY).toInt())
    }

    // A full-screen extract editor would defeat floating over the app.
    override fun onEvaluateFullscreenMode(): Boolean = !floatingKeyboard && super.onEvaluateFullscreenMode()

    /** Collapsed: brand caption or quick paste. Expanded: the feature buttons, so they fit even a floating card. */
    private fun setToolbarMenuOpen(open: Boolean, animate: Boolean = false) {
        toolbarMenuOpen = open
        toolbarLead?.visibility = if (open) View.GONE else View.VISIBLE
        toolbarMenu?.visibility = if (open) View.VISIBLE else View.GONE
        if (animate) {
            if (open) toolbarMenu?.slideIn() else toolbarLead?.riseIn(distance = 0f, duration = 140)
        }
        toolbarMenuButton?.apply {
            setImageResource(if (open) R.drawable.ic_close_outline else R.drawable.ic_toolbar_menu)
            contentDescription = if (open) "收起工具選單" else "展開工具選單"
        }
    }

    /** Hides the keyboard and brings up the settings page. */
    private fun openSettings() {
        requestHideSelf(0)
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    private fun toggleFloatingKeyboard() {
        floatingKeyboard = !floatingKeyboard
        inputPreferences.edit().putBoolean(FLOATING_KEY, floatingKeyboard).apply()
        closeVoicePanel()
        applyFloatingLayout()
    }

    private fun applyFloatingLayout() {
        val frame = inputFrame ?: return
        val card = keyboardCard ?: return
        val floating = floatingKeyboard
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        floatHandle?.visibility = if (floating) View.VISIBLE else View.GONE
        card.background = if (floating) rounded(keyboardSurface, 20).apply { setStroke(dp(1), palette.outline) } else null
        card.clipToOutline = floating
        card.elevation = if (floating) dp(12).toFloat() else 0f
        card.translationX = 0f
        card.translationY = 0f
        card.pivotX = 0f
        card.pivotY = 0f
        val scale = if (floating) inputPreferences.getFloat(FLOATING_SCALE_KEY, 1f).coerceIn(FLOATING_MIN_SCALE, 1f) else 1f
        card.scaleX = scale
        card.scaleY = scale
        card.layoutParams = (card.layoutParams as FrameLayout.LayoutParams).apply {
            width = if (floating) floatingCardWidth() else match
            gravity = if (floating) Gravity.TOP or Gravity.START else Gravity.BOTTOM
        }
        frame.layoutParams?.let { params ->
            params.height = if (floating) match else ViewGroup.LayoutParams.WRAP_CONTENT
            frame.layoutParams = params
        }
        window?.window?.let { imeWindow ->
            imeWindow.navigationBarColor = if (floating) Color.TRANSPARENT else keyboardSurface
            imeWindow.setLayout(match, if (floating) match else ViewGroup.LayoutParams.WRAP_CONTENT)
            // The framework's input area and its parents wrap the keyboard; stretch them to the window when floating.
            var view: View? = imeWindow.findViewById(android.R.id.inputArea)
            while (view != null && view !== imeWindow.decorView) {
                val params = view.layoutParams ?: break
                if (view !in dockedWindowHeights) dockedWindowHeights[view] = params.height
                params.height = if (floating) match else dockedWindowHeights.getValue(view)
                view.layoutParams = params
                view = view.parent as? View
            }
        }
        floatButton?.apply {
            background = keyBackground(if (floating) actionColor else specialSurface)
            imageTintList = ColorStateList.valueOf(if (floating) palette.onAction else ink)
            contentDescription = if (floating) "固定鍵盤" else "懸浮鍵盤"
        }
        if (floating) frame.post { positionFloatingCard() }
    }

    private fun floatingCardWidth(): Int =
        minOf((resources.displayMetrics.widthPixels * 0.84f).toInt(), dp(440))

    /** Places the card at its saved position, stored as fractions so it survives rotation. */
    private fun positionFloatingCard() {
        val frame = inputFrame ?: return
        val card = keyboardCard ?: return
        if (!floatingKeyboard || frame.width == 0 || card.width == 0) return
        // A rotated or smaller screen may no longer fit the saved size.
        val fit = minOf(frame.width.toFloat() / card.width, frame.height.toFloat() / card.height, 1f)
        if (card.scaleX > fit) {
            card.scaleX = fit.coerceAtLeast(FLOATING_MIN_SCALE)
            card.scaleY = card.scaleX
        }
        val maxX = (frame.width - card.width * card.scaleX).coerceAtLeast(0f)
        val maxY = (frame.height - card.height * card.scaleY).coerceAtLeast(0f)
        card.translationX = inputPreferences.getFloat(FLOATING_X_KEY, 0.5f).coerceIn(0f, 1f) * maxX
        card.translationY = inputPreferences.getFloat(FLOATING_Y_KEY, 1f).coerceIn(0f, 1f) * maxY
    }

    /** Grip at the bottom of the floating card; dragging it moves the keyboard. */
    private fun buildFloatHandle(): FrameLayout = FrameLayout(this).apply {
        visibility = View.GONE
        contentDescription = "拖曳移動鍵盤"
        addView(View(this@IceInputMethodService).apply {
            background = rounded(palette.outline, 2)
        }, FrameLayout.LayoutParams(dp(40), dp(4), Gravity.CENTER))
        addView(buildResizeGrip(), FrameLayout.LayoutParams(dp(30), dp(22), Gravity.END or Gravity.BOTTOM))
        var offsetX = 0f
        var offsetY = 0f
        setOnTouchListener { _, event ->
            val frame = inputFrame
            val card = keyboardCard
            if (frame == null || card == null) return@setOnTouchListener false
            val maxX = (frame.width - card.width * card.scaleX).coerceAtLeast(0f)
            val maxY = (frame.height - card.height * card.scaleY).coerceAtLeast(0f)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    offsetX = event.rawX - card.translationX
                    offsetY = event.rawY - card.translationY
                }
                MotionEvent.ACTION_MOVE -> {
                    card.translationX = (event.rawX - offsetX).coerceIn(0f, maxX)
                    card.translationY = (event.rawY - offsetY).coerceIn(0f, maxY)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> inputPreferences.edit()
                    .putFloat(FLOATING_X_KEY, if (maxX > 0f) card.translationX / maxX else 0.5f)
                    .putFloat(FLOATING_Y_KEY, if (maxY > 0f) card.translationY / maxY else 1f)
                    .apply()
            }
            true
        }
    }

    /** Bottom-right grip: dragging it scales the floating card around its top-left corner. */
    private fun buildResizeGrip(): ImageView = ImageView(this).apply {
        setImageResource(R.drawable.ic_resize)
        imageTintList = ColorStateList.valueOf(muted)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(4), dp(2), dp(6), dp(2))
        contentDescription = "拖曳調整鍵盤大小"
        var startX = 0f
        var startWidth = 0f
        setOnTouchListener { _, event ->
            val frame = inputFrame
            val card = keyboardCard
            if (frame == null || card == null || card.width == 0) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startWidth = card.width * card.scaleX
                }
                MotionEvent.ACTION_MOVE -> {
                    // The card may not grow past the screen edge from where it currently sits.
                    val limit = minOf((frame.width - card.translationX) / card.width,
                        (frame.height - card.translationY) / card.height, 1f)
                    val scale = ((startWidth + event.rawX - startX) / card.width)
                        .coerceIn(FLOATING_MIN_SCALE, limit.coerceAtLeast(FLOATING_MIN_SCALE))
                    card.scaleX = scale
                    card.scaleY = scale
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val maxX = (frame.width - card.width * card.scaleX).coerceAtLeast(0f)
                    val maxY = (frame.height - card.height * card.scaleY).coerceAtLeast(0f)
                    inputPreferences.edit()
                        .putFloat(FLOATING_SCALE_KEY, card.scaleX)
                        .putFloat(FLOATING_X_KEY, if (maxX > 0f) (card.translationX / maxX).coerceIn(0f, 1f) else 0.5f)
                        .putFloat(FLOATING_Y_KEY, if (maxY > 0f) (card.translationY / maxY).coerceIn(0f, 1f) else 1f)
                        .apply()
                }
            }
            true
        }
    }

    /** Scale of the floating card; key sizes measured inside it must be multiplied by this on screen. */
    private val cardScale: Float
        get() = keyboardCard?.scaleX ?: 1f

    private fun openEmojiPanel() {
        if (emojiOpen) return
        closeExpandedCandidates()
        closeClipboardPanel()
        closeEditPanel()
        closeVoicePanel()
        emojiVariantPage = null
        emojiReturnPosition = 0
        emojiOpen = true
        dismissPreeditPreview()
        emojiTab = EmojiTab.EMOJI
        rows?.visibility = View.GONE
        emojiPanel?.visibility = View.VISIBLE
        emojiPanel?.riseIn()
        emptyToolbar?.visibility = View.GONE
        candidateBar?.visibility = View.GONE
        emojiToolbar?.visibility = View.VISIBLE
        renderEmojiPage()
    }

    private fun openMediaSearch(tab: EmojiTab) {
        if (tab == EmojiTab.EMOJI) return
        if (secure || mediaQueryEditing) return
        emojiVariantPage = null
        closeExpandedCandidates()
        closeClipboardPanel()
        closeEditPanel()
        closeVoicePanel()
        if (tab == EmojiTab.MYGO) {
            mygoRequest++
            mygoQuery = ""
            mygoResults.clear()
            mygoPage = 0
            mygoHasNext = false
            mygoLoading = false
            mygoError = null
            mygoScrollPosition = 0
            mygoScrollOffset = 0
            mygoResultsScroll = null
        } else gifQuery = ""
        mediaPreedit = ""
        emojiOpen = true
        dismissPreeditPreview()
        emojiTab = tab
        showMediaToolbarTitle(if (tab == EmojiTab.GIF) "GIF 搜尋" else "MyGO 梗圖")
        emojiPanel?.visibility = View.GONE
        rows?.visibility = View.VISIBLE
        emptyToolbar?.visibility = View.GONE
        candidateBar?.visibility = View.GONE
        emojiToolbar?.visibility = View.VISIBLE
        enterMediaQueryEditing(tab)
    }

    private fun closeEmojiPanel() {
        if (!emojiOpen) return
        emojiCategoryTransitioning = false
        emojiVariantPage = null
        val wasMediaQueryEditing = mediaQueryEditing
        if (wasMediaQueryEditing) {
            mediaQueryEditing = false
            mediaPreedit = ""
            RimeManager.run(this, { engine -> engine.clearComposition() }) { }
            renderKeys()
        }
        emojiOpen = false
        emojiRecentSnapshot = null
        emojiGrids.remove(-1)
        emojiPanel?.visibility = View.GONE
        rows?.visibility = if (expanded || clipboardOpen || editOpen) View.GONE else View.VISIBLE
        emojiToolbar?.visibility = View.GONE
        val candidatesVisible = !secure && !latestState?.candidates.isNullOrEmpty()
        emptyToolbar?.visibility = if (candidatesVisible) View.GONE else View.VISIBLE
        candidateBar?.visibility = if (candidatesVisible) View.VISIBLE else View.GONE
        updateQuickPasteSuggestion()
        if (wasMediaQueryEditing) renderCandidates(null) else updatePreeditPreview(latestState)
    }

    private fun renderEmojiPage() {
        val body = emojiBody ?: return
        if (emojiTab == EmojiTab.MYGO) mygoResultsScroll?.let {
            mygoScrollPosition = it.firstVisiblePosition
            mygoScrollOffset = (it.getChildAt(0)?.top ?: it.paddingTop) - it.paddingTop
        }
        mygoResultsScroll = null
        body.removeAllViews()
        emojiBackButton?.apply {
            contentDescription = if (emojiVariantPage != null) "返回表情列表" else "返回字母鍵盤"
        }
        val categoriesVisible = emojiTab == EmojiTab.EMOJI && emojiVariantPage == null
        emojiCategoryScroll?.visibility = if (categoriesVisible) View.VISIBLE else View.GONE
        emojiTitle?.visibility = if (categoriesVisible) View.GONE else View.VISIBLE
        mediaSearchButton?.visibility = View.GONE
        if (emojiTab == EmojiTab.EMOJI && !EmojiCatalog.isReady()) {
            showMediaToolbarTitle("表情符號")
            body.addView(mygoStatus("正在載入表情符號…"), LinearLayout.LayoutParams(-1, 0, 1f))
            if (!emojiReadyCallbackPending) {
                emojiReadyCallbackPending = true
                EmojiCatalog.prepare(this) {
                    emojiReadyCallbackPending = false
                    if (!serviceDestroyed && emojiOpen && emojiTab == EmojiTab.EMOJI) renderEmojiPage()
                }
            }
            return
        }
        if (categoriesVisible) renderEmojiCategories()
        val title = when (emojiTab) {
            EmojiTab.EMOJI -> emojiVariantPage?.let { "選擇外觀 · ${it.choices.size}" } ?: "表情符號"
            EmojiTab.GIF -> "GIF 搜尋"
            EmojiTab.MYGO -> "MyGO 梗圖"
        }
        if (!categoriesVisible) showMediaToolbarTitle(title)
        when (emojiTab) {
            EmojiTab.EMOJI -> renderEmojiGrid(body)
            EmojiTab.GIF -> renderGifPage(body)
            EmojiTab.MYGO -> renderMygoPage(body)
        }
        if (emojiTab == EmojiTab.MYGO && mygoPage == 0 && !mygoLoading && mygoError == null) {
            fetchMygoPage(reset = true)
        }
    }

    private fun renderEmojiGrid(body: LinearLayout) {
        emojiVariantPage?.let { renderEmojiVariantPage(body, it); return }
        val categories = EmojiCatalog.categories(this)
        emojiCategory = emojiCategory.coerceIn(-1, categories.lastIndex)
        fun emojiAt(index: Int): List<String> = if (index == -1) {
            (emojiRecentSnapshot ?: EmojiCatalog.recent(this).also { emojiRecentSnapshot = it })
                .ifEmpty { categories.first().emoji.take(32) }
        } else categories[index].emoji
        fun gridAt(index: Int): GridView = emojiGrids.getOrPut(index) { createEmojiGrid(emojiAt(index)) }.also {
            (it.parent as? android.view.ViewGroup)?.removeView(it)
        }
        val previous = if (emojiCategory > -1) gridAt(emojiCategory - 1) else null
        val current = gridAt(emojiCategory)
        val following = if (emojiCategory < categories.lastIndex) {
            gridAt(emojiCategory + 1)
        } else null
        // Retain only the visible page and its neighbours, including their recycled cells.
        emojiGrids.keys.retainAll(setOf(emojiCategory - 1, emojiCategory, emojiCategory + 1))
        body.addView(EmojiCategoryPager(emojiCategory, previous, current, following),
            LinearLayout.LayoutParams(-1, 0, 1f))
        val category = emojiCategory
        current.post {
            if (emojiOpen && emojiVariantPage == null && emojiCategory == category) {
                current.setSelection(emojiReturnPosition)
            }
        }
    }

    private fun createEmojiGrid(emoji: List<String>): GridView = GridView(this).apply {
            numColumns = 8
            horizontalSpacing = dp(2)
            verticalSpacing = dp(4)
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(4), dp(4), dp(4), dp(4))
            adapter = object : BaseAdapter() {
                override fun getCount() = emoji.size
                override fun getItem(position: Int) = emoji[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                    val symbol = emoji[position]
                    val variants = EmojiCatalog.variants(this@IceInputMethodService, symbol)
                    val cell = (convertView as? FrameLayout) ?: FrameLayout(this@IceInputMethodService).apply {
                        background = keyBackground(Color.TRANSPARENT)
                        layoutParams = AbsListView.LayoutParams(-1, dp(44))
                        addView(TextView(this@IceInputMethodService).apply {
                            textSize = 26f
                            gravity = Gravity.CENTER
                        }, FrameLayout.LayoutParams(-1, -1))
                        addView(uiTextView().apply {
                            text = "•"
                            textSize = 11f
                            setTextColor(accent)
                            gravity = Gravity.BOTTOM or Gravity.RIGHT
                            setPadding(0, 0, dp(4), dp(1))
                        }, FrameLayout.LayoutParams(-1, -1))
                    }
                    (cell.getChildAt(0) as TextView).text = symbol
                    cell.getChildAt(1).visibility = if (variants == null) View.GONE else View.VISIBLE
                    cell.contentDescription = when {
                        variants == null -> "輸入 $symbol"
                        variants.choices.size > 18 -> "輸入 $symbol，長按選擇雙人膚色組合"
                        variants.choices.size > 6 -> "輸入 $symbol，長按選擇性別和膚色"
                        else -> "輸入 $symbol，長按選擇膚色"
                    }
                    cell.onHapticClick { commitEmoji(symbol) }
                    cell.setOnTouchListener(if (variants == null) null else View.OnTouchListener { touched, event ->
                        handleEmojiVariantTouch(touched, event, symbol, variants)
                    })
                    return cell
                }
            }
    }

    private fun selectEmojiCategory(next: Int) {
        if (emojiCategoryTransitioning || next == emojiCategory) return
        if ((emojiBody?.getChildAt(0) as? EmojiCategoryPager)?.animateTo(next) == true) return
        emojiCategory = next
        emojiReturnPosition = 0
        renderEmojiPage()
    }

    private inner class EmojiCategoryPager(
        private val category: Int,
        private val previous: GridView?,
        private val current: GridView,
        private val following: GridView?
    ) : FrameLayout(this@IceInputMethodService) {
        private val touchSlop = ViewConfiguration.get(this@IceInputMethodService).scaledTouchSlop
        private var startX = 0f
        private var startY = 0f
        private var startTime = 0L
        private var offset = 0f
        private var verticalGesture = false
        private var dragging = false

        init {
            clipChildren = true
            previous?.let { addView(it, LayoutParams(-1, -1)) }
            following?.let { addView(it, LayoutParams(-1, -1)) }
            addView(current, LayoutParams(-1, -1))
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            setOffset(offset)
        }

        private fun setOffset(value: Float) {
            val pageWidth = width.toFloat().coerceAtLeast(1f)
            offset = when {
                value > 0 && previous == null -> (value * 0.18f).coerceAtMost(dp(20).toFloat())
                value < 0 && following == null -> (value * 0.18f).coerceAtLeast(-dp(20).toFloat())
                else -> value.coerceIn(-pageWidth, pageWidth)
            }
            current.translationX = offset
            previous?.translationX = offset - pageWidth
            following?.translationX = offset + pageWidth
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (emojiCategoryTransitioning) return true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    startTime = event.eventTime
                    verticalGesture = false
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - startX
                    val dy = event.y - startY
                    if (!verticalGesture && abs(dy) > touchSlop && abs(dy) > abs(dx)) {
                        verticalGesture = true
                    }
                    if (!dragging && !verticalGesture &&
                        holdPopup?.visibility != View.VISIBLE &&
                        abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.3f) {
                        dragging = true
                        val cancel = MotionEvent.obtain(event)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                    }
                    if (dragging) {
                        setOffset(dx)
                        return true
                    }
                }
                MotionEvent.ACTION_UP -> if (dragging) {
                    dragging = false
                    val velocity = (event.x - startX) * 1000f /
                        (event.eventTime - startTime).coerceAtLeast(1L)
                    val commit = abs(offset) > width * 0.25f ||
                        (abs(offset) > dp(48) && abs(velocity) > 650f)
                    val target = when {
                        commit && offset > 0 && previous != null -> category - 1
                        commit && offset < 0 && following != null -> category + 1
                        else -> null
                    }
                    settle(target)
                    return true
                }
                MotionEvent.ACTION_CANCEL -> if (dragging) {
                    dragging = false
                    settle(null)
                    return true
                }
            }
            return super.dispatchTouchEvent(event)
        }

        fun animateTo(next: Int): Boolean {
            if (emojiCategoryTransitioning) return true
            if ((next == category - 1 && previous != null) ||
                (next == category + 1 && following != null)) {
                settle(next)
                return true
            }
            return false
        }

        private fun settle(target: Int?) {
            emojiCategoryTransitioning = true
            val pageWidth = width.toFloat().coerceAtLeast(1f)
            val end = when {
                target == category - 1 -> pageWidth
                target == category + 1 -> -pageWidth
                else -> 0f
            }
            val animator = ValueAnimator.ofFloat(offset, end)
            animator.duration = (90f + 120f * abs(end - offset) / pageWidth).toLong()
            animator.addUpdateListener { setOffset(it.animatedValue as Float) }
            animator.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    emojiCategoryTransitioning = false
                    if (target != null && emojiOpen && emojiTab == EmojiTab.EMOJI &&
                        emojiVariantPage == null && emojiCategory == category &&
                        this@EmojiCategoryPager.parent == emojiBody) {
                        emojiCategory = target
                        emojiReturnPosition = 0
                        renderEmojiPage()
                    } else if (target == null) setOffset(0f)
                }
            })
            animator.start()
        }
    }

    // Hold the caret solid after each change and only blink once typing pauses, like a text field.
    private fun restartMediaCaretBlink() {
        mainHandler.removeCallbacks(mediaCaretBlink)
        if (!mediaQueryEditing) {
            mediaCaret = null
            return
        }
        mediaCaret?.visible = true
        mainHandler.postDelayed(mediaCaretBlink, CARET_BLINK_INTERVAL_MS)
    }

    private fun showMediaToolbarTitle(title: String) {
        emojiCategoryScroll?.visibility = View.GONE
        val showMediaSearch = emojiTab == EmojiTab.GIF || emojiTab == EmojiTab.MYGO
        mediaSearchButton?.visibility = if (showMediaSearch) View.VISIBLE else View.GONE
        if (showMediaSearch) {
            val name = if (emojiTab == EmojiTab.GIF) "GIF" else "MyGO 梗圖"
            val query = (if (emojiTab == EmojiTab.GIF) gifQuery else mygoQuery) +
                if (mediaQueryEditing) mediaPreedit else ""
            mediaSearchButton?.contentDescription = "搜尋 $name"
            val placeholder = "搜尋 $name"
            mediaSearchText?.apply {
                text = if (mediaQueryEditing) {
                    // The caret sits after the query, or before the placeholder like an empty text field.
                    SpannableStringBuilder(query).apply {
                        mediaCaret = insertCaret(length)
                        if (query.isBlank()) append(placeholder)
                    }
                } else query.ifBlank { placeholder }
                setTextColor(if (query.isBlank()) muted else ink)
                ellipsize = if (mediaQueryEditing) TextUtils.TruncateAt.START else TextUtils.TruncateAt.END
            }
        }
        restartMediaCaretBlink()
        emojiTitle?.apply {
            visibility = if (showMediaSearch) View.GONE else View.VISIBLE
            text = title
        }
    }

    private fun renderEmojiCategories() {
        val categoryScroll = emojiCategoryScroll ?: return
        val icons = mapOf(
            "表情" to R.drawable.ic_emoji_smile,
            "人物" to R.drawable.ic_emoji_person,
            "動物" to R.drawable.ic_emoji_animal,
            "食物" to R.drawable.ic_emoji_food,
            "旅行" to R.drawable.ic_emoji_travel,
            "活動" to R.drawable.ic_emoji_activity,
            "物件" to R.drawable.ic_emoji_object,
            "符號" to R.drawable.ic_emoji_symbols,
            "旗幟" to R.drawable.ic_emoji_flag
        )
        val filledIcons = mapOf(
            "最近" to R.drawable.ic_emoji_history_filled,
            "表情" to R.drawable.ic_emoji_smile_filled,
            "人物" to R.drawable.ic_emoji_person_filled,
            "動物" to R.drawable.ic_emoji_animal_filled,
            "食物" to R.drawable.ic_emoji_food_filled,
            "旅行" to R.drawable.ic_emoji_travel_filled,
            "活動" to R.drawable.ic_emoji_activity_filled,
            "物件" to R.drawable.ic_emoji_object_filled,
            "符號" to R.drawable.ic_emoji_symbols_filled,
            "旗幟" to R.drawable.ic_emoji_flag_filled
        )
        val categories = listOf("最近" to R.drawable.ic_emoji_history) +
            EmojiCatalog.categories(this).map { it.name to (icons[it.name] ?: R.drawable.ic_emoji_smile) }
        val categoryRow = (categoryScroll.getChildAt(0) as? LinearLayout)?.takeIf {
            it.childCount == categories.size
        } ?: LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            categories.forEachIndexed { index, (name, _) ->
                addView(ImageView(this@IceInputMethodService).apply {
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    background = keyBackground(Color.TRANSPARENT)
                    contentDescription = "$name 表情"
                    onHapticClick {
                        selectEmojiCategory(index - 1)
                    }
                }, LinearLayout.LayoutParams(dp(40), dp(40)))
            }
            categoryScroll.removeAllViews()
            categoryScroll.addView(this)
        }
        categories.forEachIndexed { index, (name, icon) ->
            val selected = emojiCategory == index - 1
            val view = categoryRow.getChildAt(index) as ImageView
            if (view.tag != selected) {
                view.setImageResource(if (selected) filledIcons[name] ?: icon else icon)
                view.imageTintList = ColorStateList.valueOf(if (selected) candidateInk else muted)
                view.tag = selected
            }
        }
        categoryScroll.post {
            val selectedCenter = (emojiCategory + 1) * dp(40) + dp(24)
            categoryScroll.scrollTo((selectedCenter - categoryScroll.width / 2).coerceAtLeast(0), 0)
        }
    }

    private fun renderEmojiVariantPage(body: LinearLayout, group: EmojiVariantChoices) {
        val grid = GridView(this).apply {
            numColumns = 7
            horizontalSpacing = dp(2)
            verticalSpacing = dp(4)
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(4), dp(4), dp(4), dp(4))
            adapter = object : BaseAdapter() {
                override fun getCount() = group.choices.size
                override fun getItem(position: Int) = group.choices[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                    val symbol = group.choices[position]
                    val cell = (convertView as? TextView) ?: TextView(this@IceInputMethodService).apply {
                        textSize = 26f
                        gravity = Gravity.CENTER
                        background = keyBackground(Color.TRANSPARENT)
                        layoutParams = AbsListView.LayoutParams(-1, dp(44))
                    }
                    cell.text = symbol
                    cell.contentDescription = "輸入 $symbol"
                    cell.onHapticClick { commitEmoji(symbol) }
                    return cell
                }
            }
        }
        body.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun commitEmoji(symbol: String) {
        EmojiCatalog.remember(this, symbol)
        commitLiteral(symbol)
        if (emojiVariantPage != null) {
            emojiVariantPage = null
            renderEmojiPage()
        }
    }

    private fun renderGifPage(body: LinearLayout) {
        if (!ensureGiphyConfigured()) {
            val message = if (GiphySettings.apiKey(this).isBlank()) "尚未設定 GIPHY 金鑰，請到 App 設定頁「開始使用」填入"
                else "GIF 目前無法使用，請稍後重試"
            body.addView(mygoStatus(message),
                LinearLayout.LayoutParams(-1, 0, 1f))
            return
        }
        val container = FrameLayout(this)
        val status = mygoStatus("正在載入 GIF…").apply { visibility = View.VISIBLE }
        var hasResults = false
        GPHCustomTheme.apply {
            if (darkMode) applyDarkThemeProps() else applyLightThemeProps()
            backgroundColor = keyboardSurface
            defaultTextColor = ink
            usernameColor = muted
            retryButtonBackgroundColor = specialSurface
            retryButtonTextColor = accent
        }
        val grid = GiphyGridView(this).apply {
            direction = GiphyGridView.VERTICAL
            spanCount = 2
            cellPadding = dp(4)
            theme = GPHTheme.Custom
            setBackgroundColor(keyboardSurface)
            callback = object : GPHGridCallback {
                override fun contentDidUpdate(resultCount: Int) {
                    mainHandler.post {
                        if (parent != container) return@post
                        if (resultCount > 0) hasResults = true
                        status.text = when {
                            resultCount < 0 && !hasResults -> "GIF 載入失敗，請重新搜尋"
                            resultCount == 0 && !hasResults -> "找不到相關 GIF"
                            else -> ""
                        }
                        status.visibility = if (hasResults) View.GONE else View.VISIBLE
                    }
                }

                override fun didSelectMedia(media: Media) {
                    insertGiphyGif(media)
                }
            }
        }
        container.addView(grid, FrameLayout.LayoutParams(-1, -1))
        container.addView(status, FrameLayout.LayoutParams(-1, -1))
        body.addView(container, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(uiTextView().apply {
            text = "Powered by GIPHY"
            setTextColor(muted)
            textSize = 10f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, dp(24)))
        grid.content = gifQuery.trim().takeIf { it.isNotEmpty() }
            ?.let(GPHContent::searchQuery) ?: GPHContent.trendingGifs
    }

    private fun ensureGiphyConfigured(): Boolean {
        val key = GiphySettings.apiKey(this)
        if (key.isBlank()) return false
        // The key can change on the settings page while the keyboard service stays alive.
        if (giphyConfiguredKey == key) return true
        giphyConfiguredKey = runCatching {
            Giphy.configure(applicationContext, key)
            key
        }.getOrNull()
        return giphyConfiguredKey == key
    }

    private fun insertGiphyGif(media: Media) {
        val token = generation
        Toast.makeText(this, "正在準備 GIF…", Toast.LENGTH_SHORT).show()
        mygoExecutor.execute {
            val prepared = runCatching { GiphyGif.prepareForInsertion(this, media) }
            mainHandler.post {
                if (token != generation) return@post
                prepared.onSuccess { file ->
                    insertImageFile(file, "image/gif", media.title?.ifBlank { "GIF" } ?: "GIF")
                    returnToKeyboardAfterMedia(EmojiTab.GIF)
                }.onFailure { Toast.makeText(this, "GIF 下載失敗，請重試", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun renderMygoPage(body: LinearLayout) {
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), dp(4))
        }
        if (mygoResults.isEmpty() && !mygoLoading && mygoError == null && mygoPage > 0) {
            footer.addView(mygoStatus("找不到相關梗圖"), LinearLayout.LayoutParams(-1, dp(86)))
        }
        if (mygoLoading) footer.addView(mygoStatus("正在載入梗圖…"), LinearLayout.LayoutParams(-1, dp(58)))
        mygoError?.let { error ->
            footer.addView(uiTextView().apply {
                text = "$error · 點此重試"
                setTextColor(accent)
                textSize = 13f
                gravity = Gravity.CENTER
                background = keyBackground(specialSurface)
                onHapticClick { fetchMygoPage(reset = mygoPage == 0) }
            }, LinearLayout.LayoutParams(-1, dp(50)))
        }
        if (mygoHasNext && !mygoLoading && mygoError == null) {
            footer.addView(uiTextView().apply {
                text = "載入更多"
                setTextColor(accent)
                textSize = 13f
                typeface = UiFonts.bodyTypeface(this@IceInputMethodService, mediumWeight = true)
                gravity = Gravity.CENTER
                background = keyBackground(specialSurface)
                onHapticClick { fetchMygoPage(reset = false) }
            }, LinearLayout.LayoutParams(-1, dp(44)).apply {
                setMargins(dp(4), dp(8), dp(4), dp(4))
            })
        }
        footer.addView(uiTextView().apply {
            text = "圖片來源：MyGO-Searcher · miyago9267"
            setTextColor(muted)
            textSize = 10f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, dp(30)))
        val scroll = ListView(this).apply {
            isVerticalScrollBarEnabled = false
            divider = null
            cacheColorHint = Color.TRANSPARENT
            setPadding(dp(4), 0, dp(4), 0)
            addFooterView(footer, null, false)
            adapter = object : BaseAdapter() {
                override fun getCount() = (mygoResults.size + 1) / 2
                override fun getItem(position: Int) = mygoResults[position * 2]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                    val row = (convertView as? LinearLayout) ?: LinearLayout(this@IceInputMethodService).apply {
                        tag = MygoRowViews(List(2) {
                            val card = LinearLayout(this@IceInputMethodService).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(dp(4), dp(4), dp(4), dp(4))
                                background = candidateBackground(12)
                                onHapticClick { (tag as? MyGoImage)?.let(::insertMygoImage) }
                            }
                            val preview = ImageView(this@IceInputMethodService).apply {
                                scaleType = ImageView.ScaleType.CENTER_CROP
                                background = rounded(specialSurface, 8)
                                clipToOutline = true
                            }
                            val title = uiTextView().apply {
                                setTextColor(ink)
                                textSize = 12f
                                maxLines = 1
                                ellipsize = TextUtils.TruncateAt.END
                                gravity = Gravity.CENTER_VERTICAL
                                setPadding(dp(4), 0, dp(4), 0)
                            }
                            card.addView(preview, LinearLayout.LayoutParams(-1, dp(72)))
                            card.addView(title, LinearLayout.LayoutParams(-1, dp(24)))
                            addView(card, LinearLayout.LayoutParams(0, dp(104), 1f).apply {
                                setMargins(dp(4), dp(4), dp(4), dp(4))
                            })
                            MygoCardViews(card, preview, title)
                        })
                    }
                    (row.tag as MygoRowViews).cards.forEachIndexed { index, views ->
                        val image = mygoResults.getOrNull(position * 2 + index)
                        views.card.visibility = if (image == null) View.INVISIBLE else View.VISIBLE
                        if (views.card.tag != image) {
                            views.card.tag = image
                            views.preview.tag = image?.url
                            views.preview.setImageDrawable(null)
                            views.title.text = image?.alt?.ifBlank { "MyGO 梗圖" }
                            views.card.contentDescription = image?.let { "插入梗圖：${it.alt}" }
                        }
                        if (image != null && views.preview.drawable == null) loadMygoThumbnail(image, views.preview)
                    }
                    return row
                }
            }
        }
        mygoResultsScroll = scroll
        body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        scroll.post { scroll.setSelectionFromTop(mygoScrollPosition, mygoScrollOffset) }
    }

    private fun mygoStatus(message: String) = uiTextView().apply {
        text = message
        setTextColor(muted)
        textSize = 13f
        gravity = Gravity.CENTER
    }

    private fun fetchMygoPage(reset: Boolean) {
        if (mygoLoading) return
        if (reset) {
            mygoResults.clear()
            mygoPage = 0
            mygoHasNext = false
            mygoScrollPosition = 0
            mygoScrollOffset = 0
            mygoResultsScroll = null
        }
        val page = mygoPage + 1
        val query = mygoQuery.trim()
        val request = ++mygoRequest
        val token = generation
        mygoLoading = true
        mygoError = null
        if (emojiOpen && emojiTab == EmojiTab.MYGO && !mediaQueryEditing) renderEmojiPage()
        mygoExecutor.execute {
            if (request != mygoRequest) return@execute
            val result = runCatching { MyGoApi.page(query, page) }
            mainHandler.post {
                if (request != mygoRequest || token != generation) return@post
                mygoLoading = false
                result.onSuccess { response ->
                    mygoResults.addAll(response.images)
                    mygoPage = page
                    mygoHasNext = response.hasNext
                }.onFailure {
                    mygoError = "載入失敗"
                }
                if (emojiOpen && emojiTab == EmojiTab.MYGO && !mediaQueryEditing) renderEmojiPage()
            }
        }
    }

    private fun loadMygoThumbnail(image: MyGoImage, preview: ImageView) {
        val key = "mygo:${image.url}"
        ThumbnailCache.get(key)?.let { preview.setImageBitmap(it); return }
        val request = mygoRequest
        val app = applicationContext
        thumbnailExecutor.execute {
            if (request != mygoRequest) return@execute
            val bitmap = runCatching { ThumbnailCache.load(key) { MyGoApi.cachedImage(app, image) } }.getOrNull()
            mainHandler.post {
                if (bitmap != null && request == mygoRequest && emojiOpen && emojiTab == EmojiTab.MYGO &&
                    preview.isAttachedToWindow && preview.tag == image.url) preview.setImageBitmap(bitmap)
            }
        }
    }

    private fun insertMygoImage(image: MyGoImage) {
        val token = generation
        Toast.makeText(this, "正在準備梗圖…", Toast.LENGTH_SHORT).show()
        mygoExecutor.execute {
            val prepared = runCatching {
                val downloaded = MyGoApi.cachedImage(this, image)
                val stored = ClipboardHistory.recordImageFile(this, downloaded, image.mimeType, image.alt)
                ClipboardHistory.imageFile(this, stored ?: return@runCatching downloaded) ?: downloaded
            }
            mainHandler.post {
                if (token != generation) return@post
                prepared.onSuccess { file ->
                    insertImageFile(file, image.mimeType, image.alt)
                    returnToKeyboardAfterMedia(EmojiTab.MYGO)
                }.onFailure {
                    Toast.makeText(this, "梗圖下載失敗，請重試", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Leave the media page only if the user is still browsing it, not typing a new search.
    private fun returnToKeyboardAfterMedia(tab: EmojiTab) {
        if (emojiOpen && emojiTab == tab && !mediaQueryEditing) closeEmojiPanel()
    }

    private fun insertImageFile(file: File, mimeType: String, label: String) {
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val supportsImage = currentInputEditorInfo?.contentMimeTypes?.any {
                it == "image/*" || it == mimeType
            } == true
            val inserted = if (supportsImage) runCatching {
                currentInputConnection?.finishComposingText()
                currentInputConnection?.commitContent(
                    InputContentInfo(uri, ClipDescription(label, arrayOf(mimeType)), null),
                    InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null
                ) == true
            }.getOrDefault(false) else false
            if (inserted) Toast.makeText(this, "已插入圖片", Toast.LENGTH_SHORT).show()
            else {
                clipboardManager.setPrimaryClip(ClipData.newUri(contentResolver, label, uri))
                Toast.makeText(this, "圖片已存入剪貼簿；可從鍵盤剪貼簿再次貼上", Toast.LENGTH_LONG).show()
            }
        }.onFailure {
            Toast.makeText(this, "圖片插入失敗，請重試", Toast.LENGTH_SHORT).show()
        }
    }

    private fun enterMediaQueryEditing(tab: EmojiTab = emojiTab) {
        if (!emojiOpen || tab == EmojiTab.EMOJI || emojiTab != tab || mediaQueryEditing) return
        val token = generation
        if (editorComposing) {
            currentInputConnection?.setComposingText("", 1)
            editorComposing = false
        }
        RimeManager.run(this, { engine ->
            val raw = engine.getInput()
            val previous = if (raw.isNotEmpty()) engine.commit().ifEmpty { raw } else ""
            engine.clearComposition()
            previous to engine.getProcessResult(false)
        }) { result ->
            if (token != generation || !emojiOpen || emojiTab != tab) return@run
            result.onSuccess { (previous, state) ->
                if (previous.isNotEmpty()) currentInputConnection?.commitText(previous, 1)
                mediaQueryTab = tab
                mediaQueryEditing = true
                mediaPreedit = ""
                emojiPanel?.visibility = View.GONE
                rows?.visibility = View.VISIBLE
                emojiBackButton?.apply {
                    contentDescription = "返回字母鍵盤"
                }
                updateMediaQueryTitle()
                renderCandidates(state)
                renderKeys()
            }.onFailure {
                closeEmojiPanel()
                Toast.makeText(this, "$tab 搜尋暫時無法輸入", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun leaveMediaQueryEditing(search: Boolean) {
        if (!mediaQueryEditing) return
        val token = generation
        RimeManager.run(this, { engine ->
            val raw = engine.getInput()
            val committed = if (search && raw.isNotEmpty()) engine.commit().ifEmpty { raw } else ""
            engine.clearComposition()
            committed to engine.getProcessResult(false)
        }) { result ->
            if (token != generation || !mediaQueryEditing) return@run
            result.onSuccess { (committed, state) ->
                if (search && committed.isNotEmpty()) appendMediaQuery(committed)
                mediaQueryEditing = false
                mediaPreedit = ""
                rows?.visibility = View.GONE
                emojiPanel?.visibility = View.VISIBLE
                emojiBackButton?.apply {
                    contentDescription = "返回字母鍵盤"
                }
                showMediaToolbarTitle(if (mediaQueryTab == EmojiTab.GIF) "GIF 搜尋" else "MyGO 梗圖")
                renderCandidates(state)
                renderKeys()
                if (search && mediaQueryTab == EmojiTab.MYGO) fetchMygoPage(reset = true)
                else renderEmojiPage()
            }.onFailure {
                mediaQueryEditing = false
                mediaPreedit = ""
                rows?.visibility = View.GONE
                emojiPanel?.visibility = View.VISIBLE
                renderCandidates(null)
                renderKeys()
                renderEmojiPage()
            }
        }
    }

    private fun appendMediaQuery(text: String) {
        if (text.isEmpty()) return
        if (mediaQueryTab == EmojiTab.GIF) gifQuery = (gifQuery + text).take(100)
        else mygoQuery = (mygoQuery + text).take(100)
        updateMediaQueryTitle()
    }

    private fun deleteMediaQueryCharacter() {
        val query = if (mediaQueryTab == EmojiTab.GIF) gifQuery else mygoQuery
        if (query.isEmpty()) return
        val previous = query.offsetByCodePoints(query.length, -1)
        if (mediaQueryTab == EmojiTab.GIF) gifQuery = query.substring(0, previous)
        else mygoQuery = query.substring(0, previous)
        updateMediaQueryTitle()
    }

    private fun updateMediaQueryTitle() {
        if (!mediaQueryEditing) return
        val visible = (if (mediaQueryTab == EmojiTab.GIF) gifQuery else mygoQuery) + mediaPreedit
        val mode = if (mediaQueryTab == EmojiTab.GIF) "GIF" else "MyGO"
        showMediaToolbarTitle(if (visible.isBlank()) "$mode · 搜尋" else "$mode · $visible")
        emojiTitle?.apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
        }
    }

    private fun renderClipboardHistory() {
        val list = clipboardList ?: return
        val request = ++clipboardRenderRequest
        val token = generation
        val app = applicationContext
        ClipboardHistory.runAsync({ ClipboardHistory.entries(app) }) { result ->
            if (serviceDestroyed || !clipboardOpen || request != clipboardRenderRequest ||
                token != generation || list !== clipboardList) return@runAsync
            bindClipboardHistory(list, result.getOrDefault(emptyList()))
        }
    }

    private fun bindClipboardHistory(list: LinearLayout, allEntries: List<ClipboardEntry>) {
        list.removeAllViews()
        val recentCount = allEntries.count { !it.pinned }
        val pinnedCount = allEntries.size - recentCount
        clipboardRecentTab?.apply {
            text = "最近 24h · $recentCount"
            setTextColor(if (clipboardTab == ClipboardTab.RECENT) accent else muted)
            typeface = if (clipboardTab == ClipboardTab.RECENT) UiFonts.bodyTypeface(this@IceInputMethodService, mediumWeight = true) else UiFonts.bodyTypeface(this@IceInputMethodService)
            background = if (clipboardTab == ClipboardTab.RECENT) keyBackground(specialSurface) else keyBackground(Color.TRANSPARENT)
        }
        clipboardPinnedTab?.apply {
            text = "收藏 · $pinnedCount"
            setTextColor(if (clipboardTab == ClipboardTab.PINNED) accent else muted)
            typeface = if (clipboardTab == ClipboardTab.PINNED) UiFonts.bodyTypeface(this@IceInputMethodService, mediumWeight = true) else UiFonts.bodyTypeface(this@IceInputMethodService)
            background = if (clipboardTab == ClipboardTab.PINNED) keyBackground(specialSurface) else keyBackground(Color.TRANSPARENT)
        }
        clipboardClearButton?.visibility = if (clipboardTab == ClipboardTab.RECENT && recentCount > 0)
            View.VISIBLE else View.INVISIBLE
        val entries = allEntries.filter { it.pinned == (clipboardTab == ClipboardTab.PINNED) }
        if (entries.isEmpty()) {
            list.addView(uiTextView().apply {
                text = if (clipboardTab == ClipboardTab.RECENT) {
                    "最近 24 小時沒有複製記錄\n複製文字或圖片後會顯示在這裡"
                } else "尚無收藏\n在「最近」長按項目即可收藏"
                setTextColor(muted)
                textSize = 14f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, dp(150)))
            return
        }
        entries.forEach { entry ->
            val cell = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(8), dp(8))
                background = candidateBackground(12)
                contentDescription = if (entry.pinned) "已收藏，點選貼上，長按取消收藏" else "點選貼上，長按收藏"
                onHapticClick {
                    closeClipboardPanel()
                    val imageFile = ClipboardHistory.imageFile(this@IceInputMethodService, entry)
                    if (imageFile != null) insertImageFile(imageFile, entry.mimeType ?: "image/jpeg", entry.label ?: "圖片")
                    else entry.text?.let(::commitLiteral)
                }
                setOnLongClickListener {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    val app = applicationContext
                    ClipboardHistory.runAsync({ ClipboardHistory.togglePinned(app, entry.id) }) {
                        if (!serviceDestroyed && clipboardOpen) renderClipboardHistory()
                    }
                    true
                }
            }
            if (entry.imageFileName != null) {
                val preview = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = rounded(specialSurface, 8)
                    clipToOutline = true
                    contentDescription = entry.label ?: "圖片"
                }
                cell.addView(preview, LinearLayout.LayoutParams(dp(64), dp(48)).apply { rightMargin = dp(12) })
                val key = "clipboard:${entry.imageFileName}"
                val cached = ThumbnailCache.get(key)
                if (cached != null) preview.setImageBitmap(cached)
                else {
                    val token = generation
                    val request = clipboardRenderRequest
                    val app = applicationContext
                    thumbnailExecutor.execute {
                        if (token != generation || request != clipboardRenderRequest) return@execute
                        val bitmap = runCatching {
                            ThumbnailCache.load(key) { ClipboardHistory.imageFile(app, entry) }
                        }.getOrNull()
                        if (bitmap != null) mainHandler.post {
                            if (token == generation && request == clipboardRenderRequest &&
                                clipboardOpen && preview.isAttachedToWindow) preview.setImageBitmap(bitmap)
                        }
                    }
                }
            }
            cell.addView(uiTextView().apply {
                text = entry.text?.let { value ->
                    value.take(240).replace("\n", " ↵ ") + if (value.length > 240) "…" else ""
                } ?: entry.label ?: "圖片"
                setTextColor(ink)
                textSize = 15f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            cell.addView(ImageView(this).apply {
                setImageResource(if (entry.pinned) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark_outline)
                imageTintList = ColorStateList.valueOf(if (entry.pinned) accent else muted)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(10), dp(10), dp(10), dp(10))
                contentDescription = if (entry.pinned) "已收藏" else "可長按收藏"
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            list.addView(cell, LinearLayout.LayoutParams(-1, dp(if (entry.imageFileName != null) 64 else 56)).apply {
                bottomMargin = dp(8)
            })
        }
    }

    private fun renderExpandedCandidates(candidates: List<RimeCandidate>) {
        val list = expandedList ?: return
        list.removeAllViews()
        if (candidates.isEmpty()) {
            list.addView(uiTextView().apply {
                text = "沒有更多候選詞"
                setTextColor(muted)
                textSize = 14f
                setPadding(dp(14), dp(12), 0, 0)
            }, android.view.ViewGroup.LayoutParams(-1, -2))
            return
        }
        candidates.forEachIndexed { index, candidate ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(44)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = keyBackground(Color.TRANSPARENT)
                contentDescription = "候選詞 ${index + 1}：${candidate.text}"
                onHapticClick { selectCandidate(index, global = true) }
            }
            cell.addView(uiTextView().apply {
                text = candidate.text
                setTextColor(candidateInk)
                textSize = 18f
                typeface = UiFonts.bodyTypeface(this@IceInputMethodService, mediumWeight = true)
                maxLines = 1
                includeFontPadding = false
            })
            if (candidate.comment.isNotBlank()) {
                cell.addView(uiTextView().apply {
                    text = candidate.comment
                    setTextColor(muted)
                    textSize = 11f
                    maxLines = 1
                    setPadding(0, dp(4), 0, 0)
                    includeFontPadding = false
                })
            }
            list.addView(cell, android.view.ViewGroup.LayoutParams(-2, -2))
        }
    }

    private fun renderKeys() {
        val container = rows ?: return
        updateTraditionalButton()
        val layout = KeyLayoutState(symbols, shiftState, ascii, fullWidthPunctuation,
            darkMode, mediaQueryEditing, mediaQueryTab, symbolKey, zhuyinLayout, symbolPage)
        if (container.tag == layout) return
        container.tag = layout
        container.removeAllViews()
        if (symbols) {
            // Same row shapes as the letter keyboard, with a page key where Shift sits.
            SYMBOL_PAGES[symbolPage].forEachIndexed { index, keys ->
                when (index) {
                    0 -> addRow(keys)
                    1 -> addRow(keys, inset = 15)
                    else -> addRow(listOf("PAGE") + keys + "DEL")
                }
            }
        } else if (zhuyinLayout) {
            // Daqian layout, as on physical Bopomofo keyboards and Gboard.
            addRow("1 2 3 4 5 6 7 8 9 0 -".split(' '), zhuyin = true)
            addRow("q w e r t y u i o p".split(' '), zhuyin = true, sideWeight = 0.5f)
            addRow("a s d f g h j k l ;".split(' '), zhuyin = true, sideWeight = 0.5f)
            addRow("z x c v b n m , . /".split(' ') + "DEL", zhuyin = true)
        } else {
            addRow("qwertyuiop".map(::letterLabel), numberHints = true)
            addRow("asdfghjkl".map(::letterLabel), inset = 15)
            addRow(listOf("SHIFT") + "zxcvbnm".map(::letterLabel) + "DEL")
        }
        addRow(listOf("MODE", "EMOJI", "SPACE", "PUNCT", "ENTER"), bottom = true,
            height = if (zhuyinLayout) ZHUYIN_CELL_HEIGHT_DP else KEY_CELL_HEIGHT_DP)
    }

    private fun letterLabel(letter: Char): String =
        if (shiftState == ShiftState.OFF) letter.toString() else letter.uppercase()

    private fun symbolChoices(key: String): SymbolChoices? = when (key) {
        "0" -> SymbolChoices(listOf("₀", "⁰"), 1)
        "1" -> SymbolChoices(listOf("¹", "₁"), 0)
        "2" -> SymbolChoices(listOf("²", "₂"), 0)
        "3" -> SymbolChoices(listOf("³", "₃"), 0)
        "4" -> SymbolChoices(listOf("⁴", "₄"), 0)
        "5" -> SymbolChoices(listOf("⁵", "₅"), 0)
        "6" -> SymbolChoices(listOf("⁶", "₆"), 0)
        "7" -> SymbolChoices(listOf("⁷", "₇"), 0)
        "8" -> SymbolChoices(listOf("⁸", "₈"), 0)
        "9" -> SymbolChoices(listOf("⁹", "₉"), 0)
        "(" -> SymbolChoices(listOf(punctuationText("["), punctuationText("{"), "〈", "《", "【"), 3)
        ")" -> SymbolChoices(listOf(punctuationText("]"), punctuationText("}"), "〉", "】", "》"), 4)
        "-" -> SymbolChoices(listOf(punctuationText("_"), "–", punctuationText("~"),
            if (fullWidthPunctuation) "~" else "～", "—"), 2)
        "\"" -> SymbolChoices(listOf("「", "」", "“", "”", "『", "』"), 0)
        "*" -> SymbolChoices(listOf("·", "•", "※", "×", "÷"), 0)
        "+" -> SymbolChoices(listOf(punctuationText("="), "±", "≠", "≈", "∞"), 0)
        "/" -> SymbolChoices(listOf(punctuationText("\\"), punctuationText("|"), "／", "÷"), 2)
        ":" -> SymbolChoices(listOf("…", "⋯", "："), 0)
        ";" -> SymbolChoices(listOf("；", "…", "⋯"), 1)
        "!" -> SymbolChoices(listOf("¡", "‼", "❗"), 1)
        "?" -> SymbolChoices(listOf("¿", "⁇", "❔"), 0)
        "$" -> SymbolChoices(listOf("¥", "€", "£", "₩", "₹"), 0)
        else -> null
    }

    private fun addRow(keys: List<String>, inset: Int = 0, bottom: Boolean = false, numberHints: Boolean = false,
                       zhuyin: Boolean = false, sideWeight: Float = 0f,
                       height: Int = if (zhuyin) ZHUYIN_CELL_HEIGHT_DP else KEY_CELL_HEIGHT_DP) {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(inset), 0, dp(inset), 0)
        }
        // Half-key spacers keep shorter rows aligned to the same key width.
        if (sideWeight > 0f) row.addView(View(this), LinearLayout.LayoutParams(0, 1, sideWeight))
        keys.forEachIndexed { index, key ->
            val label = when (key) {
                "DEL" -> "⌫"
                "ENTER" -> "↵"
                "SPACE" -> if (ascii) "En" else if (inputScheme == InputScheme.ZHUYIN) "注" else "中"
                "MODE" -> if (symbols) "ABC" else "?123"
                "PUNCT" -> punctuationText(symbolKey.primary)
                "EMOJI" -> ""
                "SHIFT" -> if (shiftState == ShiftState.LOCKED) "⇪" else "⇧"
                "PAGE" -> "${symbolPage + 1}/${SYMBOL_PAGES.size}"
                else -> if (zhuyin) ZHUYIN_LABELS[key] ?: key
                    else if (symbols && key.length == 1 && !key[0].isLetterOrDigit()) punctuationText(key) else key
            }
            val special = key in setOf("DEL", "MODE", "EMOJI", "SHIFT", "PAGE")
            val shifted = key == "SHIFT" && shiftState != ShiftState.OFF
            val action = key == "ENTER"
            val letter = key.singleOrNull()?.takeIf { !zhuyin && it.isLetter() }?.lowercaseChar()
            val alternate = when {
                letter == null -> null
                numberHints -> if (index == 9) "0" else (index + 1).toString()
                else -> LETTER_SYMBOLS[letter]?.let(::punctuationText)
            }
            val punctuationKey = key == "PUNCT"
            val characterKey = !special && !action && key != "SPACE"
            val symbolChoices = if (symbols) symbolChoices(key) else null
            val view = uiTextView().apply {
                text = label
                if (key == "SHIFT") contentDescription = when (shiftState) {
                    ShiftState.OFF -> "Shift，切換下一個字母大寫"
                    ShiftState.ONCE -> "Shift，下一個字母大寫"
                    ShiftState.LOCKED -> "大寫鎖定"
                }
                if (key == "EMOJI") contentDescription = "開啟表情符號"
                if (key == "PAGE") contentDescription = "符號第 ${symbolPage + 1} 頁，共 ${SYMBOL_PAGES.size} 頁，點選翻頁"
                gravity = Gravity.CENTER
                setTextColor(if (action || shifted) palette.onAction else ink)
                textSize = if (punctuationKey || key == "EMOJI") 21f else if (bottom || special) 14f
                    else if (zhuyin) 19f else 21f
                typeface = if (characterKey) UiFonts.displayTypeface(this@IceInputMethodService)
                    else UiFonts.bodyTypeface(this@IceInputMethodService, mediumWeight = special || action)
                if (characterKey) includeFontPadding = false
                if (alternate == null && symbolChoices == null && !punctuationKey) {
                    background = keyBackground(if (action || shifted) actionColor else if (special) specialSurface else keySurface)
                    elevation = 0f
                    if (key == "SPACE") {
                        contentDescription = "空白，左右滑動移動游標，按住不動語音輸入"
                        setOnTouchListener { touched, event -> handleSpaceTouch(touched, event) }
                    } else if (key == "MODE") {
                        contentDescription = if (symbols) "字母鍵盤，向上滑切換中英文" else "符號鍵盤，向上滑切換中英文"
                        setOnTouchListener { touched, event -> handleModeTouch(touched, event) }
                    } else onHapticClick { handleKey(key) }
                }
            }
            val keyView: View = if (key == "SHIFT" || key == "DEL" || key == "ENTER") {
                ImageView(this).apply {
                    val icon = when (key) {
                        "SHIFT" -> if (shiftState == ShiftState.LOCKED) R.drawable.ic_shift_lock else R.drawable.ic_shift_outline
                        "DEL" -> R.drawable.ic_delete_outline
                        else -> if (mediaQueryEditing) R.drawable.ic_search_outline else R.drawable.ic_enter_outline
                    }
                    setImageResource(icon)
                    imageTintList = ColorStateList.valueOf(if (action || shifted) palette.onAction else ink)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    background = keyBackground(if (action || shifted) actionColor else specialSurface)
                    elevation = 0f
                    contentDescription = when (key) {
                        "SHIFT" -> when (shiftState) {
                            ShiftState.OFF -> "Shift，切換下一個字母大寫"
                            ShiftState.ONCE -> "Shift，下一個字母大寫"
                            ShiftState.LOCKED -> "大寫鎖定"
                        }
                        "DEL" -> "刪除，長按連續刪除，上滑清空，下滑復原"
                        else -> if (mediaQueryEditing) "搜尋 ${if (mediaQueryTab == EmojiTab.GIF) "GIF" else "MyGO 梗圖"}" else "確認，長按或上滑換行"
                    }
                    when {
                        key == "DEL" -> setOnTouchListener { touched, event -> handleDeleteTouch(touched, event) }
                        key == "ENTER" && !mediaQueryEditing -> setOnTouchListener { touched, event -> handleEnterTouch(touched, event) }
                        else -> onHapticClick { handleKey(key) }
                    }
                }
            } else if (key == "EMOJI") {
                ImageView(this).apply {
                    setImageResource(R.drawable.ic_emoji_outline)
                    imageTintList = ColorStateList.valueOf(ink)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(5), dp(5), dp(5), dp(5))
                    background = keyBackground(specialSurface)
                    elevation = 0f
                    contentDescription = if (mediaQueryEditing) "返回表情符號" else "開啟表情符號，長按左滑 GIF、右滑 MyGO"
                    if (mediaQueryEditing) onHapticClick {
                        leaveMediaQueryEditing(search = false)
                        emojiTab = EmojiTab.EMOJI
                    } else {
                        onHapticClick { openEmojiPanel() }
                        setOnTouchListener { touched, event -> handleEmojiTouch(touched, event) }
                    }
                }
            } else if (alternate != null) {
                FrameLayout(this).apply {
                    background = keyBackground(keySurface)
                    elevation = 0f
                    contentDescription = "$label，長按選擇或上滑輸入 $alternate"
                    addView(view, FrameLayout.LayoutParams(-1, -1))
                    addKeyHint(alternate)
                    setOnTouchListener { touched, event -> handleLetterKeyTouch(touched, event, key, alternate) }
                }
            } else if (symbolChoices != null) {
                FrameLayout(this).apply {
                    background = keyBackground(keySurface)
                    elevation = 0f
                    val hint = symbolChoices.values[symbolChoices.preferred]
                    contentDescription = "$label，長按選擇符號，上滑輸入 $hint"
                    addView(view, FrameLayout.LayoutParams(-1, -1))
                    addKeyHint(hint)
                    setOnTouchListener { touched, event -> handleSymbolKeyTouch(touched, event, key, symbolChoices) }
                }
            } else if (punctuationKey) {
                FrameLayout(this).apply {
                    background = keyBackground(keySurface)
                    elevation = 0f
                    contentDescription = "符號快捷鍵 ${punctuationText(symbolKey.primary)}，長按選擇或上滑輸入 ${punctuationText(symbolKey.secondary)}"
                    addView(view, FrameLayout.LayoutParams(-1, -1))
                    addKeyHint(punctuationText(symbolKey.secondary))
                    setOnTouchListener { touched, event -> handlePunctuationTouch(touched, event) }
                }
            } else view
            val weight = when (key) {
                "SPACE" -> 4f
                "SHIFT", "DEL", "PAGE" -> 1.55f
                "MODE", "ENTER" -> if (bottom) 1.55f else 1f
                "EMOJI", "PUNCT" -> 1f
                else -> if (bottom) 1.15f else 1f
            }
            // Keep the visual gap, but let the key receive touches across its whole cell.
            keyView.background = InsetDrawable(
                keyView.background,
                dp(KEY_HORIZONTAL_INSET_DP), dp(KEY_VERTICAL_INSET_DP),
                dp(KEY_HORIZONTAL_INSET_DP), dp(KEY_VERTICAL_INSET_DP)
            )
            keyView.stateListAnimator = keyPressAnimator()
            row.addView(keyView, LinearLayout.LayoutParams(0, dp(height), weight))
        }
        if (sideWeight > 0f) row.addView(View(this), LinearLayout.LayoutParams(0, 1, sideWeight))
        containerAdd(row)
    }

    private fun handleLetterKeyTouch(view: View, event: MotionEvent, key: String, alternate: String): Boolean {
        updateKeyPressed(view, event)
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val startX = event.rawX
                val startY = event.rawY
                val token = generation
                lateinit var gesture: NumberKeyGesture
                val longPress = Runnable {
                    if (!gesture.cancelled && token == generation && view.isAttachedToWindow) {
                        gesture.held = true
                        gesture.choice = initialHoldChoice(key)
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        showHoldPopup(view, key.lowercase(), alternate)
                    }
                }
                gesture = NumberKeyGesture(startX, startY, longPress)
                view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                view.tag = gesture
                return true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val gesture = view.tag as? NumberKeyGesture ?: return true
                val dx = event.rawX - gesture.startX
                val dy = event.rawY - gesture.startY
                if (!gesture.held && !gesture.cancelled) {
                    if (dy < -dp(24) && abs(dy) > abs(dx)) {
                        view.removeCallbacks(gesture.longPress)
                        gesture.cancelled = true
                        gesture.swipeAlternate = true
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        showChoicePopup(view, listOf(alternate), 0)
                    } else if ((abs(dx) > slop && abs(dx) > abs(dy)) || dy > slop) {
                        view.removeCallbacks(gesture.longPress)
                        gesture.cancelled = true
                    }
                }
                if (gesture.held && event.actionMasked != MotionEvent.ACTION_CANCEL) {
                    val choice = holdChoiceForDrag(key, dx)
                    gesture.choice = choice
                    updateHoldChoice(choice)
                }
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    view.removeCallbacks(gesture.longPress)
                    view.tag = null
                    if (gesture.swipeAlternate) {
                        holdPopup?.visibility = View.GONE
                        if (event.actionMasked == MotionEvent.ACTION_UP) {
                            if (shiftState == ShiftState.ONCE) {
                                shiftState = ShiftState.OFF
                                renderKeys()
                            }
                            commitLiteral(alternate)
                        }
                    } else if (gesture.held) {
                        val value = holdValues.getOrNull(gesture.choice)
                        holdPopup?.visibility = View.GONE
                        if (event.actionMasked == MotionEvent.ACTION_UP && value != null) {
                            if (shiftState == ShiftState.ONCE) {
                                shiftState = ShiftState.OFF
                                renderKeys()
                            }
                            commitLiteral(value)
                        }
                    } else if (event.actionMasked == MotionEvent.ACTION_UP && !gesture.cancelled) {
                        handleKey(key)
                    }
                }
                return true
            }
        }
        return true
    }

    private fun handleEmojiTouch(view: View, event: MotionEvent): Boolean {
        updateKeyPressed(view, event)
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val token = generation
                lateinit var gesture: NumberKeyGesture
                val longPress = Runnable {
                    if (!gesture.cancelled && token == generation && view.isAttachedToWindow) {
                        gesture.held = true
                        gesture.choice = 1
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        showChoicePopup(view, listOf("GIF", "EMOJI", "MyGO"), 1)
                    }
                }
                gesture = NumberKeyGesture(event.rawX, event.rawY, longPress)
                view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                view.tag = gesture
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val gesture = view.tag as? NumberKeyGesture ?: return true
                val dx = event.rawX - gesture.startX
                val dy = event.rawY - gesture.startY
                if (!gesture.held && !gesture.cancelled && (abs(dx) > slop || abs(dy) > slop)) {
                    view.removeCallbacks(gesture.longPress)
                    gesture.cancelled = true
                }
                if (gesture.held && event.actionMasked != MotionEvent.ACTION_CANCEL) {
                    val choice = when {
                        dx < -dp(23) -> 0
                        dx > dp(23) -> 2
                        else -> 1
                    }
                    if (choice != gesture.choice) {
                        gesture.choice = choice
                        updateHoldChoice(choice)
                    }
                }
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    view.removeCallbacks(gesture.longPress)
                    view.tag = null
                    holdPopup?.visibility = View.GONE
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        if (gesture.held) when (gesture.choice) {
                            0 -> openMediaSearch(EmojiTab.GIF)
                            2 -> openMediaSearch(EmojiTab.MYGO)
                            else -> openEmojiPanel()
                        } else if (!gesture.cancelled) openEmojiPanel()
                    }
                }
            }
        }
        return true
    }

    private fun handleEmojiVariantTouch(
        view: View, event: MotionEvent, selectedEmoji: String, variants: EmojiVariantChoices
    ): Boolean {
        updateKeyPressed(view, event)
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val token = generation
                lateinit var gesture: NumberKeyGesture
                val longPress = Runnable {
                    if (!gesture.cancelled && token == generation && view.isAttachedToWindow) {
                        gesture.held = true
                        if (variants.choices.size > 18) {
                            emojiReturnPosition = (view.parent as? GridView)?.firstVisiblePosition ?: 0
                            emojiVariantPage = variants
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            renderEmojiPage()
                            return@Runnable
                        }
                        gesture.choice = variants.choices.indexOf(selectedEmoji).takeIf { it >= 0 }
                            ?: variants.choices.indexOf(variants.base).coerceAtLeast(0)
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        showChoicePopup(view, variants.choices, gesture.choice, emojiChoices = true)
                    }
                }
                gesture = NumberKeyGesture(event.rawX, event.rawY, longPress)
                view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                view.tag = gesture
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val gesture = view.tag as? NumberKeyGesture ?: return true
                val dx = event.rawX - gesture.startX
                val dy = event.rawY - gesture.startY
                if (!gesture.held && !gesture.cancelled && (abs(dx) > slop || abs(dy) > slop)) {
                    view.removeCallbacks(gesture.longPress)
                    gesture.cancelled = true
                }
                if (gesture.held && variants.choices.size <= 18 &&
                    event.actionMasked != MotionEvent.ACTION_CANCEL &&
                    (abs(dx) > dp(8) || abs(dy) > dp(8))) {
                    val popup = holdPopup
                    if (popup != null && holdValues.isNotEmpty()) {
                        val position = IntArray(2)
                        popup.getLocationOnScreen(position)
                        val columns = minOf(6, holdValues.size)
                        val x = event.rawX - position[0] - dp(4)
                        val y = event.rawY - position[1] - dp(4)
                        val row = if (y >= 0 && y < popup.height - dp(8)) {
                            (y / dp(48)).toInt()
                        } else if (y >= popup.height && abs(dx) > dp(8)) {
                            gesture.choice / columns
                        } else -1
                        if (row >= 0 && x >= 0 && x < columns * dp(48)) {
                            val choice = row * columns + (x / dp(48)).toInt()
                            if (choice in holdValues.indices && choice != gesture.choice) {
                                gesture.choice = choice
                                updateHoldChoice(choice)
                            }
                        }
                    }
                }
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    view.removeCallbacks(gesture.longPress)
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    view.tag = null
                    holdPopup?.visibility = View.GONE
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        if (gesture.held && variants.choices.size <= 18) {
                            holdValues.getOrNull(gesture.choice)?.let(::commitEmoji)
                        }
                        else if (!gesture.held && !gesture.cancelled) commitEmoji(selectedEmoji)
                    }
                }
            }
        }
        return true
    }

    private fun handleDeleteTouch(view: View, event: MotionEvent): Boolean {
        updateKeyPressed(view, event)
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val token = generation
                lateinit var gesture: DeleteKeyGesture
                val repeat = object : Runnable {
                    override fun run() {
                        if (view.tag !== gesture || gesture.cancelled || gesture.choice != -1 ||
                            token != generation || !view.isAttachedToWindow) return
                        gesture.repeated = true
                        handleKey("DEL")
                        view.postDelayed(this, DELETE_REPEAT_INTERVAL_MS)
                    }
                }
                val longPress = Runnable {
                    if (!gesture.cancelled && token == generation && view.isAttachedToWindow) {
                        gesture.held = true
                        gesture.choice = -1
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        repeat.run()
                    }
                }
                gesture = DeleteKeyGesture(event.rawX, event.rawY, longPress, repeat)
                view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                view.tag = gesture
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val gesture = view.tag as? DeleteKeyGesture ?: return true
                val dx = event.rawX - gesture.startX
                val dy = event.rawY - gesture.startY
                if (!gesture.held && !gesture.cancelled && event.actionMasked != MotionEvent.ACTION_CANCEL) {
                    when {
                        abs(dy) > dp(24) && abs(dy) > abs(dx) -> {
                            view.removeCallbacks(gesture.longPress)
                            gesture.held = true
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        }
                        abs(dx) > slop && abs(dx) > abs(dy) -> {
                            view.removeCallbacks(gesture.longPress)
                            gesture.cancelled = true
                        }
                    }
                }
                if (gesture.held && event.actionMasked != MotionEvent.ACTION_CANCEL) {
                    val choice = when {
                        dy < -dp(24) -> 0
                        dy > dp(24) -> 1
                        else -> -1
                    }
                    if (choice != gesture.choice) {
                        gesture.choice = choice
                        if (choice == -1) {
                            holdPopup?.visibility = View.GONE
                            view.postDelayed(gesture.repeat, DELETE_REPEAT_INTERVAL_MS)
                        } else {
                            view.removeCallbacks(gesture.repeat)
                            showDeleteActionPopup(view, choice)
                        }
                    }
                }
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    view.removeCallbacks(gesture.longPress)
                    view.removeCallbacks(gesture.repeat)
                    view.tag = null
                    holdPopup?.visibility = View.GONE
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        if (gesture.held) when (gesture.choice) {
                            0 -> clearAllText()
                            1 -> restoreClearedText()
                            else -> if (!gesture.repeated) handleKey("DEL")
                        } else if (!gesture.cancelled) handleKey("DEL")
                    }
                }
            }
        }
        return true
    }

    private fun handleEnterTouch(view: View, event: MotionEvent): Boolean {
        updateKeyPressed(view, event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val token = generation
                lateinit var gesture: NumberKeyGesture
                val longPress = Runnable {
                    if (!gesture.cancelled && token == generation && view.isAttachedToWindow) {
                        gesture.held = true
                        gesture.choice = 0
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        showChoicePopup(view, listOf("\\n"), 0)
                    }
                }
                gesture = NumberKeyGesture(event.rawX, event.rawY, longPress, choice = -1)
                view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                view.tag = gesture
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val gesture = view.tag as? NumberKeyGesture ?: return true
                val dx = event.rawX - gesture.startX
                val dy = event.rawY - gesture.startY
                if (!gesture.held && !gesture.cancelled && event.actionMasked != MotionEvent.ACTION_CANCEL) {
                    when {
                        dy < -dp(24) && abs(dy) > abs(dx) -> {
                            view.removeCallbacks(gesture.longPress)
                            gesture.held = true
                            gesture.choice = 0
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            showChoicePopup(view, listOf("\\n"), 0)
                        }
                        abs(dx) > dp(24) || dy > dp(24) -> {
                            view.removeCallbacks(gesture.longPress)
                            gesture.cancelled = true
                        }
                    }
                }
                if (gesture.held && event.actionMasked != MotionEvent.ACTION_CANCEL) {
                    // Sliding back down below the key releases the newline choice.
                    val choice = if (dy > dp(24)) -1 else 0
                    if (choice != gesture.choice) {
                        gesture.choice = choice
                        updateHoldChoice(choice)
                    }
                }
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    view.removeCallbacks(gesture.longPress)
                    view.tag = null
                    holdPopup?.visibility = View.GONE
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        if (gesture.held) {
                            if (gesture.choice == 0) commitLiteral("\n")
                        } else if (!gesture.cancelled) handleKey("ENTER")
                    }
                }
            }
        }
        return true
    }

    /** 中/En menu on the ?123 key; the other language is preselected so releasing switches. */
    private fun openLanguageMenu(view: View, gesture: SwipeKeyGesture) {
        gesture.active = true
        gesture.choice = if (ascii) 0 else 1
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        showChoicePopup(view, listOf("中", "En"), gesture.choice)
    }

    private fun handleModeTouch(view: View, event: MotionEvent): Boolean {
        updateKeyPressed(view, event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val token = generation
                val gesture = SwipeKeyGesture(event.rawX, event.rawY)
                // Holding the key opens the same language menu as swiping up.
                gesture.longPress = Runnable {
                    if (view.tag === gesture && !gesture.active && token == generation && view.isAttachedToWindow) {
                        openLanguageMenu(view, gesture)
                    }
                }
                view.tag = gesture
                view.postDelayed(gesture.longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val gesture = view.tag as? SwipeKeyGesture ?: return true
                val dx = event.rawX - gesture.startX
                val dy = event.rawY - gesture.startY
                if (!gesture.active && dy < -dp(24) && abs(dy) > abs(dx)) openLanguageMenu(view, gesture)
                // A sideways drag before the menu opens is not a hold.
                if (!gesture.active && abs(dx) > dp(24)) gesture.longPress?.let(view::removeCallbacks)
                if (event.actionMasked != MotionEvent.ACTION_MOVE) gesture.longPress?.let(view::removeCallbacks)
                if (gesture.active && event.actionMasked != MotionEvent.ACTION_CANCEL) {
                    val choice = when {
                        dx < -dp(24) -> 0
                        dx > dp(24) -> 1
                        else -> if (ascii) 0 else 1
                    }
                    if (choice != gesture.choice) {
                        gesture.choice = choice
                        updateHoldChoice(choice)
                    }
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    view.tag = null
                    if (gesture.active) {
                        holdPopup?.visibility = View.GONE
                        if ((gesture.choice == 1) != ascii) toggleLanguage()
                    } else if (abs(dx) <= dp(24) && abs(dy) <= dp(24)) {
                        handleKey("MODE")
                    }
                } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    view.tag = null
                    holdPopup?.visibility = View.GONE
                }
            }
        }
        return true
    }

    private fun handleSpaceTouch(view: View, event: MotionEvent): Boolean {
        updateKeyPressed(view, event)
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val token = generation
                lateinit var gesture: SpaceCursorGesture
                val longPress = Runnable {
                    if (!gesture.cancelled && token == generation && view.isAttachedToWindow) {
                        gesture.held = true
                        gesture.voice = latestState?.inputText.isNullOrEmpty() && startVoiceHold()
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                }
                val stepWidth = dp(SpaceCursorSettings.stepDp(SpaceCursorSettings.read(this)))
                gesture = SpaceCursorGesture(event.rawX, event.rawY, longPress, stepWidth)
                view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                view.tag = gesture
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val gesture = view.tag as? SpaceCursorGesture ?: return true
                val dx = event.rawX - gesture.startX
                val dy = event.rawY - gesture.startY
                if (!gesture.held && !gesture.cancelled) {
                    if (abs(dx) > dp(20) && abs(dx) > abs(dy)) {
                        view.removeCallbacks(gesture.longPress)
                        gesture.held = true
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    } else if (abs(dy) > slop) {
                        view.removeCallbacks(gesture.longPress)
                        gesture.cancelled = true
                    }
                }
                if (gesture.voice) {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_MOVE -> updateVoiceZone(event.rawX)
                        MotionEvent.ACTION_UP -> releaseVoiceHold(cancelled = false)
                        MotionEvent.ACTION_CANCEL -> releaseVoiceHold(cancelled = true)
                    }
                    if (event.actionMasked != MotionEvent.ACTION_MOVE) view.tag = null
                    return true
                }
                if (gesture.held && event.actionMasked != MotionEvent.ACTION_CANCEL) {
                    val step = (dx / gesture.stepWidthPx).toInt()
                    val movement = step - gesture.lastStep
                    if (movement != 0) {
                        gesture.lastStep = step
                        moveCursor(movement)
                    }
                }
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    view.removeCallbacks(gesture.longPress)
                    view.tag = null
                    if (event.actionMasked == MotionEvent.ACTION_UP && !gesture.held && !gesture.cancelled) {
                        handleKey("SPACE")
                    }
                }
            }
        }
        return true
    }

    private fun moveCursor(steps: Int) {
        if (steps == 0 || mediaQueryEditing) return
        val token = generation
        if (!secure && !latestState?.inputText.isNullOrEmpty()) {
            RimeManager.run(this, { engine ->
                var state = engine.getProcessResult(false)
                repeat(abs(steps).coerceAtMost(32)) {
                    val inputLength = state.inputText.toByteArray(Charsets.UTF_8).size
                    if (inputLength == 0 || (steps < 0 && state.caretPos <= 0) ||
                        (steps > 0 && state.caretPos >= inputLength)) return@repeat
                    state = engine.processKeyAndGetResult(if (steps < 0) RIME_CHAR_LEFT else RIME_CHAR_RIGHT, 0)
                }
                state
            }) { result ->
                if (token == generation) result.onSuccess(::applyRimeResult)
            }
        } else {
            moveEditorCursor(steps)
        }
    }

    private fun moveEditorCursor(steps: Int) {
        val connection = currentInputConnection ?: return
        val extracted = connection.getExtractedText(ExtractedTextRequest(), 0)
        if (extracted?.text != null) {
            val text = extracted.text.toString()
            var position = extracted.selectionEnd.coerceIn(0, text.length)
            repeat(abs(steps).coerceAtMost(32)) {
                position = when {
                    steps < 0 && position > 0 -> Character.offsetByCodePoints(text, position, -1)
                    steps > 0 && position < text.length -> Character.offsetByCodePoints(text, position, 1)
                    else -> position
                }
            }
            val target = extracted.startOffset + position
            connection.setSelection(target, target)
        } else {
            val keyCode = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            repeat(abs(steps).coerceAtMost(32)) {
                connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            }
        }
    }

    private fun showHoldPopup(keyView: View, letter: String, alternate: String) {
        val shifted = shiftState != ShiftState.OFF
        val values = when (letter) {
            "q", "a", "z" -> listOf(alternate, letter, letter.uppercase())
            "p", "l", "m" -> listOf(letter.uppercase(), letter, alternate)
            else -> if (shifted) listOf(letter, alternate, letter.uppercase())
                else listOf(letter.uppercase(), alternate, letter)
        }
        showChoicePopup(keyView, values, initialHoldChoice(letter))
    }

    private fun showChoicePopup(keyView: View, values: List<String>, selected: Int,
                                emojiChoices: Boolean = false, anchorSelected: Boolean = false) {
        val popup = holdPopup ?: return
        val frame = inputFrame ?: return
        holdValues = values
        popup.removeAllViews()
        val columns = if (emojiChoices) minOf(6, values.size) else values.size
        val rows = if (emojiChoices) (values.size + columns - 1) / columns else 1
        popup.orientation = if (rows > 1) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        val options = mutableListOf<View>()
        values.chunked(columns).forEach { rowValues ->
            val row = if (rows > 1) LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                popup.addView(this, LinearLayout.LayoutParams(-1, 0, 1f))
            } else popup
            rowValues.forEach { value ->
                val option: View = if (value == "EMOJI" || value == "MyGO") ImageView(this).apply {
                    setImageResource(if (value == "MyGO") R.drawable.ic_mygo_mark else R.drawable.ic_emoji_outline)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    val inset = if (value == "MyGO") dp(4) else dp(7)
                    setPadding(inset, inset, inset, inset)
                    contentDescription = if (value == "MyGO") "MyGO 梗圖搜尋" else "表情符號"
                } else uiTextView().apply {
                    text = value
                    gravity = Gravity.CENTER
                    textSize = if (emojiChoices) 25f else when (value) {
                        "，", "。" -> 27f
                        "MyGO" -> 15f
                        "GIF" -> 17f
                        else -> 19f
                    }
                    typeface = when {
                        emojiChoices -> Typeface.DEFAULT
                        value in setOf("GIF", "中", "En") -> UiFonts.bodyTypeface(this@IceInputMethodService, mediumWeight = true)
                        else -> UiFonts.displayTypeface(this@IceInputMethodService)
                    }
                    if (!emojiChoices) includeFontPadding = false
                }
                row.addView(option, LinearLayout.LayoutParams(if (rows > 1) dp(48) else 0, -1,
                    if (rows > 1) 0f else 1f))
                options.add(option)
            }
        }
        holdOptions = options
        updateHoldChoice(selected)
        val keyPosition = IntArray(2)
        val framePosition = IntArray(2)
        keyView.getLocationOnScreen(keyPosition)
        frame.getLocationOnScreen(framePosition)
        val width = dp(48 * columns + if (emojiChoices) 8 else 0)
        popup.layoutParams = (popup.layoutParams as FrameLayout.LayoutParams).apply {
            this.width = width
            height = dp(if (emojiChoices) 48 * rows + 8 else 52)
            leftMargin = (keyPosition[0] - framePosition[0] + (keyView.width * cardScale).toInt() / 2 -
                if (emojiChoices) dp(4 + 48 * (selected % columns) + 24)
                else if (anchorSelected) dp(48 * selected + 24)
                else width / 2)
                .coerceIn(0, (frame.width - width).coerceAtLeast(0))
            topMargin = (keyPosition[1] - framePosition[1] - height - dp(4)).coerceAtLeast(0)
        }
        popup.visibility = View.VISIBLE
        popup.popIn()
    }

    private fun showDeleteActionPopup(keyView: View, choice: Int) {
        val popup = holdPopup ?: return
        val frame = inputFrame ?: return
        popup.orientation = LinearLayout.HORIZONTAL
        popup.removeAllViews()
        // An icon centres exactly in the pill, unlike text with the font's extra line padding.
        holdOptions = listOf(ImageView(this).apply {
            setImageResource(if (choice == 0) R.drawable.ic_clear_all else R.drawable.ic_undo)
            imageTintList = ColorStateList.valueOf(palette.onAction)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = if (choice == 0) "清空" else "復原"
            background = rounded(actionColor, 8)
            popup.addView(this, LinearLayout.LayoutParams(-1, -1))
        })
        val keyPosition = IntArray(2)
        val framePosition = IntArray(2)
        keyView.getLocationOnScreen(keyPosition)
        frame.getLocationOnScreen(framePosition)
        val width = dp(64)
        val height = dp(44)
        val keyTop = keyPosition[1] - framePosition[1]
        popup.layoutParams = (popup.layoutParams as FrameLayout.LayoutParams).apply {
            this.width = width
            this.height = height
            leftMargin = (keyPosition[0] - framePosition[0] + (keyView.width * cardScale).toInt() / 2 - width / 2)
                .coerceIn(0, (frame.width - width).coerceAtLeast(0))
            topMargin = if (choice == 0) {
                (keyTop - height - dp(5)).coerceAtLeast(0)
            } else {
                (keyTop + (keyView.height * cardScale).toInt() + dp(5)).coerceAtMost((frame.height - height).coerceAtLeast(0))
            }
        }
        popup.visibility = View.VISIBLE
        popup.popIn()
    }

    private fun handleSymbolKeyTouch(view: View, event: MotionEvent, key: String,
                                     choices: SymbolChoices): Boolean {
        updateKeyPressed(view, event)
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val token = generation
                lateinit var gesture: NumberKeyGesture
                val longPress = Runnable {
                    if (!gesture.cancelled && token == generation && view.isAttachedToWindow) {
                        gesture.held = true
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        showChoicePopup(view, choices.values, choices.preferred, anchorSelected = true)
                        gesture.choice = holdChoiceAt(gesture.startX)
                        updateHoldChoice(gesture.choice)
                    }
                }
                gesture = NumberKeyGesture(event.rawX, event.rawY, longPress)
                view.tag = gesture
                view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val gesture = view.tag as? NumberKeyGesture ?: return true
                val dx = event.rawX - gesture.startX
                val dy = event.rawY - gesture.startY
                if (!gesture.held && !gesture.cancelled && event.actionMasked != MotionEvent.ACTION_CANCEL) {
                    if (dy < -dp(24) && abs(dy) > abs(dx)) {
                        view.removeCallbacks(gesture.longPress)
                        gesture.cancelled = true
                        gesture.swipeAlternate = true
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        showChoicePopup(view, listOf(choices.values[choices.preferred]), 0)
                    } else if ((abs(dx) > slop && abs(dx) > abs(dy)) || dy > slop) {
                        view.removeCallbacks(gesture.longPress)
                        gesture.cancelled = true
                    }
                }
                if (gesture.held && event.actionMasked != MotionEvent.ACTION_CANCEL) {
                    val choice = holdChoiceAt(event.rawX)
                    if (choice != gesture.choice) {
                        gesture.choice = choice
                        updateHoldChoice(choice)
                    }
                }
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    view.removeCallbacks(gesture.longPress)
                    view.tag = null
                    holdPopup?.visibility = View.GONE
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        when {
                            gesture.swipeAlternate -> commitLiteral(choices.values[choices.preferred])
                            gesture.held -> choices.values.getOrNull(gesture.choice)?.let(::commitLiteral)
                            !gesture.cancelled -> handleKey(key)
                        }
                    }
                }
            }
        }
        return true
    }

    private fun handlePunctuationTouch(view: View, event: MotionEvent): Boolean {
        updateKeyPressed(view, event)
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val startX = event.rawX
                val startY = event.rawY
                val token = generation
                lateinit var gesture: NumberKeyGesture
                val longPress = Runnable {
                    if (!gesture.cancelled && token == generation && view.isAttachedToWindow) {
                        gesture.held = true
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        showChoicePopup(view, symbolKey.menu.map(::punctuationText), symbolMenuStart(),
                            anchorSelected = true)
                        // The popup may be pushed in from the keyboard edge, so pick what is under the finger.
                        gesture.choice = holdChoiceAt(startX)
                        updateHoldChoice(gesture.choice)
                    }
                }
                gesture = NumberKeyGesture(startX, startY, longPress)
                view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                view.tag = gesture
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val gesture = view.tag as? NumberKeyGesture ?: return true
                val dx = event.rawX - gesture.startX
                val dy = event.rawY - gesture.startY
                if (!gesture.held && !gesture.cancelled) {
                    if (dy < -dp(24) && abs(dy) > abs(dx)) {
                        view.removeCallbacks(gesture.longPress)
                        gesture.cancelled = true
                        gesture.swipeAlternate = true
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        showChoicePopup(view, listOf(punctuationText(symbolKey.secondary)), 0)
                    } else if ((abs(dx) > slop && abs(dx) > abs(dy)) || dy > slop) {
                        view.removeCallbacks(gesture.longPress)
                        gesture.cancelled = true
                    }
                }
                if (gesture.held && event.actionMasked != MotionEvent.ACTION_CANCEL) {
                    val choice = holdChoiceAt(event.rawX)
                    if (choice != gesture.choice) {
                        gesture.choice = choice
                        updateHoldChoice(choice)
                    }
                }
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    view.removeCallbacks(gesture.longPress)
                    view.tag = null
                    holdPopup?.visibility = View.GONE
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        if (gesture.swipeAlternate) commitLiteral(punctuationText(symbolKey.secondary))
                        else if (gesture.held) holdValues.getOrNull(gesture.choice)?.let(::commitLiteral)
                        else if (!gesture.cancelled) commitLiteral(punctuationText(symbolKey.primary))
                    }
                }
            }
        }
        return true
    }

    // Long press opens on the swipe-up symbol when the menu has it, as the original key preselected the period.
    private fun symbolMenuStart(): Int = symbolKey.menu.indexOf(symbolKey.secondary).takeIf { it >= 0 }
        ?: symbolKey.menu.indexOf(symbolKey.primary).coerceAtLeast(0)

    /** Index of the single-row hold popup option under a screen x coordinate. */
    private fun holdChoiceAt(rawX: Float): Int {
        val popup = holdPopup ?: return 0
        val frame = inputFrame ?: return 0
        val params = popup.layoutParams as? FrameLayout.LayoutParams ?: return 0
        if (holdValues.isEmpty()) return 0
        val origin = IntArray(2).also(frame::getLocationOnScreen)
        val optionWidth = (params.width - popup.paddingLeft - popup.paddingRight).toFloat() / holdValues.size
        val x = rawX - origin[0] - params.leftMargin - popup.paddingLeft
        return (x / optionWidth).toInt().coerceIn(0, holdValues.lastIndex)
    }

    private fun initialHoldChoice(key: String): Int = when (key.lowercase()) {
        "q", "a", "z" -> 0
        "p", "l", "m" -> 2
        else -> 1
    }

    private fun holdChoiceForDrag(key: String, dx: Float): Int = when (key.lowercase()) {
        "q", "a", "z" -> when {
            dx > dp(64) -> 2
            dx > dp(20) -> 1
            else -> 0
        }
        "p", "l", "m" -> when {
            dx < -dp(64) -> 0
            dx < -dp(20) -> 1
            else -> 2
        }
        else -> when {
            dx < -dp(24) -> 0
            dx > dp(24) -> 2
            else -> 1
        }
    }

    private fun updateHoldChoice(selected: Int) {
        holdOptions.forEachIndexed { index, option ->
            val color = if (index == selected) palette.onAction else ink
            when (option) {
                is TextView -> option.setTextColor(color)
                is ImageView -> option.imageTintList = ColorStateList.valueOf(color)
            }
            option.background = rounded(if (index == selected) actionColor else Color.TRANSPARENT, 8)
        }
    }

    private class NumberKeyGesture(
        val startX: Float,
        val startY: Float,
        val longPress: Runnable,
        var held: Boolean = false,
        var cancelled: Boolean = false,
        var swipeAlternate: Boolean = false,
        var choice: Int = 1
    )

    private class DeleteKeyGesture(
        val startX: Float,
        val startY: Float,
        val longPress: Runnable,
        val repeat: Runnable,
        var held: Boolean = false,
        var cancelled: Boolean = false,
        var choice: Int = -1,
        var repeated: Boolean = false
    )

    private class SwipeKeyGesture(
        val startX: Float,
        val startY: Float,
        var active: Boolean = false,
        var choice: Int = 0,
        var longPress: Runnable? = null
    )

    private class SpaceCursorGesture(
        val startX: Float,
        val startY: Float,
        val longPress: Runnable,
        val stepWidthPx: Int,
        var held: Boolean = false,
        var cancelled: Boolean = false,
        var lastStep: Int = 0,
        var voice: Boolean = false
    )

    private class PreeditCaretSpan(
        private val color: Int,
        private val widthPx: Int,
        private val linePx: Int,
        private val insetPx: Int
    ) : ReplacementSpan() {
        // Toggled for blinking; keeps the span's width so text never shifts.
        var visible = true

        override fun getSize(
            paint: Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?
        ): Int = widthPx

        override fun draw(
            canvas: Canvas,
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint
        ) {
            if (!visible) return
            val previousColor = paint.color
            val previousStyle = paint.style
            paint.color = color
            paint.style = Paint.Style.FILL
            val left = x + (widthPx - linePx) / 2f
            canvas.drawRoundRect(
                left, y + paint.ascent() + insetPx,
                left + linePx, y + paint.descent() - insetPx,
                linePx / 2f, linePx / 2f, paint
            )
            paint.color = previousColor
            paint.style = previousStyle
        }
    }

    private fun containerAdd(row: LinearLayout) {
        rows?.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    // Short, transform-only animations; they follow the system animation scale, so "animations off" disables them.
    private fun View.riseIn(distance: Float = dp(12).toFloat(), duration: Long = 170) {
        animate().cancel()
        alpha = 0f
        translationY = distance
        animate().alpha(1f).translationY(0f).setDuration(duration)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
    }

    private fun View.slideIn() {
        animate().cancel()
        alpha = 0f
        translationX = dp(24).toFloat()
        animate().alpha(1f).translationX(0f).setDuration(170)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
    }

    private fun View.popIn() {
        animate().cancel()
        // Grow out of the key below, which sits under the popup's bottom edge.
        pivotX = (layoutParams?.width?.takeIf { it > 0 } ?: width) / 2f
        pivotY = (layoutParams?.height?.takeIf { it > 0 } ?: height).toFloat()
        alpha = 0f
        scaleX = 0.82f
        scaleY = 0.82f
        animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(110)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.4f)).start()
    }

    private fun keyPressAnimator(): android.animation.StateListAnimator {
        fun scaleTo(value: Float, duration: Long) = android.animation.ObjectAnimator.ofPropertyValuesHolder(
            null as Any?,
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, value),
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, value)
        ).setDuration(duration)
        return android.animation.StateListAnimator().apply {
            addState(intArrayOf(android.R.attr.state_pressed), scaleTo(0.94f, 60))
            addState(intArrayOf(), scaleTo(1f, 120))
        }
    }

    private fun View.onHapticClick(action: () -> Unit) {
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            action()
        }
    }

    private fun updateKeyPressed(view: View, event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.isPressed = true
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.isPressed = false
        }
    }

    private fun FrameLayout.addKeyHint(value: String) {
        // Size to the font's line height so digits, punctuation and scripts cannot
        // be clipped by the old 17 dp box. The key background already insets the top.
        addView(uiTextView().apply {
            text = value
            textSize = 10f
            typeface = UiFonts.displayTypeface(this@IceInputMethodService)
            includeFontPadding = false
            isSingleLine = true
            gravity = Gravity.TOP or Gravity.RIGHT
            setTextColor(muted)
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.RIGHT).apply {
            rightMargin = dp(4)
        })
    }

    private fun uiTextView() = TextView(this).apply {
        typeface = UiFonts.bodyTypeface(this@IceInputMethodService)
    }

    private fun keyBackground(color: Int): StateListDrawable = StateListDrawable().apply {
        val pressedColor = when (color) {
            keySurface -> palette.keyPressed
            actionColor -> palette.actionPressed
            else -> palette.insetPressed
        }
        addState(intArrayOf(android.R.attr.state_pressed), rounded(pressedColor, 8))
        addState(intArrayOf(), rounded(color, 8).apply {
            if (color != Color.TRANSPARENT && color != actionColor) setStroke(dp(1), palette.outline)
        })
    }

    private fun candidateBackground(radius: Int): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), rounded(palette.insetPressed, radius))
        addState(intArrayOf(), rounded(keySurface, radius))
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
