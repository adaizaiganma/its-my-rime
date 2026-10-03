package com.kingzcheung.xime

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ClipboardManager
import android.content.ClipDescription
import android.content.ClipData
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
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
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.giphy.sdk.core.models.Media
import com.giphy.sdk.ui.Giphy
import com.giphy.sdk.ui.pagination.GPHContent
import com.giphy.sdk.ui.themes.GPHTheme
import com.giphy.sdk.ui.views.GPHGridCallback
import com.giphy.sdk.ui.views.GiphyGridView
import com.kingzcheung.xime.rime.RimeCandidate
import com.kingzcheung.xime.rime.RimeProcessResult
import kotlin.math.abs
import kotlin.math.roundToInt
import java.io.File
import java.util.concurrent.Executors

private const val BACKSPACE = 0xff08
private const val RETURN = 0xff0d
private const val RIME_CHAR_LEFT = 0xff96
private const val RIME_CHAR_RIGHT = 0xff98
private const val SHIFT_MASK = 1
private const val DELETE_REPEAT_INTERVAL_MS = 65L
private val PINYIN_COMMENT = Regex("［([A-Za-z0-9üÜ'\\s]+)］")
private val LETTER_SYMBOLS = mapOf(
    'a' to "@", 's' to "#", 'd' to "$", 'f' to "_", 'g' to "&",
    'h' to "-", 'j' to "+", 'k' to "(", 'l' to ")",
    'z' to "*", 'x' to "\"", 'c' to "'", 'v' to ":", 'b' to ";",
    'n' to "!", 'm' to "?"
)

private enum class ShiftState { OFF, ONCE, LOCKED }
private enum class EmojiTab { EMOJI, GIF, MYGO }

private data class KeyOutcome(
    val state: RimeProcessResult? = null,
    val direct: String? = null,
    val delete: Boolean = false,
    val enter: Boolean = false
)

class IceInputMethodService : InputMethodService() {
    private var darkMode = false
    private val ink get() = if (darkMode) Color.rgb(232, 240, 252) else Color.rgb(28, 47, 74)
    private val muted get() = if (darkMode) Color.rgb(166, 188, 216) else Color.rgb(105, 122, 146)
    private val blue get() = if (darkMode) Color.rgb(145, 185, 250) else Color.rgb(48, 95, 166)
    private val actionBlue get() = if (darkMode) Color.rgb(56, 107, 184) else blue
    private val candidateBlue get() = if (darkMode) Color.rgb(207, 225, 255) else Color.rgb(31, 75, 137)
    private val keyboardSurface get() = if (darkMode) Color.rgb(17, 29, 46) else Color.rgb(241, 245, 251)
    private val keySurface get() = if (darkMode) Color.rgb(38, 57, 82) else Color.WHITE
    private val specialSurface get() = if (darkMode) Color.rgb(47, 74, 108) else Color.rgb(222, 232, 248)
    private var root: LinearLayout? = null
    private var inputFrame: FrameLayout? = null
    private var topBar: FrameLayout? = null
    private var emptyToolbar: LinearLayout? = null
    private var candidateBar: LinearLayout? = null
    private var caption: TextView? = null
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
    private var emojiOpen = false
    private var emojiTab = EmojiTab.EMOJI
    private var emojiCategory = -1
    private var emojiCategoryTransitioning = false
    private var emojiVariantPage: EmojiVariantChoices? = null
    private var emojiReturnPosition = 0
    private var mediaQueryEditing = false
    private var mediaQueryTab = EmojiTab.MYGO
    private var gifQuery = ""
    private var giphyConfigured = false
    private var mygoQuery = ""
    private var mediaPreedit = ""
    private val mygoResults = mutableListOf<MyGoImage>()
    private var mygoPage = 0
    private var mygoHasNext = false
    private var mygoLoading = false
    private var mygoError: String? = null
    private var mygoRequest = 0
    private var mygoScrollY = 0
    private var mygoResultsScroll: ScrollView? = null
    private val mygoExecutor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var expandRequest = 0
    private var latestState: RimeProcessResult? = null
    private var symbols = false
    private var secure = false
    private var ascii = false

    private var chineseFullPunctuation = true

    private var englishFullPunctuation = false
    private var traditional = false
    private var shiftState = ShiftState.OFF
    private var editorComposing = false
    private var clearedText: String? = null
    private var clearUsedClipboard = false
    private var lastShiftTap = 0L
    private var generation = 0
    private val appearancePreferences by lazy {
        getSharedPreferences(AppearanceSettings.PREFS_NAME, MODE_PRIVATE)
    }
    private val inputPreferences by lazy { getSharedPreferences("input_modes", MODE_PRIVATE) }
    private val clipboardManager by lazy { getSystemService(CLIPBOARD_SERVICE) as ClipboardManager }
    private var lastCapturedClipboardText: String? = null
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        Handler(Looper.getMainLooper()).post { captureClipboard(fromChange = true) }
    }
    private val appearanceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AppearanceSettings.DARK_MODE) refreshAppearance()
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
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        captureClipboard()
        RimeManager.observe(statusObserver)
        RimeManager.ensureReady(this)
    }

    override fun onDestroy() {
        dismissPreeditPreview()
        appearancePreferences.unregisterOnSharedPreferenceChangeListener(appearanceListener)
        clipboardManager.removePrimaryClipChangedListener(clipboardListener)
        mygoExecutor.shutdownNow()
        RimeManager.removeObserver(statusObserver)
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        dismissPreeditPreview()
        darkMode = AppearanceSettings.isDark(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(8), dp(5), dp(8))
            setBackgroundColor(keyboardSurface)
        }
        topBar = FrameLayout(this)
        emptyToolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), 0, dp(9), 0)
        }
        caption = TextView(this).apply {
            text = if (RimeManager.status.ready) modeCaption() else RimeManager.status.message
            setTextColor(ink)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        emptyToolbar?.addView(caption, LinearLayout.LayoutParams(0, dp(36), 1f))
        clipboardButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_clipboard_history)
            imageTintList = ColorStateList.valueOf(blue)
            contentDescription = "開啟剪貼簿"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = keyBackground(specialSurface)
            visibility = if (secure) View.GONE else View.VISIBLE
            onHapticClick { toggleClipboardPanel() }
        }
        emptyToolbar?.addView(clipboardButton, LinearLayout.LayoutParams(dp(44), dp(40)).apply {
            rightMargin = dp(5)
        })
        punctuationWidthButton = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(blue)
            background = keyBackground(specialSurface)
            onHapticClick { togglePunctuationWidth() }
        }
        updatePunctuationWidthButton()
        emptyToolbar?.addView(punctuationWidthButton, LinearLayout.LayoutParams(dp(44), dp(40)).apply {
            rightMargin = dp(5)
        })
        traditionalButton = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(blue)
            background = keyBackground(specialSurface)
            onHapticClick { toggleTraditional() }
        }
        updateTraditionalButton()
        emptyToolbar?.addView(traditionalButton, LinearLayout.LayoutParams(dp(44), dp(40)))
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
            setPadding(dp(6), 0, dp(6), 0)
        }
        candidateScroll?.addView(candidateRow)
        candidateControls.addView(candidateScroll, LinearLayout.LayoutParams(0, dp(44), 1f))
        moreButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_candidates_expand)
            imageTintList = ColorStateList.valueOf(blue)
            contentDescription = "展開所有候選詞"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(9), dp(9), dp(9), dp(9))
            background = keyBackground(specialSurface)
            onHapticClick { toggleExpandedCandidates() }
        }
        candidateControls.addView(moreButton, LinearLayout.LayoutParams(dp(44), dp(40)).apply {
            rightMargin = dp(9)
        })
        candidateBar?.addView(candidateControls, LinearLayout.LayoutParams(-1, dp(44)))
        topBar?.addView(candidateBar, FrameLayout.LayoutParams(-1, -1))
        emojiToolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(9), 0, dp(9), 0)
        }
        emojiCategoryScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
        }
        emojiToolbar?.addView(emojiCategoryScroll, LinearLayout.LayoutParams(0, dp(40), 1f))
        emojiBackButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_emoji_back)
            imageTintList = ColorStateList.valueOf(blue)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = keyBackground(specialSurface)
            contentDescription = "返回字母鍵盤"
            onHapticClick {
                if (emojiVariantPage != null) {
                    emojiVariantPage = null
                    renderEmojiPage()
                } else closeEmojiPanel()
            }
        }
        emojiTitle = TextView(this).apply {
            text = "表情符號"
            setTextColor(ink)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        emojiToolbar?.addView(emojiTitle, LinearLayout.LayoutParams(0, dp(40), 1f))
        mediaSearchButton = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(10), 0, dp(10), 0)
            background = keyBackground(keySurface)
            onHapticClick { enterMediaQueryEditing(emojiTab) }
        }
        mediaSearchButton?.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_search_outline)
            imageTintList = ColorStateList.valueOf(blue)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }, LinearLayout.LayoutParams(dp(22), dp(22)))
        mediaSearchText = TextView(this).apply {
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(9), 0, 0, 0)
        }
        mediaSearchButton?.addView(mediaSearchText, LinearLayout.LayoutParams(0, -2, 1f))
        emojiToolbar?.addView(mediaSearchButton, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            rightMargin = dp(5)
        })
        emojiToolbar?.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_delete_outline)
            imageTintList = ColorStateList.valueOf(blue)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(9), dp(9), dp(9), dp(9))
            background = keyBackground(specialSurface)
            contentDescription = "刪除前一個字元"
            onHapticClick { handleKey("DEL") }
        }, LinearLayout.LayoutParams(dp(44), dp(40)).apply { rightMargin = dp(5) })
        emojiToolbar?.addView(emojiBackButton, LinearLayout.LayoutParams(dp(44), dp(40)))
        topBar?.addView(emojiToolbar, FrameLayout.LayoutParams(-1, -1))
        root?.addView(topBar, LinearLayout.LayoutParams(-1, dp(50)))

        val keyboardArea = FrameLayout(this)
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        keyboardArea.addView(rows, FrameLayout.LayoutParams(-1, -1))
        expandedPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = rounded(keyboardSurface, 16)
        }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        expandedList = CandidateFlowLayout(this, dp(5), dp(5)).apply {
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        scroll.addView(expandedList, FrameLayout.LayoutParams(-1, -2))
        expandedPanel?.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        keyboardArea.addView(expandedPanel, FrameLayout.LayoutParams(-1, -1))
        clipboardPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = rounded(keyboardSurface, 16)
        }
        clipboardPanel?.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(2), dp(12), dp(4))
            addView(TextView(this@IceInputMethodService).apply {
                text = "剪貼簿"
                setTextColor(ink)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, dp(30), 1f))
            addView(TextView(this@IceInputMethodService).apply {
                text = "點選貼上 · 長按收藏"
                setTextColor(muted)
                textSize = 11f
                gravity = Gravity.CENTER_VERTICAL
            })
        }, LinearLayout.LayoutParams(-1, dp(36)))
        val clipboardScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        clipboardList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), 0, dp(6), dp(6))
        }
        clipboardScroll.addView(clipboardList)
        clipboardPanel?.addView(clipboardScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        keyboardArea.addView(clipboardPanel, FrameLayout.LayoutParams(-1, -1))
        emojiPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = rounded(keyboardSurface, 16)
        }
        emojiBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        emojiPanel?.addView(emojiBody, LinearLayout.LayoutParams(-1, 0, 1f))
        keyboardArea.addView(emojiPanel, FrameLayout.LayoutParams(-1, -1))
        root?.addView(keyboardArea, LinearLayout.LayoutParams(-1, dp(228)))
        inputFrame = FrameLayout(this).apply {
            addView(root, FrameLayout.LayoutParams(-1, -2))
        }
        holdPopup = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = rounded(keySurface, 14)
            elevation = dp(10).toFloat()
            visibility = View.GONE
        }
        inputFrame?.addView(holdPopup, FrameLayout.LayoutParams(dp(144), dp(52)))
        preeditPreview = TextView(this).apply {
            setTextColor(ink)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            isSingleLine = true
            setPadding(dp(9), 0, dp(9), 0)
        }
        preeditScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(preeditPreview, FrameLayout.LayoutParams(-2, -1))
        }
        val previewSurface = FrameLayout(this).apply {
            background = rounded(keySurface, 12)
            elevation = dp(8).toFloat()
            addView(preeditScroll, FrameLayout.LayoutParams(-1, -1))
        }
        preeditPopup = PopupWindow(previewSurface, dp(48), dp(42), false).apply {
            isTouchable = false
            isOutsideTouchable = false
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            isClippingEnabled = false
        }
        renderCandidates(null)
        renderKeys()
        return inputFrame!!
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        refreshAppearance()
        captureClipboard()
    }

    private fun refreshAppearance() {
        val selectedMode = AppearanceSettings.isDark(this)
        if (darkMode == selectedMode) return
        val state = latestState
        darkMode = selectedMode
        if (root != null) {
            closeExpandedCandidates()
            closeClipboardPanel()
            closeEmojiPanel()
            setInputView(onCreateInputView())
            state?.let(::renderCandidates)
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        generation++
        val variation = attribute?.inputType?.and(InputType.TYPE_MASK_VARIATION) ?: 0
        val inputClass = attribute?.inputType?.and(InputType.TYPE_MASK_CLASS) ?: 0
        secure = (inputClass == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        )) || (inputClass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        symbols = false
        ascii = false
        chineseFullPunctuation = inputPreferences.getBoolean("chinese_full_punctuation", true)
        englishFullPunctuation = inputPreferences.getBoolean("english_full_punctuation", false)
        shiftState = ShiftState.OFF
        editorComposing = false
        clearedText = null
        clearUsedClipboard = false
        closeExpandedCandidates()
        closeClipboardPanel()
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

    override fun onFinishInput() {
        generation++
        if (editorComposing) currentInputConnection?.finishComposingText()
        editorComposing = false
        clearedText = null
        clearUsedClipboard = false
        holdPopup?.visibility = View.GONE
        closeExpandedCandidates()
        closeClipboardPanel()
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
                shiftState = ShiftState.OFF
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
        val shiftedLetter = !symbols && key.length == 1 && key[0].isLetter() && shiftState != ShiftState.OFF
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
                    if (!result.processed && result.committedText.isEmpty()) KeyOutcome(state = result, direct = textKey)
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

    private fun captureClipboard(fromChange: Boolean = false) {
        if (secure) return
        val clip = runCatching { clipboardManager.primaryClip }.getOrNull()
        if (clip == null || clip.itemCount == 0) {
            lastCapturedClipboardText = null
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true) return
        val item = clip.getItemAt(0)
        val text = item.text?.toString()
        if (text != null) {
            if (!fromChange && text == lastCapturedClipboardText) return
            lastCapturedClipboardText = text
            if (ClipboardHistory.record(this, text) && clipboardOpen) renderClipboardHistory()
            return
        }
        val uri = item.uri ?: return
        val mime = runCatching { contentResolver.getType(uri) }.getOrNull()?.takeIf { it.startsWith("image/") }
            ?: (0 until clip.description.mimeTypeCount).map { clip.description.getMimeType(it) }
                .firstOrNull { it.startsWith("image/") }
        if (mime == null) return
        val label = clip.description.label?.toString().orEmpty().ifBlank { "圖片" }
        mygoExecutor.execute {
            val stored = runCatching { ClipboardHistory.recordImageUri(this, uri, mime, label) }.getOrNull()
            if (stored != null) mainHandler.post { if (clipboardOpen) renderClipboardHistory() }
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

    private fun modeCaption(): String = if (ascii) "霧凇拼音 · 英文" else "霧凇拼音 · 中文"

    private fun updateTraditionalButton() {
        traditionalButton?.apply {
            text = if (traditional) "繁" else "简"
            contentDescription = if (traditional) "目前繁體，切換簡體" else "目前簡體，切換繁體"
            visibility = if (secure) View.GONE else View.VISIBLE
        }
        clipboardButton?.visibility = if (secure) View.GONE else View.VISIBLE
        updatePunctuationWidthButton()
    }

    private val fullWidthPunctuation: Boolean
        get() = !secure && if (ascii) englishFullPunctuation else chineseFullPunctuation

    private fun updatePunctuationWidthButton() {
        punctuationWidthButton?.apply {
            text = if (fullWidthPunctuation) "全" else "半"
            contentDescription = if (fullWidthPunctuation) "目前全形標點，切換半形" else "目前半形標點，切換全形"
            visibility = if (secure) View.GONE else View.VISIBLE
        }
    }

    private fun togglePunctuationWidth() {
        if (secure) return
        if (ascii) {
            englishFullPunctuation = !englishFullPunctuation
            inputPreferences.edit().putBoolean("english_full_punctuation", englishFullPunctuation).apply()
        } else {
            chineseFullPunctuation = !chineseFullPunctuation
            inputPreferences.edit().putBoolean("chinese_full_punctuation", chineseFullPunctuation).apply()
        }
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
                    appendMediaQuery(previous + value)
                    mediaPreedit = ""
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
        currentInputConnection?.let { connection ->
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
            val bytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
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

    private fun updatePreeditPreview(state: RimeProcessResult?) {
        val popup = preeditPopup ?: return
        val frame = inputFrame ?: return
        val preview = preeditPreview ?: return
        val preedit = state?.let(::formattedPreedit).orEmpty()
        if (secure || mediaQueryEditing || emojiOpen || clipboardOpen || preedit.isEmpty()) {
            dismissPreeditPreview()
            return
        }
        val cursor = preeditCursorIndex(state!!, preedit).coerceIn(0, preedit.length)
        val display = SpannableStringBuilder(preedit).apply {
            insert(cursor, "\u200b")
            setSpan(PreeditCaretSpan(blue, dp(3), dp(2), dp(2)),
                cursor, cursor + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        preview.text = display
        preview.contentDescription = "目前輸入：$preedit"
        if (!frame.isAttachedToWindow || frame.width == 0) {
            frame.post { if (latestState === state) updatePreeditPreview(state) }
            return
        }
        val maxWidth = (frame.width - dp(24)).coerceAtLeast(dp(48))
        val width = (preview.paint.measureText(preedit).roundToInt() + dp(21))
            .coerceIn(dp(40), maxWidth)
        val location = IntArray(2)
        frame.getLocationInWindow(location)
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

    private fun inputPunctuation(period: Boolean) {
        commitLiteral(punctuationText(if (period) "." else ","))
    }

    private fun renderCandidates(state: RimeProcessResult?) {
        latestState = state
        if (state != null) ascii = state.isAsciiMode
        updatePreeditPreview(state)
        updatePunctuationWidthButton()
        val showCandidates = state != null && state.candidates.isNotEmpty() && (!secure || mediaQueryEditing)
        emojiToolbar?.visibility = if (emojiOpen && !(mediaQueryEditing && showCandidates)) View.VISIBLE else View.GONE
        val strip = candidateRow ?: return
        strip.removeAllViews()
        if (!showCandidates) {
            closeExpandedCandidates()
            emptyToolbar?.visibility = if (emojiOpen) View.GONE else View.VISIBLE
            candidateBar?.visibility = View.GONE
            caption?.text = when {
                !RimeManager.status.ready -> RimeManager.status.message
                else -> modeCaption()
            }
            return
        }
        closeClipboardPanel()
        emptyToolbar?.visibility = View.GONE
        candidateBar?.visibility = if (!emojiOpen || mediaQueryEditing) View.VISIBLE else View.GONE
        state.candidates.take(9).forEachIndexed { index, candidate ->
            val text = TextView(this).apply {
                this.text = candidate.text
                contentDescription = "候選詞 ${index + 1}：${candidate.text}"
                setTextColor(candidateBlue)
                textSize = 19f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = keyBackground(Color.TRANSPARENT)
                setPadding(dp(14), 0, dp(14), 0)
                onHapticClick { selectCandidate(index) }
            }
            val params = LinearLayout.LayoutParams(-2, dp(40)).apply {
                rightMargin = dp(7)
            }
            strip.addView(text, params)
        }
        moreButton?.setImageResource(if (expanded) R.drawable.ic_candidates_collapse else R.drawable.ic_candidates_expand)
        moreButton?.contentDescription = if (expanded) "收起候選詞清單" else "展開所有候選詞"
        candidateScroll?.smoothScrollTo(0, 0)
    }

    private fun toggleExpandedCandidates() {
        if (expanded) {
            closeExpandedCandidates()
            return
        }
        if (latestState?.candidates.isNullOrEmpty()) return
        closeClipboardPanel()
        expanded = true
        rows?.visibility = View.GONE
        expandedPanel?.visibility = View.VISIBLE
        moreButton?.setImageResource(R.drawable.ic_candidates_collapse)
        moreButton?.contentDescription = "收起候選詞清單"
        expandedList?.removeAllViews()
        expandedList?.addView(TextView(this).apply {
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
        rows?.visibility = if (clipboardOpen || (emojiOpen && !mediaQueryEditing)) View.GONE else View.VISIBLE
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
        captureClipboard()
        clipboardOpen = true
        dismissPreeditPreview()
        rows?.visibility = View.GONE
        clipboardPanel?.visibility = View.VISIBLE
        clipboardButton?.apply {
            background = keyBackground(actionBlue)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            contentDescription = "收起剪貼簿"
        }
        renderClipboardHistory()
    }

    private fun closeClipboardPanel() {
        clipboardOpen = false
        clipboardPanel?.visibility = View.GONE
        updatePreeditPreview(latestState)
        rows?.visibility = if (expanded || (emojiOpen && !mediaQueryEditing)) View.GONE else View.VISIBLE
        clipboardButton?.apply {
            background = keyBackground(specialSurface)
            imageTintList = ColorStateList.valueOf(blue)
            contentDescription = "開啟剪貼簿"
        }
    }

    private fun openEmojiPanel() {
        if (emojiOpen) return
        closeExpandedCandidates()
        closeClipboardPanel()
        emojiVariantPage = null
        emojiReturnPosition = 0
        emojiOpen = true
        dismissPreeditPreview()
        emojiTab = EmojiTab.EMOJI
        rows?.visibility = View.GONE
        emojiPanel?.visibility = View.VISIBLE
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
        if (tab == EmojiTab.MYGO) {
            mygoRequest++
            mygoQuery = ""
            mygoResults.clear()
            mygoPage = 0
            mygoHasNext = false
            mygoLoading = false
            mygoError = null
            mygoScrollY = 0
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
        emojiPanel?.visibility = View.GONE
        rows?.visibility = if (expanded || clipboardOpen) View.GONE else View.VISIBLE
        emojiToolbar?.visibility = View.GONE
        val candidatesVisible = !secure && !latestState?.candidates.isNullOrEmpty()
        emptyToolbar?.visibility = if (candidatesVisible) View.GONE else View.VISIBLE
        candidateBar?.visibility = if (candidatesVisible) View.VISIBLE else View.GONE
        if (wasMediaQueryEditing) renderCandidates(null) else updatePreeditPreview(latestState)
    }

    private fun renderEmojiPage() {
        val body = emojiBody ?: return
        if (emojiTab == EmojiTab.MYGO) mygoResultsScroll?.let { mygoScrollY = it.scrollY }
        mygoResultsScroll = null
        body.removeAllViews()
        emojiBackButton?.apply {
            contentDescription = if (emojiVariantPage != null) "返回表情列表" else "返回字母鍵盤"
        }
        val categoriesVisible = emojiTab == EmojiTab.EMOJI && emojiVariantPage == null
        emojiCategoryScroll?.visibility = if (categoriesVisible) View.VISIBLE else View.GONE
        emojiTitle?.visibility = if (categoriesVisible) View.GONE else View.VISIBLE
        mediaSearchButton?.visibility = View.GONE
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
        fun emojiAt(index: Int): List<String> = if (index == -1) {
            EmojiCatalog.recent(this).ifEmpty { categories.first().emoji.take(32) }
        } else categories[index].emoji
        val previous = if (emojiCategory > -1) createEmojiGrid(emojiAt(emojiCategory - 1)) else null
        val current = createEmojiGrid(emojiAt(emojiCategory))
        val following = if (emojiCategory < categories.lastIndex) {
            createEmojiGrid(emojiAt(emojiCategory + 1))
        } else null
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
            setPadding(dp(3), dp(2), dp(3), dp(4))
            adapter = object : BaseAdapter() {
                override fun getCount() = emoji.size
                override fun getItem(position: Int) = emoji[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                    val symbol = emoji[position]
                    val variants = EmojiCatalog.variants(this@IceInputMethodService, symbol)
                    val cell = (convertView as? FrameLayout) ?: FrameLayout(this@IceInputMethodService).apply {
                        background = keyBackground(Color.TRANSPARENT)
                        layoutParams = AbsListView.LayoutParams(-1, dp(43))
                        addView(TextView(this@IceInputMethodService).apply {
                            textSize = 26f
                            gravity = Gravity.CENTER
                        }, FrameLayout.LayoutParams(-1, -1))
                        addView(TextView(this@IceInputMethodService).apply {
                            text = "•"
                            textSize = 11f
                            setTextColor(blue)
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

    private fun showMediaToolbarTitle(title: String) {
        emojiCategoryScroll?.visibility = View.GONE
        val showMediaSearch = emojiTab == EmojiTab.GIF || emojiTab == EmojiTab.MYGO
        mediaSearchButton?.visibility = if (showMediaSearch) View.VISIBLE else View.GONE
        if (showMediaSearch) {
            val name = if (emojiTab == EmojiTab.GIF) "GIF" else "MyGO 梗圖"
            val query = (if (emojiTab == EmojiTab.GIF) gifQuery else mygoQuery) +
                if (mediaQueryEditing) mediaPreedit else ""
            mediaSearchButton?.contentDescription = "搜尋 $name"
            mediaSearchText?.apply {
                text = query.ifBlank { "搜尋 $name" }
                setTextColor(if (query.isBlank()) muted else ink)
                ellipsize = if (mediaQueryEditing) TextUtils.TruncateAt.START else TextUtils.TruncateAt.END
            }
        }
        emojiTitle?.apply {
            visibility = if (showMediaSearch) View.GONE else View.VISIBLE
            text = title
        }
    }

    private fun renderEmojiCategories() {
        val categoryScroll = emojiCategoryScroll ?: return
        categoryScroll.removeAllViews()
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
        val categoryRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        categories.forEachIndexed { index, (name, icon) ->
                val selected = emojiCategory == index - 1
                categoryRow.addView(ImageView(this).apply {
                    setImageResource(if (selected) filledIcons[name] ?: icon else icon)
                    imageTintList = ColorStateList.valueOf(if (selected) candidateBlue else muted)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    background = keyBackground(Color.TRANSPARENT)
                    contentDescription = "$name 表情"
                    onHapticClick {
                        selectEmojiCategory(index - 1)
                    }
                }, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                    leftMargin = dp(2)
                    rightMargin = dp(2)
                })
            }
        categoryScroll.addView(categoryRow)
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
            setPadding(dp(3), dp(2), dp(3), dp(4))
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
                        layoutParams = AbsListView.LayoutParams(-1, dp(42))
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
        } else if (emojiCategory == -1) renderEmojiPage()
    }

    private fun renderGifPage(body: LinearLayout) {
        if (BuildConfig.GIPHY_SDK_KEY.isBlank() || !ensureGiphyConfigured()) {
            body.addView(mygoStatus("GIF 服務無法啟動，請檢查 Android SDK 金鑰"),
                LinearLayout.LayoutParams(-1, 0, 1f))
            return
        }
        val container = FrameLayout(this)
        val status = mygoStatus("正在載入 GIF…").apply { visibility = View.VISIBLE }
        var hasResults = false
        val grid = GiphyGridView(this).apply {
            direction = GiphyGridView.VERTICAL
            spanCount = 2
            cellPadding = dp(4)
            theme = if (darkMode) GPHTheme.Dark else GPHTheme.Light
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
        body.addView(TextView(this).apply {
            text = "Powered by GIPHY"
            setTextColor(muted)
            textSize = 10f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, dp(24)))
        grid.content = gifQuery.trim().takeIf { it.isNotEmpty() }
            ?.let(GPHContent::searchQuery) ?: GPHContent.trendingGifs
    }

    private fun ensureGiphyConfigured(): Boolean {
        if (giphyConfigured) return true
        giphyConfigured = runCatching {
            Giphy.configure(applicationContext, BuildConfig.GIPHY_SDK_KEY)
            true
        }.getOrDefault(false)
        return giphyConfigured
    }

    private fun insertGiphyGif(media: Media) {
        val token = generation
        Toast.makeText(this, "正在準備 GIF…", Toast.LENGTH_SHORT).show()
        mygoExecutor.execute {
            val prepared = runCatching { GiphyGif.prepareForInsertion(this, media) }
            mainHandler.post {
                if (token != generation) return@post
                prepared.onSuccess { file -> insertImageFile(file, "image/gif", media.title?.ifBlank { "GIF" } ?: "GIF") }
                    .onFailure { Toast.makeText(this, "GIF 下載失敗，請重試", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun renderMygoPage(body: LinearLayout) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), 0, dp(5), dp(4))
        }
        if (mygoResults.isEmpty() && !mygoLoading && mygoError == null && mygoPage > 0) {
            content.addView(mygoStatus("找不到相關梗圖"), LinearLayout.LayoutParams(-1, dp(86)))
        }
        mygoResults.chunked(2).forEach { pair ->
            val row = LinearLayout(this)
            pair.forEach { image ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(4), dp(4), dp(4), dp(3))
                    background = candidateBackground(12)
                    contentDescription = "插入梗圖：${image.alt}"
                    onHapticClick { insertMygoImage(image) }
                }
                val preview = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = rounded(specialSurface, 8)
                    tag = image.id
                }
                card.addView(preview, LinearLayout.LayoutParams(-1, dp(72)))
                card.addView(TextView(this).apply {
                    text = image.alt.ifBlank { "MyGO 梗圖" }
                    setTextColor(ink)
                    textSize = 11f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(3), 0, dp(3), 0)
                }, LinearLayout.LayoutParams(-1, dp(22)))
                row.addView(card, LinearLayout.LayoutParams(0, dp(101), 1f).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                })
                loadMygoThumbnail(image, preview)
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, dp(101), 1f))
            content.addView(row)
        }
        if (mygoLoading) content.addView(mygoStatus("正在載入梗圖…"), LinearLayout.LayoutParams(-1, dp(58)))
        mygoError?.let { error ->
            content.addView(TextView(this).apply {
                text = "$error · 點此重試"
                setTextColor(blue)
                textSize = 13f
                gravity = Gravity.CENTER
                background = keyBackground(specialSurface)
                onHapticClick { fetchMygoPage(reset = mygoPage == 0) }
            }, LinearLayout.LayoutParams(-1, dp(50)))
        }
        if (mygoHasNext && !mygoLoading && mygoError == null) {
            content.addView(TextView(this).apply {
                text = "載入更多"
                setTextColor(blue)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = keyBackground(specialSurface)
                onHapticClick { fetchMygoPage(reset = false) }
            }, LinearLayout.LayoutParams(-1, dp(43)).apply {
                setMargins(dp(3), dp(4), dp(3), dp(3))
            })
        }
        content.addView(TextView(this).apply {
            text = "圖片來源：MyGO-Searcher · miyago9267"
            setTextColor(muted)
            textSize = 10f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, dp(30)))
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
        }
        mygoResultsScroll = scroll
        body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        scroll.post { scroll.scrollTo(0, mygoScrollY) }
    }

    private fun mygoStatus(message: String) = TextView(this).apply {
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
            mygoScrollY = 0
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
        val request = mygoRequest
        mygoExecutor.execute {
            if (request != mygoRequest) return@execute
            val bitmap = runCatching { MyGoApi.thumbnail(MyGoApi.cachedImage(this, image)) }.getOrNull()
            mainHandler.post {
                if (bitmap != null && request == mygoRequest && emojiOpen && emojiTab == EmojiTab.MYGO &&
                    preview.isAttachedToWindow && preview.tag == image.id) preview.setImageBitmap(bitmap)
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
                }.onFailure {
                    Toast.makeText(this, "梗圖下載失敗，請重試", Toast.LENGTH_SHORT).show()
                }
            }
        }
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
        list.removeAllViews()
        val entries = ClipboardHistory.entries(this)
        if (entries.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "尚無複製記錄\n複製文字或圖片後會顯示在這裡"
                setTextColor(muted)
                textSize = 14f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, dp(150)))
            return
        }
        entries.forEach { entry ->
            val cell = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(13), dp(7), dp(12), dp(7))
                background = candidateBackground(13)
                contentDescription = if (entry.pinned) "已收藏，點選貼上，長按取消收藏" else "點選貼上，長按收藏"
                onHapticClick {
                    closeClipboardPanel()
                    val imageFile = ClipboardHistory.imageFile(this@IceInputMethodService, entry)
                    if (imageFile != null) insertImageFile(imageFile, entry.mimeType ?: "image/jpeg", entry.label ?: "圖片")
                    else entry.text?.let(::commitLiteral)
                }
                setOnLongClickListener {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    ClipboardHistory.togglePinned(this@IceInputMethodService, entry.id)
                    renderClipboardHistory()
                    true
                }
            }
            if (entry.imageFileName != null) {
                val preview = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = rounded(specialSurface, 7)
                    contentDescription = entry.label ?: "圖片"
                }
                cell.addView(preview, LinearLayout.LayoutParams(dp(75), dp(55)).apply { rightMargin = dp(10) })
                val file = ClipboardHistory.imageFile(this, entry)
                if (file != null) mygoExecutor.execute {
                    val bitmap = runCatching { MyGoApi.thumbnail(file) }.getOrNull()
                    if (bitmap != null) mainHandler.post { preview.setImageBitmap(bitmap) }
                }
            }
            cell.addView(TextView(this).apply {
                text = entry.text?.replace("\n", " ↵ ") ?: entry.label ?: "圖片"
                setTextColor(ink)
                textSize = 15f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            cell.addView(TextView(this).apply {
                text = if (entry.pinned) "★" else "☆"
                setTextColor(if (entry.pinned) blue else muted)
                textSize = 21f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(36), dp(40)))
            list.addView(cell, LinearLayout.LayoutParams(-1, dp(if (entry.imageFileName != null) 69 else 57)).apply {
                bottomMargin = dp(5)
            })
        }
    }

    private fun renderExpandedCandidates(candidates: List<RimeCandidate>) {
        val list = expandedList ?: return
        list.removeAllViews()
        if (candidates.isEmpty()) {
            list.addView(TextView(this).apply {
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
                setPadding(dp(10), dp(5), dp(10), dp(5))
                background = keyBackground(Color.TRANSPARENT)
                contentDescription = "候選詞 ${index + 1}：${candidate.text}"
                onHapticClick { selectCandidate(index, global = true) }
            }
            cell.addView(TextView(this).apply {
                text = candidate.text
                setTextColor(candidateBlue)
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
            })
            if (candidate.comment.isNotBlank()) {
                cell.addView(TextView(this).apply {
                    text = candidate.comment
                    setTextColor(muted)
                    textSize = 11f
                    maxLines = 1
                })
            }
            list.addView(cell, android.view.ViewGroup.LayoutParams(-2, -2))
        }
    }

    private fun renderKeys() {
        val container = rows ?: return
        container.removeAllViews()
        updatePunctuationWidthButton()
        if (symbols) {
            addRow(listOf("1","2","3","4","5","6","7","8","9","0"))
            addRow(listOf("@","#","$","%","&","-","+","(",")"))
            addRow(listOf("*","\"",":",";","!","?","/","DEL"))
        } else {
            addRow("qwertyuiop".map(::letterLabel), numberHints = true)
            addRow("asdfghjkl".map(::letterLabel), inset = 15)
            addRow(listOf("SHIFT") + "zxcvbnm".map(::letterLabel) + "DEL")
        }
        addRow(listOf("MODE", "EMOJI", "SPACE", "PUNCT", "ENTER"), bottom = true)
    }

    private fun letterLabel(letter: Char): String =
        if (shiftState == ShiftState.OFF) letter.toString() else letter.uppercase()

    private fun addRow(keys: List<String>, inset: Int = 0, bottom: Boolean = false, numberHints: Boolean = false) {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(inset), 0, dp(inset), 0)
        }
        keys.forEachIndexed { index, key ->
            val label = when (key) {
                "DEL" -> "⌫"
                "ENTER" -> "↵"
                "SPACE" -> if (ascii) "En" else "中"
                "MODE" -> if (symbols) "ABC" else "?123"
                "PUNCT" -> punctuationText(",")
                "EMOJI" -> ""
                "SHIFT" -> if (shiftState == ShiftState.LOCKED) "⇪" else "⇧"
                else -> if (symbols && key.length == 1 && !key[0].isLetterOrDigit()) punctuationText(key) else key
            }
            val special = key in setOf("DEL", "MODE", "EMOJI", "SHIFT")
            val shifted = key == "SHIFT" && shiftState != ShiftState.OFF
            val action = key == "ENTER"
            val letter = key.singleOrNull()?.takeIf(Char::isLetter)?.lowercaseChar()
            val alternate = when {
                letter == null -> null
                numberHints -> if (index == 9) "0" else (index + 1).toString()
                else -> LETTER_SYMBOLS[letter]?.let(::punctuationText)
            }
            val punctuationKey = key == "PUNCT"
            val view = TextView(this).apply {
                text = label
                if (key == "SHIFT") contentDescription = when (shiftState) {
                    ShiftState.OFF -> "Shift，切換下一個字母大寫"
                    ShiftState.ONCE -> "Shift，下一個字母大寫"
                    ShiftState.LOCKED -> "大寫鎖定"
                }
                if (key == "EMOJI") contentDescription = "開啟表情符號"
                gravity = Gravity.CENTER
                setTextColor(if (action || shifted) Color.WHITE else if (special) blue else ink)
                textSize = if (punctuationKey || key == "EMOJI") 21f else if (bottom || special) 14f else 21f
                typeface = if (special || action) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                if (alternate == null && !punctuationKey) {
                    background = keyBackground(if (action || shifted) actionBlue else if (special) specialSurface else keySurface)
                    elevation = dp(1).toFloat()
                    if (key == "SPACE") {
                        contentDescription = "空白，按住左右滑動移動游標"
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
                    imageTintList = ColorStateList.valueOf(if (action || shifted) Color.WHITE else blue)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    background = keyBackground(if (action || shifted) actionBlue else specialSurface)
                    elevation = dp(1).toFloat()
                    contentDescription = when (key) {
                        "SHIFT" -> when (shiftState) {
                            ShiftState.OFF -> "Shift，切換下一個字母大寫"
                            ShiftState.ONCE -> "Shift，下一個字母大寫"
                            ShiftState.LOCKED -> "大寫鎖定"
                        }
                        "DEL" -> "刪除，長按連續刪除，上滑清空，下滑復原"
                        else -> if (mediaQueryEditing) "搜尋 ${if (mediaQueryTab == EmojiTab.GIF) "GIF" else "MyGO 梗圖"}" else "換行"
                    }
                    if (key == "DEL") {
                        setOnTouchListener { touched, event -> handleDeleteTouch(touched, event) }
                    } else onHapticClick { handleKey(key) }
                }
            } else if (key == "EMOJI") {
                ImageView(this).apply {
                    setImageResource(R.drawable.ic_emoji_outline)
                    imageTintList = ColorStateList.valueOf(blue)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(5), dp(5), dp(5), dp(5))
                    background = keyBackground(specialSurface)
                    elevation = dp(1).toFloat()
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
                    elevation = dp(1).toFloat()
                    contentDescription = "$label，長按選擇或上滑輸入 $alternate"
                    addView(view, FrameLayout.LayoutParams(-1, -1))
                    addView(TextView(this@IceInputMethodService).apply {
                        text = alternate
                        textSize = 10f
                        setTextColor(muted)
                        gravity = Gravity.TOP or Gravity.RIGHT
                    }, FrameLayout.LayoutParams(dp(18), dp(17), Gravity.TOP or Gravity.RIGHT).apply {
                        topMargin = dp(2)
                        rightMargin = dp(4)
                    })
                    setOnTouchListener { touched, event -> handleLetterKeyTouch(touched, event, key, alternate) }
                }
            } else if (punctuationKey) {
                FrameLayout(this).apply {
                    background = keyBackground(keySurface)
                    elevation = dp(1).toFloat()
                    contentDescription = "${punctuationText(",")}，長按選擇或上滑輸入 ${punctuationText(".")}"
                    addView(view, FrameLayout.LayoutParams(-1, -1))
                    addView(TextView(this@IceInputMethodService).apply {
                        text = punctuationText(".")
                        textSize = 10f
                        setTextColor(muted)
                        gravity = Gravity.TOP or Gravity.RIGHT
                    }, FrameLayout.LayoutParams(dp(20), dp(17), Gravity.TOP or Gravity.RIGHT).apply {
                        topMargin = dp(2)
                        rightMargin = dp(4)
                    })
                    setOnTouchListener { touched, event -> handlePunctuationTouch(touched, event) }
                }
            } else view
            val weight = when (key) {
                "SPACE" -> 4f
                "SHIFT", "DEL" -> 1.55f
                "MODE", "ENTER" -> if (bottom) 1.55f else 1f
                "EMOJI", "PUNCT" -> 1f
                else -> if (bottom) 1.15f else 1f
            }
            val params = LinearLayout.LayoutParams(0, dp(47), weight).apply {
                setMargins(dp(2), dp(4), dp(2), dp(4))
            }
            row.addView(keyView, params)
        }
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

    private fun handleModeTouch(view: View, event: MotionEvent): Boolean {
        updateKeyPressed(view, event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> view.tag = SwipeKeyGesture(event.rawX, event.rawY)
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val gesture = view.tag as? SwipeKeyGesture ?: return true
                val dx = event.rawX - gesture.startX
                val dy = event.rawY - gesture.startY
                if (!gesture.active && dy < -dp(24) && abs(dy) > abs(dx)) {
                    gesture.active = true
                    gesture.choice = if (ascii) 0 else 1
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    showChoicePopup(view, listOf("中", "En"), gesture.choice)
                }
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

    private fun showChoicePopup(keyView: View, values: List<String>, selected: Int, emojiChoices: Boolean = false) {
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
                val option: View = if (value == "EMOJI") ImageView(this).apply {
                    setImageResource(R.drawable.ic_emoji_outline)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(dp(7), dp(7), dp(7), dp(7))
                    contentDescription = "表情符號"
                } else TextView(this).apply {
                    text = value
                    gravity = Gravity.CENTER
                    textSize = if (emojiChoices) 25f else when (value) {
                        "，", "。" -> 27f
                        "MyGO" -> 15f
                        "GIF" -> 17f
                        else -> 19f
                    }
                    typeface = Typeface.DEFAULT_BOLD
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
            leftMargin = (keyPosition[0] - framePosition[0] + keyView.width / 2 -
                if (emojiChoices) dp(4 + 48 * (selected % columns) + 24) else width / 2)
                .coerceIn(0, (frame.width - width).coerceAtLeast(0))
            topMargin = (keyPosition[1] - framePosition[1] - height - dp(5)).coerceAtLeast(0)
        }
        popup.visibility = View.VISIBLE
    }

    private fun showDeleteActionPopup(keyView: View, choice: Int) {
        val popup = holdPopup ?: return
        val frame = inputFrame ?: return
        popup.orientation = LinearLayout.HORIZONTAL
        popup.removeAllViews()
        holdOptions = listOf(TextView(this).apply {
            text = if (choice == 0) "清空" else "復原"
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = rounded(actionBlue, 10)
            popup.addView(this, LinearLayout.LayoutParams(-1, -1))
        })
        val keyPosition = IntArray(2)
        val framePosition = IntArray(2)
        keyView.getLocationOnScreen(keyPosition)
        frame.getLocationOnScreen(framePosition)
        val width = dp(80)
        val height = dp(44)
        val keyTop = keyPosition[1] - framePosition[1]
        popup.layoutParams = (popup.layoutParams as FrameLayout.LayoutParams).apply {
            this.width = width
            this.height = height
            leftMargin = (keyPosition[0] - framePosition[0] + keyView.width / 2 - width / 2)
                .coerceIn(0, (frame.width - width).coerceAtLeast(0))
            topMargin = if (choice == 0) {
                (keyTop - height - dp(5)).coerceAtLeast(0)
            } else {
                (keyTop + keyView.height + dp(5)).coerceAtMost((frame.height - height).coerceAtLeast(0))
            }
        }
        popup.visibility = View.VISIBLE
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
                        gesture.choice = 1
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        val choices = listOf(punctuationText(","), punctuationText("."))
                        showChoicePopup(view, choices, gesture.choice)
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
                        showChoicePopup(view, listOf(punctuationText(".")), 0)
                    } else if ((abs(dx) > slop && abs(dx) > abs(dy)) || dy > slop) {
                        view.removeCallbacks(gesture.longPress)
                        gesture.cancelled = true
                    }
                }
                if (gesture.held && event.actionMasked != MotionEvent.ACTION_CANCEL) {
                    gesture.choice = (1 + (dx / dp(48)).roundToInt()).coerceIn(0, holdValues.lastIndex)
                    updateHoldChoice(gesture.choice)
                }
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    view.removeCallbacks(gesture.longPress)
                    view.tag = null
                    holdPopup?.visibility = View.GONE
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        if (gesture.swipeAlternate) inputPunctuation(period = true)
                        else if (gesture.held) holdValues.getOrNull(gesture.choice)?.let(::commitLiteral)
                        else if (!gesture.cancelled) inputPunctuation(period = false)
                    }
                }
            }
        }
        return true
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
            val color = if (index == selected) Color.WHITE else ink
            when (option) {
                is TextView -> option.setTextColor(color)
                is ImageView -> option.imageTintList = ColorStateList.valueOf(color)
            }
            option.background = rounded(if (index == selected) actionBlue else Color.TRANSPARENT, 10)
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
        var choice: Int = 0
    )

    private class SpaceCursorGesture(
        val startX: Float,
        val startY: Float,
        val longPress: Runnable,
        val stepWidthPx: Int,
        var held: Boolean = false,
        var cancelled: Boolean = false,
        var lastStep: Int = 0
    )

    private class PreeditCaretSpan(
        private val color: Int,
        private val widthPx: Int,
        private val linePx: Int,
        private val insetPx: Int
    ) : ReplacementSpan() {
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

    private fun keyBackground(color: Int): StateListDrawable = StateListDrawable().apply {
        val pressedColor = when (color) {
            keySurface -> if (darkMode) Color.rgb(58, 82, 113) else Color.rgb(213, 225, 242)
            actionBlue -> if (darkMode) Color.rgb(41, 84, 148) else Color.rgb(34, 73, 135)
            else -> if (darkMode) Color.rgb(67, 98, 139) else Color.rgb(185, 205, 234)
        }
        addState(intArrayOf(android.R.attr.state_pressed), rounded(pressedColor, 11))
        addState(intArrayOf(), rounded(color, 11))
    }

    private fun candidateBackground(radius: Int): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), rounded(
            if (darkMode) Color.rgb(59, 92, 134) else Color.rgb(192, 214, 241), radius))
        addState(intArrayOf(), rounded(
            if (darkMode) Color.rgb(42, 68, 103) else Color.rgb(224, 234, 249), radius))
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
