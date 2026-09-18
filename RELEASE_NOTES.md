## v0.2.7

### 修正項目
- 重構 Android Launcher 設定：App 名稱與圖示改為專案內正式 Manifest / strings / mipmap 資源，不再由 GitHub Actions 在建置時用 Python 或 sed 臨時修改。
- Launcher 名稱固定為 Sticker Saver。
- Launcher 圖示改用標準 @mipmap/ic_launcher / ic_launcher_round，內容直接使用指定的 Sticker Saver 圖片。
- 移除舊的重複 launcher icon、舊向量圖示與舊 WebP 資源，避免同一功能存在多套來源。
- GitHub Actions 只負責複製正式專案檔案、簽署、編譯與驗證，不再疊加 Manifest patch。
- 發布前會驗證最終 APK 的 Sticker Saver 名稱、MainActivity Launcher 與圖示資源。
