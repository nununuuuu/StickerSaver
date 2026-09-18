## v0.2.6

### 新增功能
- 來源卡片改為直接點擊整張卡片展開／收合貼文文字，不再需要中央箭頭。

### 修正項目
- 修正 Android 15 前景切回 App 時剪貼簿 Threads 連結沒有提示的問題：改在 Activity 真正取得視窗焦點後讀取剪貼簿。
- 保留 App 使用中的剪貼簿即時監聽，支援一般 Threads 貼文網址與 threads.com/share/... 分享短網址。
- 強制將最終 Launcher 名稱設為「Sticker Saver」，同時覆寫 application 與 MainActivity 的 label。
- 強制將 application 與 MainActivity Launcher icon 指向 Sticker Saver 專用 adaptive icon。
- 建置完成後直接檢查最終 APK 的 AndroidManifest 與 icon 資源；名稱或圖示沒有真正打進 APK 時，Build 會直接失敗，不再只相信 workflow 修改腳本。
- 長按來源卡片刪除、備註與「前往」按鈕行為維持不變。
