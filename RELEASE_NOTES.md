## 新功能
- 主貼文貼圖改用 Threads preload JSON 解析，可直接取得 inline sticker / GIPHY sticker
- 留言解析改為讀取 Threads data-sjs JSON 內的 reply 物件
- 留言預設抓前 5 則「有貼圖的留言」，不再讓無貼圖留言佔名額
- 可選擇解析目前 preload 中全部有貼圖留言
- 來源仍可分別選擇「貼文」與「留言」，也可同時解析

## 修正
- 改用已驗證可取得 preload JSON 的 Desktop Chrome + Sec-Fetch HTTP headers
- 修正舊版只掃 HTML / img / video 時抓不到 Threads inline sticker 的問題
- 主貼文與留言會分開記錄來源，不再把整頁預載貼圖混成同一組
- 保留舊媒體解析邏輯作 fallback
- 留言模式不再標示為「熱門前 X」，避免與 Threads 未公開的熱門排序混淆

## 已知限制
- 「全部有貼圖留言」目前只包含 Threads 初始 preload JSON 內可取得的留言；尚未自動翻頁載入後續留言
