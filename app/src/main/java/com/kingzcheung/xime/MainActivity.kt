package com.kingzcheung.xime

import android.content.ComponentName
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlin.math.roundToInt

private data class AppPalette(
    val page: Color,
    val card: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val button: Color,
    val hero: Color,
    val heroDetail: Color,
    val soft: Color,
    val outline: Color,
    val success: Color
)

private fun appPalette(darkMode: Boolean): AppPalette {
    val ui = UiTheme.palette(darkMode)
    return AppPalette(
        page = Color(ui.canvas), card = Color(if (darkMode) ui.key else ui.inset), text = Color(ui.ink),
        muted = Color(ui.muted), accent = Color(if (darkMode) ui.accent else ui.action),
        button = Color(ui.action), hero = Color(UiTheme.dark.key),
        heroDetail = Color(UiTheme.dark.muted),
        soft = Color(if (darkMode) ui.inset else ui.surface), outline = Color(ui.outline), success = Color(ui.success)
    )
}

private data class SetupState(val enabled: Boolean, val selected: Boolean)

class MainActivity : ComponentActivity() {
    private var setup by mutableStateOf(SetupState(false, false))
    private var darkMode by mutableStateOf(false)
    private var spaceCursorSensitivity by mutableStateOf(SpaceCursorSettings.DEFAULT)
    private var quickPhrases by mutableStateOf(emptyList<QuickPhrase>())
    private var giphyKey by mutableStateOf("")
    private var symbolKey by mutableStateOf(SymbolKeySettings.DEFAULT)
    private var inputScheme by mutableStateOf(InputScheme.PINYIN)
    private var voiceModel by mutableStateOf<VoiceModel.State>(VoiceModel.State.Missing)
    private var micGranted by mutableStateOf(false)
    private var micAsked = false
    private val voiceModelObserver: (VoiceModel.State) -> Unit = { voiceModel = it }
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        micAsked = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RimeManager.ensureReady(this)
        ClipboardHistory.startCleanup(this)
        setup = readSetup()
        darkMode = AppearanceSettings.isDark(this)
        spaceCursorSensitivity = SpaceCursorSettings.read(this)
        quickPhrases = QuickPhrases.entries(this)
        giphyKey = GiphySettings.userKey(this)
        symbolKey = SymbolKeySettings.read(this)
        inputScheme = InputSchemeSettings.read(this)
        VoiceModel.observe(voiceModelObserver)
        applySystemBars()
        setContent {
            val palette = appPalette(darkMode)
            var engineStatus by remember { mutableStateOf(RimeManager.status) }
            DisposableEffect(Unit) {
                val observer: (EngineStatus) -> Unit = { engineStatus = it }
                RimeManager.observe(observer)
                onDispose { RimeManager.removeObserver(observer) }
            }
            MaterialTheme(
                typography = UiFonts.typography,
                colorScheme = if (darkMode) darkColorScheme(
                    primary = palette.accent, onPrimary = Color.White,
                    background = palette.page, surface = palette.card,
                    onSurface = palette.text, onBackground = palette.text,
                    surfaceVariant = palette.soft, onSurfaceVariant = palette.muted,
                    outline = palette.outline, error = Color(UiTheme.dark.error)
                ) else lightColorScheme(
                    primary = palette.accent, onPrimary = Color.White,
                    background = palette.page, surface = palette.card,
                    onSurface = palette.text, onBackground = palette.text,
                    surfaceVariant = palette.soft, onSurfaceVariant = palette.muted,
                    outline = palette.outline, error = Color(UiTheme.light.error)
                )
            ) {
                SettingsScreen(
                    setup = setup,
                    engine = engineStatus,
                    darkMode = darkMode,
                    spaceCursorSensitivity = spaceCursorSensitivity,
                    quickPhrases = quickPhrases,
                    giphyKey = giphyKey,
                    symbolKey = symbolKey,
                    inputScheme = inputScheme,
                    onInputSchemeChange = { scheme ->
                        inputScheme = scheme
                        InputSchemeSettings.write(this, scheme)
                        RimeManager.switchScheme(this, scheme)
                    },
                    voiceModel = voiceModel,
                    micGranted = micGranted,
                    palette = palette,
                    onEnable = { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                    onSelect = { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() },
                    onGiphyKeySave = { key ->
                        val trimmed = key.trim()
                        if (trimmed.isEmpty()) GiphySettings.clear(this) else GiphySettings.write(this, trimmed)
                        giphyKey = GiphySettings.userKey(this)
                    },
                    onRedeploy = { RimeManager.redeploy(this) },
                    onDarkModeChange = { enabled ->
                        AppearanceSettings.setDark(this, enabled)
                        darkMode = enabled
                        applySystemBars()
                    },
                    onSymbolKeySave = { config ->
                        SymbolKeySettings.write(this, config)
                        symbolKey = SymbolKeySettings.read(this)
                    },
                    onVoiceDownload = { VoiceModel.download(this) },
                    onVoiceCancel = { VoiceModel.cancel() },
                    onVoiceDelete = { VoiceModel.delete(this) },
                    onMicPermission = { requestMicPermission() },
                    onSymbolKeyReset = {
                        SymbolKeySettings.reset(this)
                        symbolKey = SymbolKeySettings.read(this)
                    },
                    onSpaceCursorSensitivityChange = { level ->
                        spaceCursorSensitivity = level
                        SpaceCursorSettings.write(this, level)
                    },
                    onQuickPhraseSave = { oldCode, code, text ->
                        val normalizedCode = QuickPhrases.normalizeCode(code)
                        val normalizedText = text.trim()
                        val error = QuickPhrases.validationError(quickPhrases, oldCode,
                            normalizedCode, normalizedText)
                        if (error == null) {
                            val updated = quickPhrases.filterNot { it.code == oldCode } +
                                QuickPhrase(normalizedCode, normalizedText)
                            QuickPhrases.save(this, updated)
                            quickPhrases = updated
                            RimeManager.redeploy(this, incremental = true)
                        }
                        error
                    },
                    onQuickPhraseDelete = { code ->
                        val updated = quickPhrases.filterNot { it.code == code }
                        QuickPhrases.save(this, updated)
                        quickPhrases = updated
                        RimeManager.redeploy(this, incremental = true)
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setup = readSetup()
        darkMode = AppearanceSettings.isDark(this)
        spaceCursorSensitivity = SpaceCursorSettings.read(this)
        quickPhrases = QuickPhrases.entries(this)
        giphyKey = GiphySettings.userKey(this)
        symbolKey = SymbolKeySettings.read(this)
        inputScheme = InputSchemeSettings.read(this)
        VoiceModel.refresh(this)
        micGranted = VoiceEngine.hasMicPermission(this)
        applySystemBars()
    }

    override fun onDestroy() {
        VoiceModel.removeObserver(voiceModelObserver)
        super.onDestroy()
    }

    // After a denial with "don't ask again" the system dialog no longer appears, so open app settings instead.
    private fun requestMicPermission() {
        if (micAsked && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun applySystemBars() {
        window.statusBarColor = UiTheme.palette(darkMode).canvas
        window.navigationBarColor = window.statusBarColor
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkMode
            isAppearanceLightNavigationBars = !darkMode
        }
    }

    private fun readSetup(): SetupState {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val component = ComponentName(this, IceInputMethodService::class.java)
        return SetupState(
            enabled = imm.enabledInputMethodList.any { it.packageName == packageName && it.serviceName == component.className },
            selected = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) == component.flattenToString()
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsScreen(
    setup: SetupState,
    engine: EngineStatus,
    darkMode: Boolean,
    spaceCursorSensitivity: Int,
    quickPhrases: List<QuickPhrase>,
    giphyKey: String,
    symbolKey: SymbolKeyConfig,
    inputScheme: InputScheme,
    onInputSchemeChange: (InputScheme) -> Unit,
    palette: AppPalette,
    onEnable: () -> Unit,
    onSelect: () -> Unit,
    onGiphyKeySave: (String) -> Unit,
    onSymbolKeySave: (SymbolKeyConfig) -> Unit,
    onSymbolKeyReset: () -> Unit,
    voiceModel: VoiceModel.State,
    micGranted: Boolean,
    onVoiceDownload: () -> Unit,
    onVoiceCancel: () -> Unit,
    onVoiceDelete: () -> Unit,
    onMicPermission: () -> Unit,
    onRedeploy: () -> Unit,
    onDarkModeChange: (Boolean) -> Unit,
    onSpaceCursorSensitivityChange: (Int) -> Unit,
    onQuickPhraseSave: (String?, String, String) -> String?,
    onQuickPhraseDelete: (String) -> Unit
) {
    var shortcutCode by rememberSaveable { mutableStateOf("") }
    var shortcutText by rememberSaveable { mutableStateOf("") }
    var editingShortcut by rememberSaveable { mutableStateOf<String?>(null) }
    var shortcutError by rememberSaveable { mutableStateOf<String?>(null) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = Color(UiTheme.palette(darkMode).key),
        unfocusedContainerColor = Color(UiTheme.palette(darkMode).key),
        focusedTextColor = palette.text, unfocusedTextColor = palette.text,
        focusedBorderColor = palette.accent, unfocusedBorderColor = palette.outline,
        focusedLabelColor = palette.accent, unfocusedLabelColor = palette.muted,
        cursorColor = palette.accent
    )
    var tab by rememberSaveable { mutableStateOf(SettingsTab.START) }
    Box(Modifier.fillMaxSize().background(palette.page).safeDrawingPadding().imePadding()) {
        Column(Modifier.fillMaxSize()) {
            // Tabs crossfade; each one starts scrolled to the top.
            Crossfade(targetState = tab, modifier = Modifier.weight(1f).fillMaxWidth(),
                animationSpec = tween(180), label = "settings-tab") { current ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Column(
                        modifier = Modifier.widthIn(max = 640.dp).fillMaxSize()
                            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier.size(40.dp).background(palette.soft, RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) { Icon(painterResource(R.drawable.ic_brand_mark), contentDescription = null, tint = palette.accent,
                                    modifier = Modifier.size(32.dp)) }
                                Column(Modifier.padding(start = 12.dp)) {
                                    Text(stringResource(R.string.app_name), fontSize = 24.sp, fontFamily = UiFonts.display,
                                        fontWeight = FontWeight.Normal, letterSpacing = (-0.3).sp, color = palette.text)
                                    Text("Rime · 離線拼音輸入", fontSize = 12.sp, color = palette.muted)
                                }
                            }

                        when (current) {
                            SettingsTab.START -> {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("每一次輸入，都更自在。", color = palette.text, fontSize = 28.sp,
                                        lineHeight = 34.sp, fontFamily = UiFonts.display,
                                        letterSpacing = (-0.5).sp, fontWeight = FontWeight.Normal)
                                    Text("在你的裝置上，整理自己的輸入習慣。",
                                        color = palette.muted, fontSize = 14.sp, lineHeight = 21.sp)
                                }

                                SettingsSection("開始使用", palette) {
                                    SetupCard("啟用輸入法", "在系統設定中開啟 It's My Rime", setup.enabled, onEnable, palette)
                                    SetupCard("設為目前鍵盤", "從輸入法清單選擇 It's My Rime", setup.selected, onSelect, palette)
                                }

                                SettingsSection("試試手感", palette) {
                                    TypingTestCard(palette, fieldColors)
                                }
                            }
                            SettingsTab.INPUT -> {
                                SettingsSection("輸入方案", palette) {
                                    InputSchemeCard(inputScheme, onInputSchemeChange, palette)
                                }

                                SettingsSection("符號快捷鍵", palette) {
                                    SymbolKeyCard(symbolKey, onSymbolKeySave, onSymbolKeyReset, palette, fieldColors)
                                }

                                SettingsSection("常用字", palette) {
                                    Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = palette.card)) {
                                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Text("輸入縮寫，從候選欄選擇常用內容。", fontSize = 13.sp, color = palette.muted)
                                            OutlinedTextField(
                                                value = shortcutCode,
                                                onValueChange = { shortcutCode = it; shortcutError = null },
                                                label = { Text("縮寫") },
                                                placeholder = { Text("例如 id") },
                                                singleLine = true,
                                                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                                                colors = fieldColors, isError = shortcutError != null
                                            )
                                            OutlinedTextField(
                                                value = shortcutText,
                                                onValueChange = { shortcutText = it; shortcutError = null },
                                                label = { Text("輸出內容") },
                                                placeholder = { Text("例如 E14135065") },
                                                singleLine = true,
                                                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                                                colors = fieldColors, isError = shortcutError != null
                                            )
                                            if (shortcutError != null) {
                                                Text(shortcutError.orEmpty(), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                                            }
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically) {
                                                if (editingShortcut != null) {
                                                    TextButton(onClick = {
                                                        editingShortcut = null
                                                        shortcutCode = ""
                                                        shortcutText = ""
                                                        shortcutError = null
                                                    }) { Text("取消") }
                                                }
                                                Button(onClick = {
                                                    val error = onQuickPhraseSave(editingShortcut, shortcutCode, shortcutText)
                                                    shortcutError = error
                                                    if (error == null) {
                                                        editingShortcut = null
                                                        shortcutCode = ""
                                                        shortcutText = ""
                                                    }
                                                }, enabled = shortcutCode.isNotBlank() && shortcutText.isNotBlank(),
                                                    shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                                    colors = ButtonDefaults.buttonColors(containerColor = palette.button, contentColor = Color.White,
                                                        disabledContainerColor = palette.soft, disabledContentColor = palette.muted)) {
                                                    Text(if (editingShortcut == null) "新增常用字" else "儲存修改")
                                                }
                                            }
                                            if (quickPhrases.isNotEmpty()) {
                                                HorizontalDivider(color = palette.outline)
                                                Text("已儲存 ${quickPhrases.size} 筆", fontSize = 12.sp, color = palette.muted)
                                                quickPhrases.forEach { phrase ->
                                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                            Text(phrase.code, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                                                                color = palette.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                            Text(phrase.text, color = palette.text, maxLines = 2,
                                                                overflow = TextOverflow.Ellipsis, fontSize = 14.sp, lineHeight = 21.sp)
                                                        }
                                                        IconButton(onClick = {
                                                            editingShortcut = phrase.code
                                                            shortcutCode = phrase.code
                                                            shortcutText = phrase.text
                                                            shortcutError = null
                                                        }) { Icon(Icons.Outlined.Edit, contentDescription = "編輯 ${phrase.code}",
                                                            tint = palette.muted, modifier = Modifier.size(20.dp)) }
                                                        IconButton(onClick = {
                                                            onQuickPhraseDelete(phrase.code)
                                                            if (editingShortcut == phrase.code) {
                                                                editingShortcut = null
                                                                shortcutCode = ""
                                                                shortcutText = ""
                                                            }
                                                            shortcutError = null
                                                        }) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "刪除 ${phrase.code}",
                                                            tint = palette.muted, modifier = Modifier.size(20.dp)) }
                                                    }
                                                }
                                            }
                                            Text("儲存後會自動重新部署 Rime；常用字適用於中文輸入模式。",
                                                fontSize = 12.sp, color = palette.muted)
                                        }
                                    }
                                }

                                SettingsSection("鍵盤操作", palette) {
                                    Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = palette.card)) {
                                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Box(Modifier.size(36.dp).background(palette.soft, RoundedCornerShape(8.dp)),
                                                    contentAlignment = Alignment.Center) {
                                                    Icon(Icons.Outlined.Settings, contentDescription = null, tint = palette.muted,
                                                        modifier = Modifier.size(20.dp))
                                                }
                                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                                    Text("空白鍵滑動靈敏度", fontSize = 16.sp, fontWeight = FontWeight.Medium,
                                                        color = palette.text)
                                                    Text("目前：${listOf("較低", "偏低", "標準", "偏高", "較高")[spaceCursorSensitivity - 1]}",
                                                        fontSize = 12.sp, color = palette.muted)
                                                }
                                            }
                                            Spacer(Modifier.height(12.dp))
                                            Slider(
                                                value = spaceCursorSensitivity.toFloat(),
                                                onValueChange = { onSpaceCursorSensitivityChange(it.roundToInt()) },
                                                valueRange = 1f..5f,
                                                steps = 3,
                                                colors = SliderDefaults.colors(thumbColor = palette.button, activeTrackColor = palette.button,
                                                    inactiveTrackColor = palette.outline, activeTickColor = Color.White,
                                                    inactiveTickColor = palette.muted)
                                            )
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text("滑動較遠才移動", fontSize = 12.sp, color = palette.muted)
                                                Text("輕滑即可移動", fontSize = 12.sp, color = palette.muted)
                                            }
                                        }
                                    }
                                }
                            }
                            SettingsTab.FEATURES -> {
                                SettingsSection("語音輸入", palette) {
                                    VoiceInputCard(voiceModel, micGranted, onVoiceDownload, onVoiceCancel, onVoiceDelete,
                                        onMicPermission, palette)
                                }

                                SettingsSection("GIF 搜尋", palette) {
                                    GiphyKeyCard(giphyKey, onGiphyKeySave, palette, fieldColors)
                                }
                            }
                            SettingsTab.MORE -> {
                                SettingsSection("外觀", palette) {
                                    Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = palette.card)) {
                                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Box(Modifier.size(36.dp).background(palette.soft, RoundedCornerShape(8.dp)),
                                                contentAlignment = Alignment.Center) {
                                                Icon(Icons.Outlined.DarkMode, contentDescription = null, tint = palette.muted,
                                                    modifier = Modifier.size(20.dp))
                                            }
                                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                                Text("深色模式", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = palette.text)
                                                Text("設定頁與鍵盤使用暖色深色配色", fontSize = 12.sp, color = palette.muted)
                                            }
                                            Switch(checked = darkMode, onCheckedChange = onDarkModeChange,
                                                colors = SwitchDefaults.colors(checkedTrackColor = palette.button,
                                                    checkedThumbColor = Color(UiTheme.dark.ink), uncheckedTrackColor = palette.soft,
                                                    uncheckedThumbColor = palette.muted, uncheckedBorderColor = palette.outline))
                                        }
                                    }
                                }

                                SettingsSection("Rime 引擎", palette) {
                                    Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = palette.hero)) {
                                        Column(Modifier.fillMaxWidth().padding(20.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Icon(Icons.Outlined.Settings, contentDescription = null, tint = palette.heroDetail,
                                                    modifier = Modifier.size(20.dp))
                                                Text(stringResource(R.string.brand_caption), fontSize = 20.sp, fontFamily = UiFonts.display,
                                                    fontWeight = FontWeight.Normal, color = Color(UiTheme.dark.ink))
                                            }
                                            Spacer(Modifier.height(12.dp))
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                if (engine.busy) {
                                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp,
                                                        color = Color(UiTheme.dark.accent))
                                                } else {
                                                    Icon(if (engine.ready) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber,
                                                        contentDescription = null,
                                                        tint = Color(if (engine.ready) UiTheme.dark.success else UiTheme.dark.warning),
                                                        modifier = Modifier.size(18.dp))
                                                }
                                                Text(engine.message, fontSize = 14.sp, lineHeight = 21.sp, color = palette.heroDetail,
                                                    modifier = Modifier.weight(1f))
                                            }
                                            Spacer(Modifier.height(16.dp))
                                            Button(
                                                onClick = onRedeploy,
                                                enabled = !engine.busy,
                                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = palette.button, contentColor = Color.White,
                                                    disabledContainerColor = Color(UiTheme.dark.inset), disabledContentColor = palette.heroDetail)
                                            ) {
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                                    Text("重新部署 Rime", fontWeight = FontWeight.Medium)
                                                }
                                            }
                                            Spacer(Modifier.height(8.dp))
                                            Text("重新編譯目前的設定與詞庫。", color = palette.heroDetail, fontSize = 12.sp)
                                        }
                                    }
                                }

                                Text("Rime × 霧凇拼音", Modifier.fillMaxWidth().padding(bottom = 10.dp),
                                    textAlign = TextAlign.Center, color = palette.muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
            // Hidden while typing in a settings field so the bar doesn't ride on top of the keyboard.
            if (!WindowInsets.isImeVisible) SettingsNavigationBar(tab, { tab = it }, palette)
        }
    }
}

private enum class SettingsTab(val label: String, val icon: ImageVector) {
    START("開始", Icons.Outlined.Home),
    INPUT("輸入", Icons.Outlined.Keyboard),
    FEATURES("功能", Icons.Outlined.Extension),
    MORE("其他", Icons.Outlined.Tune),
}

@Composable
private fun SettingsNavigationBar(selected: SettingsTab, onSelect: (SettingsTab) -> Unit, palette: AppPalette) {
    HorizontalDivider(color = palette.outline)
    // The outer Box already pads for system bars, so the bar adds no insets of its own.
    NavigationBar(containerColor = palette.page, tonalElevation = 0.dp, windowInsets = WindowInsets(0)) {
        SettingsTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label, fontSize = 12.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = palette.accent, selectedTextColor = palette.accent,
                    indicatorColor = palette.soft,
                    unselectedIconColor = palette.muted, unselectedTextColor = palette.muted
                )
            )
        }
    }
}

@Composable
private fun TypingTestCard(palette: AppPalette, fieldColors: TextFieldColors) {
    // A test keystroke invalidates this card, not the entire settings screen.
    var testText by rememberSaveable { mutableStateOf("") }
    Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = palette.card)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("點擊下方輸入框，試著輸入 nihao。", fontSize = 13.sp, color = palette.muted)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = testText, onValueChange = { testText = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("在這裡試打文字…", color = palette.muted) },
                minLines = 2, maxLines = 5,
                shape = RoundedCornerShape(8.dp), colors = fieldColors
            )
        }
    }
}

@Composable
private fun GiphyKeyCard(savedKey: String, onSave: (String) -> Unit, palette: AppPalette, fieldColors: TextFieldColors) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var revealed by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val buildKey = BuildConfig.GIPHY_SDK_KEY.isNotBlank()
    val done = savedKey.isNotBlank() || buildKey
    val invalid = draft.isNotBlank() && !GiphySettings.isValid(draft.trim())
    Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = palette.card)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(32.dp).background(palette.soft, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(if (done) Icons.Outlined.CheckCircle else Icons.Outlined.Key,
                        contentDescription = null, tint = if (done) palette.success else palette.muted,
                        modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("GIF 搜尋金鑰", fontWeight = FontWeight.Medium, color = palette.text, fontSize = 15.sp)
                    Text(when {
                        savedKey.isNotBlank() -> "已設定 ${GiphySettings.masked(savedKey)}"
                        buildKey -> "使用建置時的金鑰"
                        else -> "選填，填入 GIPHY 金鑰後可搜尋 GIF"
                    }, color = palette.muted, fontSize = 12.sp)
                }
                if (!editing) {
                    TextButton(onClick = {
                        draft = savedKey
                        revealed = false
                        editing = true
                    }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (savedKey.isNotBlank()) "修改" else "填入", fontSize = 13.sp)
                    }
                }
            }
            if (editing) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("GIPHY Android SDK 金鑰") },
                    singleLine = true,
                    isError = invalid,
                    visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { revealed = !revealed }) {
                            Icon(if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (revealed) "隱藏金鑰" else "顯示金鑰", tint = palette.muted)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), colors = fieldColors
                )
                if (invalid) {
                    Text("金鑰只能包含英文字母、數字、- 與 _", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { editing = false; draft = "" }) { Text("取消") }
                    if (savedKey.isNotBlank()) {
                        TextButton(onClick = {
                            onSave("")
                            editing = false
                            draft = ""
                        }) { Text("清除") }
                    }
                    Button(onClick = {
                        onSave(draft)
                        editing = false
                        draft = ""
                    }, enabled = draft.isNotBlank() && !invalid,
                        shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.button, contentColor = Color.White,
                            disabledContainerColor = palette.soft, disabledContentColor = palette.muted)) {
                        Text("儲存金鑰")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("金鑰只保存在本機。", fontSize = 12.sp, color = palette.muted, modifier = Modifier.weight(1f))
                    TextButton(onClick = { uriHandler.openUri("https://developers.giphy.com/dashboard/") }) {
                        Text("申請金鑰", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun InputSchemeCard(selected: InputScheme, onSelect: (InputScheme) -> Unit, palette: AppPalette) {
    Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = palette.card)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InputScheme.entries.forEach { scheme ->
                val active = scheme == selected
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (active) palette.soft else Color.Transparent, RoundedCornerShape(10.dp))
                        .border(1.dp, if (active) palette.button else palette.outline, RoundedCornerShape(10.dp))
                        .clickable { onSelect(scheme) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(when (scheme) {
                            InputScheme.PINYIN -> "拼音"
                            InputScheme.ZHUYIN -> "注音"
                        }, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = palette.text)
                        Text(when (scheme) {
                            InputScheme.PINYIN -> "霧凇拼音，全拼與簡拼"
                            InputScheme.ZHUYIN -> "大千式鍵盤 ㄅㄆㄇㄈ，可省略聲調"
                        }, fontSize = 12.sp, color = palette.muted)
                    }
                    if (active) Icon(Icons.Outlined.CheckCircle, contentDescription = "使用中",
                        tint = palette.button, modifier = Modifier.size(20.dp))
                }
            }
            Text("兩種方案共用同一份詞庫與詞頻；英文模式與密碼欄位一律使用英文鍵盤。",
                fontSize = 12.sp, lineHeight = 18.sp, color = palette.muted)
        }
    }
}

@Composable
private fun VoiceInputCard(
    model: VoiceModel.State,
    micGranted: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onMicPermission: () -> Unit,
    palette: AppPalette
) {
    val sizeMb = VoiceModel.totalBytes / 1_000_000
    val buttonColors = ButtonDefaults.buttonColors(containerColor = palette.button, contentColor = Color.White,
        disabledContainerColor = palette.soft, disabledContentColor = palette.muted)
    Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = palette.card)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).background(palette.soft, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center) {
                    Icon(if (model == VoiceModel.State.Ready) Icons.Outlined.CheckCircle else Icons.Outlined.Mic,
                        contentDescription = null, modifier = Modifier.size(20.dp),
                        tint = if (model == VoiceModel.State.Ready) palette.success else palette.muted)
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("離線語音模型", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = palette.text)
                    Text(when (model) {
                        VoiceModel.State.Missing -> "尚未下載・約 $sizeMb MB"
                        is VoiceModel.State.Downloading ->
                            "下載中 ${model.downloaded * 100 / model.total.coerceAtLeast(1)}%" +
                                "・${model.downloaded / 1_000_000} / ${model.total / 1_000_000} MB"
                        VoiceModel.State.Ready -> "已就緒・SenseVoice 中英辨識"
                        is VoiceModel.State.Failed -> model.message
                    }, fontSize = 12.sp, color = if (model is VoiceModel.State.Failed)
                        MaterialTheme.colorScheme.error else palette.muted)
                }
            }
            if (model is VoiceModel.State.Downloading) {
                LinearProgressIndicator(
                    progress = { model.downloaded.toFloat() / model.total.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth(), color = palette.button, trackColor = palette.soft
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                when (model) {
                    is VoiceModel.State.Downloading -> TextButton(onClick = onCancel) { Text("暫停下載") }
                    VoiceModel.State.Ready -> TextButton(onClick = onDelete) { Text("刪除模型") }
                    else -> Button(onClick = onDownload, shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp), colors = buttonColors) {
                        Text(if (model is VoiceModel.State.Failed) "重新下載" else "下載模型（約 $sizeMb MB）")
                    }
                }
            }
            HorizontalDivider(color = palette.outline)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("麥克風權限", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = palette.text)
                    Text(if (micGranted) "已允許" else "語音輸入需要使用麥克風", fontSize = 12.sp, color = palette.muted)
                }
                if (!micGranted) TextButton(onClick = onMicPermission) { Text("允許") }
                else Icon(Icons.Outlined.CheckCircle, contentDescription = "已允許", tint = palette.success,
                    modifier = Modifier.size(20.dp))
            }
            Text("下載後按住空白鍵說話，鬆開即輸入；往左滑取消、往右滑輸入並傳送。辨識完全在手機上進行，" +
                "聲音不會上傳；模型較大，建議使用 Wi-Fi 下載，中斷後可接續。",
                fontSize = 12.sp, lineHeight = 18.sp, color = palette.muted)
        }
    }
}

@Composable
private fun SymbolKeyCard(
    config: SymbolKeyConfig,
    onSave: (SymbolKeyConfig) -> Unit,
    onReset: () -> Unit,
    palette: AppPalette,
    fieldColors: TextFieldColors
) {
    // Keyed on the saved config so the fields refresh after saving or restoring defaults.
    var primary by rememberSaveable(config) { mutableStateOf(config.primary) }
    var secondary by rememberSaveable(config) { mutableStateOf(config.secondary) }
    var menuText by rememberSaveable(config) { mutableStateOf(SymbolKeySettings.menuText(config.menu)) }
    val error = SymbolKeySettings.validationError(primary, secondary, menuText)
    val draft = SymbolKeyConfig(SymbolKeySettings.normalize(primary), SymbolKeySettings.normalize(secondary),
        SymbolKeySettings.parseMenu(menuText))
    Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = palette.card)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("空白鍵右側的符號鍵：點一下輸入主要符號，上滑輸入副符號，長按可從選單挑選。",
                fontSize = 13.sp, lineHeight = 19.sp, color = palette.muted)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(56.dp).background(palette.soft, RoundedCornerShape(10.dp))) {
                    Text(draft.secondary, Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 7.dp),
                        fontSize = 11.sp, color = palette.muted, fontFamily = UiFonts.display, maxLines = 1)
                    Text(draft.primary, Modifier.align(Alignment.Center), fontSize = 22.sp,
                        color = palette.text, fontFamily = UiFonts.display, maxLines = 1)
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    draft.menu.take(SymbolKeySettings.MAX_MENU).forEach { symbol ->
                        Box(Modifier.size(32.dp).background(palette.soft, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center) {
                            Text(symbol, fontSize = 15.sp, color = palette.text, fontFamily = UiFonts.display,
                                maxLines = 1)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = primary, onValueChange = { primary = it },
                    label = { Text("主要符號") }, placeholder = { Text("點一下") },
                    singleLine = true, isError = !SymbolKeySettings.isValidSymbol(draft.primary),
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), colors = fieldColors
                )
                OutlinedTextField(
                    value = secondary, onValueChange = { secondary = it },
                    label = { Text("副符號") }, placeholder = { Text("上滑") },
                    singleLine = true, isError = !SymbolKeySettings.isValidSymbol(draft.secondary),
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), colors = fieldColors
                )
            }
            OutlinedTextField(
                value = menuText, onValueChange = { menuText = it },
                label = { Text("長按選單") }, placeholder = { Text("例如 , . ? ! 、") },
                supportingText = { Text("以空白分隔，最多 ${SymbolKeySettings.MAX_MENU} 個符號") },
                singleLine = true, isError = !SymbolKeySettings.isValidMenu(menuText),
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), colors = fieldColors
            )
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            Text("逗號、句號、問號等標點會依鍵盤的「全／半」設定自動顯示為全形或半形；「、」「《》」等中文符號則照原樣輸出。",
                fontSize = 12.sp, lineHeight = 18.sp, color = palette.muted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onReset, enabled = config != SymbolKeySettings.DEFAULT) { Text("恢復預設") }
                Button(onClick = { onSave(draft) }, enabled = error == null && draft != config,
                    shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.button, contentColor = Color.White,
                        disabledContainerColor = palette.soft, disabledContentColor = palette.muted)) {
                    Text("儲存符號設定")
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, palette: AppPalette, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, color = palette.text, fontFamily = UiFonts.display,
            fontWeight = FontWeight.Normal, fontSize = 22.sp, letterSpacing = (-0.3).sp)
        content()
    }
}

@Composable
private fun SetupCard(title: String, detail: String, done: Boolean, onClick: () -> Unit, palette: AppPalette) {
    Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = palette.card)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(32.dp).background(palette.soft, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(if (done) Icons.Outlined.CheckCircle else Icons.Outlined.Keyboard,
                    contentDescription = null, tint = if (done) palette.success else palette.muted,
                    modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, fontWeight = FontWeight.Medium, color = palette.text, fontSize = 15.sp)
                Text(if (done) "已完成" else detail, color = palette.muted, fontSize = 12.sp)
            }
            TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (done) "設定" else "前往", fontSize = 13.sp)
            }
        }
    }
}
