<div align="center">

# It's My Rime

**每一次輸入，都更自在。**

一款好看、好用、完全離線的 Android 中文輸入法。

[![It's My Rime 功能介紹](docs/media/preview.gif)](docs/media/its-my-rime-keynote.mp4)

[▶ 觀看完整介紹影片](docs/media/its-my-rime-keynote.mp4) ・ [⬇ 下載最新版](https://github.com/adaizaiganma/its-my-rime/releases)

</div>

## 為什麼選它

- **打字就是快**：拼音即時顯示在游標旁，候選詞一點就上屏。
- **隱私放心**：輸入與詞庫全部在手機上完成，你打的字不會被上傳。
- **一鍵多用**：上滑、長按就能輸入數字、符號、換行、切換中英，不用切頁面找鍵。
- **聊天更有梗**：內建 3,963 個表情、GIF 搜尋，還有 MyGO 梗圖。
- **剪貼簿與編輯工具**：記得你複製的東西，選取、剪下、貼上都在鍵盤上完成。
- **看起來舒服**：奶油色與珊瑚色的暖色設計，淺色、深色都好看。

## 開始使用

1. 到 [Releases](https://github.com/adaizaiganma/its-my-rime/releases) 下載最新的 `.apk` 並安裝。手機若詢問「是否允許安裝未知來源的應用程式」，請選擇允許。
2. 打開 **It's My Rime**，依序點「啟用輸入法」和「設為目前鍵盤」。
3. 在設定頁的「試試手感」輸入框打 `nihao`，看到「你好」就完成了！

> 第一次開啟時需要準備詞庫，大約等待幾秒鐘。

想用 GIF 搜尋的話，可以到 [GIPHY](https://developers.giphy.com/dashboard/) 免費申請一組 Android SDK 金鑰，貼到設定頁的「GIF 搜尋金鑰」。不設定也不影響其他功能。

## 手勢小技巧

| 想做什麼 | 怎麼做 |
|---|---|
| 輸入數字或符號 | 長按字母鍵（角落的小字就是它的第二功能），或往上滑 |
| 大寫字母 | 點一次 Shift 大寫下一個字，快速點兩次鎖定大寫 |
| 在聊天 App 裡換行 | 往上滑 Enter 鍵 |
| 清空整段文字 | 往上滑刪除鍵；滑錯了往下滑就能復原 |
| 移動游標 | 按住空白鍵左右滑動 |
| 切換中文／英文 | 往上滑 `?123` 鍵 |
| 打開 GIF／MyGO 搜尋 | 長按表情鍵，往左滑是 GIF、往右滑是 MyGO |

## 功能一覽

### 打字

- 候選詞放不下時，點右側箭頭展開完整清單。
- 鍵盤上方的「繁／简」一鍵切換繁體與簡體，「全／半」切換全形或半形標點。
- 符號頁長按數字可打出上標、下標（如 ² ₂），括號鍵提供《》〈〉等成對符號。
- **常用字**：在設定頁把縮寫綁定成常用內容（例如輸入 `dz` 就出現你的地址），打縮寫時會出現在候選欄。

### 表情、GIF 與梗圖

- 表情依九大類排列，左右滑動切換分類；人物和手勢可長按選膚色與性別。
- 「最近使用」會記住你常用的表情，選的時候不會跳動，關掉再開才更新。
- GIF 與 MyGO 梗圖可以直接搜尋。支援圖片的 App 會直接送出；不支援的會自動存進剪貼簿，方便貼上。

### 剪貼簿

- 自動記住最近 24 小時複製的文字和圖片，長按可以收藏，收藏的內容會一直保留。
- 剛複製完再打開鍵盤，上方會出現「快速貼上」，點一下就貼上。
- 在密碼欄位，或複製的是被標記為敏感的內容時，不會出現快速貼上提示。

### 文字編輯

剪貼簿旁的編輯按鈕會打開編輯面板：用方向鍵移動游標，開啟中間的「選取」就能用方向鍵選字，右側的 `Ctrl+A`／`X`／`C`／`V` 對應全選、剪下、複製、貼上。

### 外觀

設定頁可以切換淺色或深色模式，鍵盤和設定頁的配色會一起改變。

## 常見問題

**需要網路嗎？**
打字完全不需要網路。只有 GIF、MyGO 梗圖搜尋和圖片下載需要連線。

**支援哪些手機？**
Android 9 以上的手機都可以使用（支援 arm64 與 x86_64 架構，涵蓋絕大多數手機與模擬器）。

**安裝更新時出現「應用程式未安裝」或簽章不符？**
請先解除安裝舊版，再安裝新版。目前的測試版使用開發用簽章，正式簽章版本會另行發佈。

**GIF 頁面顯示「尚未設定 GIPHY 金鑰」？**
請到設定頁的「GIF 搜尋金鑰」填入你的金鑰。金鑰只會保存在你的手機上。

**打字資料會被收集嗎？**
不會。輸入法不會上傳你打的字，剪貼簿記錄也只存在手機裡。唯一會連網的是 GIF 與 MyGO 梗圖搜尋：你輸入的搜尋字詞會送到對應的搜尋服務。

<details>
<summary><b>給開發者</b></summary>

### 建置

需要 Android SDK 36 與 JDK 17 以上。Windows：

```powershell
.\gradlew.bat :app:assembleDebug
```

產物位於 `app/build/outputs/apk/debug/app-debug.apk`。

GIPHY 金鑰建議在 App 設定頁填入。開發時也可在不會提交的 `local.properties` 加入 `GIPHY_SDK_KEY=你的金鑰`，作為設定頁未填寫時的預設值；此金鑰會打包進 APK，公開發佈的版本請勿設定。

為了保留既有安裝與輸入法設定，Android `applicationId` 及輸入法服務類別名稱維持不變。

### 技術細節

- 輸入引擎為 [Rime](https://rime.im/)，預設方案為霧凇拼音；繁體轉換使用 OpenCC `s2tw`。
- 表情資料取自 Unicode Emoji 18.0 的 3,963 個 fully-qualified 項目，並依裝置字型支援情況過濾。
- GIF 使用 GIPHY Android SDK；MyGO 梗圖使用 [miyago9267/MyGO-Searcher](https://github.com/miyago9267/MyGO-Searcher) 的 [v1 API](https://github.com/miyago9267/MyGO-Searcher/blob/main/docs/API.md)。
- 介面參考 [Claude DESIGN.md](https://github.com/VoltAgent/awesome-design-md/blob/main/design-md/claude/DESIGN.md)，配色與元件規範見 [DESIGN.md](DESIGN.md)；效能改善與實機確認項目見 [PERFORMANCE.md](PERFORMANCE.md)。
- 字體為離線打包的 Cormorant Garamond、Noto Serif TC、Inter 與 Noto Sans TC，中英文字形整合為同一份字型以支援 Android 9；App 圖示的 SVG 原稿在 `artwork` 目錄。
- 介紹影片與預覽動畫位於 `docs/media`。

</details>

## 授權與來源

本專案依 GPL-3.0 授權，詳見 [LICENSE](LICENSE)。Rime 原生橋接與霧凇拼音資源的版本、來源和授權見 [THIRD_PARTY.md](THIRD_PARTY.md)。
