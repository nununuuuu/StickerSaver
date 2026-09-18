## v0.2.4

### 剪貼簿
- 修正先複製 Threads 連結、再進入 Sticker Saver 時不會跳出貼入詢問的問題。
- 現在除了監聽 App 使用中的剪貼簿變化，也會在 App 回到前景時主動檢查目前剪貼簿。

### 來源紀錄
- 備註改成有底色的清楚標籤，不再使用難辨識的淡色小字。
- 貼文文字預設收合，以向下箭頭展開、向上箭頭收合。
- 長按來源卡片可刪除整筆來源，包含該來源的貼圖紀錄與本機快取。
- 卡片右下角新增「前往」按鈕，可直接開啟原始 Threads 來源網址。

### Threads 分享
- MainActivity 改為 singleTask，從 Threads 分享到已在背景的 Sticker Saver 時會重用原本的 App 任務並透過 onNewIntent 接收連結，不再建立多個背景 App。

### App 圖示
- 重新套用使用者指定的 Sticker Saver 米白笑臉下載圖示。
- Launcher 改使用新的 adaptive icon 資源名稱，避免舊的 Android 預設／舊圖示資源被系統解析到。
