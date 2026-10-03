# It's My Rime

基於 Rime 的 Android 輸入法，預設使用霧凇拼音。提供拼音鍵盤、候選詞、Emoji、GIPHY GIF 與 MyGO 梗圖搜尋、剪貼簿，以及附有測試框和「重新部署 Rime」按鈕的設定頁。

## 安裝與使用

1. 在 Android Studio 開啟此專案並執行 `gradlew.bat :app:assembleDebug`，然後安裝 `app/build/outputs/apk/debug/app-debug.apk`。
2. 開啟 App，點「啟用輸入法」，在 Android 系統設定中開啟「It's My Rime」。
3. 點「設為目前鍵盤」，選擇「It's My Rime」。
4. 在設定頁的測試框輸入拼音；點選候選詞即可上屏。首次啟動會部署詞庫，需稍候。

Shift 點一次會讓下一個字母大寫，快速連點會鎖定大寫，再點一次解除。輸入中的拼音會顯示在游標旁的浮動預覽；候選列右側的向下箭頭可展開覆蓋按鍵區的候選清單。候選欄與鍵盤高度保持固定。

字母鍵第一排的右上角標示 `1`–`0`，第二、三排則標示參考 Gboard 的標點符號。短按輸入拼音，長按預選角標，按住左右滑動可選擇英文字母大小寫；最左和最右的字母鍵都往鍵盤內側滑。彈出的選項會隨滑動顯示目前選擇，鬆手才輸出。

Shift 與刪除鍵加寬。按鍵外觀加大，觸控範圍延伸到相鄰按鍵之間的空隙，同時維持鍵盤原有高度。刪除鍵短按逐字刪除，長按且不滑動會連續刪除；直接上滑清空目前輸入框、下滑復原上一次清空，長按後滑動也可使用。底排的 emoji 鍵位於空白左側、標點鍵位於空白右側；標點鍵短按輸入逗號，長按會彈出選項並預選句號。

空白鍵短按輸入空白，按住左右滑動可移動游標，靈敏度可在設定頁調整。`?123` 鍵上滑可快速切換中英文模式。所有鍵按住時背景會變深，按鍵、候選詞與候選列按鈕都有輕觸震動。空白鍵只標示目前模式「中」或「En」。英文模式下標點鍵輸入半形逗號及句號。

沒有候選字時，鍵盤上方的「简／繁」按鈕可切換簡體與台灣繁體輸出；繁體轉換使用 OpenCC `s2tw`。「全／半」按鈕可切換標點寬度，並保留中英文模式各自的設定。設定頁亦可切換深色模式。

Emoji 鍵短按會打開可分類瀏覽的表情面板，點選即可輸入；左右拖動可跟手切換分類，最近使用的表情保存在本機。表情資料取自 Unicode Emoji 18.0 的 3,963 個 fully-qualified 項目，依常見的九大類排列，並會依裝置字型支援情況過濾。人物表情的中性、男性、女性樣式及各自膚色收在同一個長按選單；其餘支援膚色的表情也可長按選擇。雙人牽手、親吻、情侶、握手、兔耳及摔角的混合膚色組合也收在各自的代表表情之下；長按代表表情後，可從選擇頁直接點選組合。長按 Emoji 鍵後左滑進入 GIF 搜尋頁，右滑進入 MyGO 梗圖搜尋頁。GIF 分頁使用 GIPHY Android SDK 顯示熱門動圖並搜尋；點選 GIF 時，支援 `image/gif` 的輸入框會直接接收，不支援的輸入框會將 GIF 放入系統剪貼簿。MyGO 分頁使用 [miyago9267/MyGO-Searcher](https://github.com/miyago9267/MyGO-Searcher) 的 [v1 API](https://github.com/miyago9267/MyGO-Searcher/blob/main/docs/API.md) 顯示熱門圖並搜尋梗圖。點選圖片時，支援圖片輸入的 App 會直接接收圖片；其他輸入框則會把圖片存入系統和鍵盤剪貼簿。鍵盤剪貼簿可保存文字與圖片，點選圖片可再次插入，長按可收藏。

支援 Android 9（API 28）以上的 arm64-v8a 與 x86_64 裝置。輸入與詞庫部署在本機完成；GIF、MyGO 搜尋與圖片下載需要網路連線。

## 建置

需要 Android SDK 36 與 JDK 17 以上。Windows：

若要啟用 GIF 搜尋，請向 GIPHY 申請 Android SDK 金鑰，並在不會提交的 `local.properties` 加入 `GIPHY_SDK_KEY=你的金鑰`。未設定金鑰時仍可建置，GIF 分頁會顯示設定提示。

```powershell
.\gradlew.bat :app:assembleDebug
```

產物位於 `app/build/outputs/apk/debug/app-debug.apk`。

為了保留既有安裝與輸入法設定，Android `applicationId` 及輸入法服務類別名稱維持不變。

## 授權與來源

本專案依 GPL-3.0 授權，詳見 [LICENSE](LICENSE)。Rime 原生橋接與霧凇拼音資源的對應版本、來源和授權見 [THIRD_PARTY.md](THIRD_PARTY.md)。
