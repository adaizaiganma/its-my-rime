package com.kingzcheung.xime

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RimeManager.ensureReady(this)
        ClipboardHistory.startCleanup(this)
        setup = readSetup()
        darkMode = AppearanceSettings.isDark(this)
        spaceCursorSensitivity = SpaceCursorSettings.read(this)
        quickPhrases = QuickPhrases.entries(this)
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
                    palette = palette,
                    onEnable = { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                    onSelect = { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() },
                    onRedeploy = { RimeManager.redeploy(this) },
                    onDarkModeChange = { enabled ->
                        AppearanceSettings.setDark(this, enabled)
                        darkMode = enabled
                        applySystemBars()
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
                            RimeManager.redeploy(this)
                        }
                        error
                    },
                    onQuickPhraseDelete = { code ->
                        val updated = quickPhrases.filterNot { it.code == code }
                        QuickPhrases.save(this, updated)
                        quickPhrases = updated
                        RimeManager.redeploy(this)
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
        applySystemBars()
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

@Composable
private fun SettingsScreen(
    setup: SetupState,
    engine: EngineStatus,
    darkMode: Boolean,
    spaceCursorSensitivity: Int,
    quickPhrases: List<QuickPhrase>,
    palette: AppPalette,
    onEnable: () -> Unit,
    onSelect: () -> Unit,
    onRedeploy: () -> Unit,
    onDarkModeChange: (Boolean) -> Unit,
    onSpaceCursorSensitivityChange: (Int) -> Unit,
    onQuickPhraseSave: (String?, String, String) -> String?,
    onQuickPhraseDelete: (String) -> Unit
) {
    var testText by rememberSaveable { mutableStateOf("") }
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
    Box(Modifier.fillMaxSize().background(palette.page).safeDrawingPadding().imePadding()) {
        Column(
            modifier = Modifier.widthIn(max = 640.dp).fillMaxSize().align(Alignment.TopCenter)
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(40.dp).background(palette.soft, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Outlined.Keyboard, contentDescription = null, tint = palette.text,
                    modifier = Modifier.size(22.dp)) }
                Column(Modifier.padding(start = 12.dp)) {
                    Text(stringResource(R.string.app_name), fontSize = 24.sp, fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Normal, letterSpacing = (-0.3).sp, color = palette.text)
                    Text("Rime · 離線拼音輸入", fontSize = 12.sp, color = palette.muted)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("每一次輸入，都更自在。", color = palette.text, fontSize = 28.sp,
                    lineHeight = 34.sp, fontFamily = FontFamily.Serif,
                    letterSpacing = (-0.5).sp, fontWeight = FontWeight.Normal)
                Text("在你的裝置上，整理自己的輸入習慣。",
                    color = palette.muted, fontSize = 14.sp, lineHeight = 21.sp)
            }

            SettingsSection("開始使用", palette) {
                SetupCard("啟用輸入法", "在系統設定中開啟 It's My Rime", setup.enabled, onEnable, palette)
                SetupCard("設為目前鍵盤", "從輸入法清單選擇 It's My Rime", setup.selected, onSelect, palette)
            }

            SettingsSection("試試手感", palette) {
                Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = palette.card)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("點擊下方輸入框，試著輸入 nihao。", fontSize = 13.sp, color = palette.muted)
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = testText,
                            onValueChange = { testText = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("在這裡試打文字…", color = palette.muted) },
                            minLines = 2, maxLines = 5,
                            shape = RoundedCornerShape(8.dp), colors = fieldColors
                        )
                    }
                }
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

            SettingsSection("Rime 引擎", palette) {
                Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = palette.hero)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.Settings, contentDescription = null, tint = palette.heroDetail,
                                modifier = Modifier.size(20.dp))
                            Text("霧凇拼音", fontSize = 20.sp, fontFamily = FontFamily.Serif,
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
            Text("Rime × 霧凇拼音", Modifier.fillMaxWidth().padding(bottom = 10.dp),
                textAlign = TextAlign.Center, color = palette.muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SettingsSection(title: String, palette: AppPalette, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, color = palette.text, fontFamily = FontFamily.Serif,
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
