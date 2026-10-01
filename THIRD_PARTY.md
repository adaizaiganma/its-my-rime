# 第三方來源

- **Xime**：`RimeEngine.kt` 與隨 APK 打包的 `librime_jni.so` 來自 [ximeiorg/Xime v3.0.0-beta4](https://github.com/ximeiorg/Xime/tree/d20171a5ac6062f00156d5a7104afc6f7bf8ca7a)。原生函式庫分別抽取自該版本的 arm64-v8a 與 x86_64 發行 APK。Xime 採 GPL-3.0 授權；此專案保留其 JNI 套件名稱以維持二進位相容。完整原始碼與原生建置腳本請見上述固定版本。
- **霧凇拼音**：`app/src/main/assets/rime` 的方案、詞庫、Lua 與 OpenCC 資源來自 [iDvel/rime-ice](https://github.com/iDvel/rime-ice/tree/3aea6d3694fb3d94ec663641f021f788822897ad)，採 GPL-3.0 授權。資源目錄中保留原授權檔。
- **Rime / librime**：Xime 原生函式庫內含 [librime](https://github.com/rime/librime) 與其相依元件；對應來源與建置方式由上述 Xime 固定版本提供。
- **OpenCC 台灣繁體字典**：`app/src/main/assets/rime/opencc/STCharacters.txt`、`STPhrases.txt`、`TWVariants.txt` 與改為文字字典格式的 `s2tw.json` 來自 [BYVoid/OpenCC 1.1.9](https://github.com/BYVoid/OpenCC/tree/556ed22496d650bd0b13b6c163be9814637970ae)，採 Apache-2.0 授權；授權全文見 `app/src/main/assets/rime/opencc/LICENSE-OpenCC`。
- **MyGO 梗圖**：MyGO 分頁透過 [MyGO-Searcher 公開 API](https://github.com/miyago9267/MyGO-Searcher/blob/main/docs/API.md) 即時取得搜尋結果及圖片；本專案未打包其資料或程式碼。圖片內容與服務由該專案提供。
- **GIPHY GIF**：GIF 分頁透過 [GIPHY Android SDK](https://github.com/Giphy/giphy-android-sdk) 的 Grid-Only 元件搜尋和顯示動圖，建置時由 Maven Central 取得 SDK。GIF 內容由 GIPHY 服務提供，使用方式受其 [SDK 條款](https://developers.giphy.com/docs/sdk/) 約束。
- **Unicode Emoji 18.0**：`app/src/main/assets/emoji/emoji-18.0.txt` 與 `variants-18.0.txt` 由 [Unicode emoji-test.txt](https://www.unicode.org/Public/emoji/latest/emoji-test.txt) 的 fully-qualified 清單產生，保留 CLDR 順序；來源檔 SHA-256 為 `8f3735cda1f92a779d78af67cf86066bb1f07143dc22f2ac29394d9bc57ab21a`。產生工具為 `tools/generate_emoji_catalog.py`。資料採 Unicode License v3，授權全文見 `app/src/main/assets/emoji/LICENSE-Unicode`。

本專案的 Gradle wrapper 用於建置 Android 程式；執行建置會從設定的 Maven 倉庫取得 Android 與 Compose 依賴。
