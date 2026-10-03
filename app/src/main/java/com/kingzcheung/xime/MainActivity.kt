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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
    val badge: Color,
    val soft: Color,
    val outline: Color,
    val success: Color
)

private val LightPalette = AppPalette(
    page = Color(0xFFF5F7FB), card = Color.White, text = Color(0xFF142945),
    muted = Color(0xFF66778F), accent = Color(0xFF315FA6), button = Color(0xFF315FA6),
    hero = Color(0xFF142945), heroDetail = Color(0xFFBFCEE4), badge = Color(0xFF2A466B),
    soft = Color(0xFFEAF0FC), outline = Color(0xFFD9E1EE), success = Color(0xFF208B72)
)

private val DarkPalette = AppPalette(
    page = Color(0xFF101A2A), card = Color(0xFF1D2D43), text = Color(0xFFE9F1FC),
    muted = Color(0xFFABC0DB), accent = Color(0xFF91B9FA), button = Color(0xFF386BB8),
    hero = Color(0xFF19365A), heroDetail = Color(0xFFCADCF4), badge = Color(0xFF294B75),
    soft = Color(0xFF2C4566), outline = Color(0xFF4D6482), success = Color(0xFF65D2AC)
)

private data class SetupState(val enabled: Boolean, val selected: Boolean)

class MainActivity : ComponentActivity() {
    private var setup by mutableStateOf(SetupState(false, false))
    private var darkMode by mutableStateOf(false)
    private var spaceCursorSensitivity by mutableStateOf(SpaceCursorSettings.DEFAULT)
    private var quickPhrases by mutableStateOf(emptyList<QuickPhrase>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RimeManager.ensureReady(this)
        setup = readSetup()
        darkMode = AppearanceSettings.isDark(this)
        spaceCursorSensitivity = SpaceCursorSettings.read(this)
        quickPhrases = QuickPhrases.entries(this)
        applySystemBars()
        setContent {
            val palette = if (darkMode) DarkPalette else LightPalette
            var engineStatus by remember { mutableStateOf(RimeManager.status) }
            DisposableEffect(Unit) {
                val observer: (EngineStatus) -> Unit = { engineStatus = it }
                RimeManager.observe(observer)
                onDispose { RimeManager.removeObserver(observer) }
            }
            MaterialTheme(
                colorScheme = if (darkMode) darkColorScheme(
                    primary = palette.accent, onPrimary = palette.page,
                    background = palette.page, surface = palette.card,
                    onSurface = palette.text, outline = palette.outline
                ) else lightColorScheme(
                    primary = palette.accent, onPrimary = Color.White,
                    background = palette.page, surface = palette.card,
                    onSurface = palette.text, outline = palette.outline
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
        window.statusBarColor = if (darkMode) android.graphics.Color.rgb(16, 26, 42)
            else android.graphics.Color.rgb(245, 247, 251)
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
    var testText by remember { mutableStateOf("") }
    var shortcutCode by remember { mutableStateOf("") }
    var shortcutText by remember { mutableStateOf("") }
    var editingShortcut by remember { mutableStateOf<String?>(null) }
    var shortcutError by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier.fillMaxSize().background(palette.page).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 26.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(46.dp).background(palette.button, RoundedCornerShape(15.dp)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Outlined.Keyboard, contentDescription = null, tint = Color.White) }
            Column(Modifier.padding(start = 12.dp)) {
                Text(stringResource(R.string.app_name), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = palette.text)
                Text("Rime · 離線拼音輸入", fontSize = 12.sp, color = palette.muted)
            }
        }

        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = palette.hero)
        ) {
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Text("讓每一次輸入，都更順手。", color = Color.White, fontSize = 25.sp,
                    lineHeight = 33.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Text("以 Rime 引擎驅動，預設使用霧凇拼音。選擇詞語即可輸入中文。",
                    color = palette.heroDetail, fontSize = 14.sp, lineHeight = 21.sp)
                Spacer(Modifier.height(18.dp))
                Surface(shape = RoundedCornerShape(50.dp), color = palette.badge) {
                    Text("霧凇拼音  ·  本機運作", Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        color = Color(0xFFEAF3FF), fontSize = 12.sp)
                }
            }
        }

        SectionTitle("開始使用", "01 / 06", palette)
        SetupCard("啟用輸入法", "在系統設定中開啟 It's My Rime", setup.enabled, onEnable, palette)
        SetupCard("設為目前鍵盤", "從輸入法清單選擇 It's My Rime", setup.selected, onSelect, palette)

        SectionTitle("試試手感", "02 / 06", palette)
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = palette.card)) {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Text("輸入測試", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                Spacer(Modifier.height(4.dp))
                Text("點擊下方輸入框，試著輸入 nihao。", fontSize = 13.sp, color = palette.muted)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = testText,
                    onValueChange = { testText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("在這裡試打文字…", color = palette.muted) },
                    minLines = 3,
                    shape = RoundedCornerShape(18.dp)
                )
            }
        }

        SectionTitle("常用字", "03 / 06", palette)
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = palette.card)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("輸入縮寫，從候選欄選擇常用內容。", fontSize = 13.sp, color = palette.muted)
                OutlinedTextField(
                    value = shortcutCode,
                    onValueChange = { shortcutCode = it; shortcutError = null },
                    label = { Text("縮寫") },
                    placeholder = { Text("例如 id") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = shortcutText,
                    onValueChange = { shortcutText = it; shortcutError = null },
                    label = { Text("輸出內容") },
                    placeholder = { Text("例如 E14135065") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (shortcutError != null) {
                    Text(shortcutError.orEmpty(), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
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
                    }, shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.button)) {
                        Text(if (editingShortcut == null) "新增常用字" else "儲存修改")
                    }
                }
                if (quickPhrases.isNotEmpty()) {
                    Text("已儲存 ${quickPhrases.size} 筆", fontSize = 12.sp, color = palette.muted)
                    quickPhrases.forEach { phrase ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(9.dp), color = palette.soft) {
                                Text(phrase.code, Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                    color = palette.accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Text(phrase.text, Modifier.weight(1f).padding(horizontal = 10.dp),
                                color = palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                fontSize = 14.sp)
                            TextButton(onClick = {
                                editingShortcut = phrase.code
                                shortcutCode = phrase.code
                                shortcutText = phrase.text
                                shortcutError = null
                            }) { Text("編輯") }
                            TextButton(onClick = {
                                onQuickPhraseDelete(phrase.code)
                                if (editingShortcut == phrase.code) {
                                    editingShortcut = null
                                    shortcutCode = ""
                                    shortcutText = ""
                                }
                                shortcutError = null
                            }) { Text("刪除") }
                        }
                    }
                }
                Text("儲存後會自動重新部署 Rime；常用字適用於中文輸入模式。",
                    fontSize = 12.sp, color = palette.muted)
            }
        }

        SectionTitle("Rime 引擎", "04 / 06", palette)
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = palette.card)) {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Settings, contentDescription = null, tint = palette.accent)
                    Text("  方案與部署", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (engine.busy) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = null,
                            tint = if (engine.ready) palette.success else Color(0xFFBF7351),
                            modifier = Modifier.size(18.dp))
                    }
                    Text("  ${engine.message}", fontSize = 13.sp, color = palette.muted)
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onRedeploy,
                    enabled = !engine.busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(vertical = 13.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.button, contentColor = Color.White)
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  重新部署 Rime", fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                Text("重新編譯目前的霧凇拼音設定與詞庫。", color = palette.muted, fontSize = 12.sp)
            }
        }
        SectionTitle("外觀", "05 / 06", palette)
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = palette.card)) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).background(palette.soft, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.DarkMode, contentDescription = null, tint = palette.accent)
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("深色模式", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                    Text("設定頁與鍵盤使用深色配色", fontSize = 12.sp, color = palette.muted)
                }
                Switch(checked = darkMode, onCheckedChange = onDarkModeChange)
            }
        }
        SectionTitle("鍵盤操作", "06 / 06", palette)
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = palette.card)) {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).background(palette.soft, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Settings, contentDescription = null, tint = palette.accent)
                    }
                    Column(Modifier.padding(start = 12.dp)) {
                        Text("空白鍵滑動靈敏度", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
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
                    steps = 3
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("滑動較遠才移動", fontSize = 12.sp, color = palette.muted)
                    Text("輕滑即可移動", fontSize = 12.sp, color = palette.muted)
                }
            }
        }
        Text("Rime × 霧凇拼音", Modifier.fillMaxWidth().padding(bottom = 10.dp),
            textAlign = TextAlign.Center, color = palette.muted, fontSize = 12.sp)
    }
}

@Composable
private fun SectionTitle(title: String, index: String, palette: AppPalette) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = palette.text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text(index, color = palette.muted, fontSize = 12.sp)
    }
}

@Composable
private fun SetupCard(title: String, detail: String, done: Boolean, onClick: () -> Unit, palette: AppPalette) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = palette.card)) {
        Row(Modifier.fillMaxWidth().padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).background(palette.soft, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(if (done) Icons.Outlined.CheckCircle else Icons.Outlined.Keyboard,
                    contentDescription = null, tint = if (done) palette.success else palette.accent,
                    modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, color = palette.text, fontSize = 15.sp)
                Text(if (done) "已完成" else detail, color = palette.muted, fontSize = 12.sp)
            }
            if (!done) {
                Button(onClick = onClick, shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                    Text("前往", fontSize = 12.sp)
                }
            }
        }
    }
}
