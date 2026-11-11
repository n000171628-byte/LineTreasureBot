# LINE 秘寶挖挖挖 助手（LineTreasureBot）

Android 自動玩三消遊戲的實驗專案，開發進度：

- [x] 步驟 1：專案骨架（截圖、懸浮按鈕、無障礙點擊）
- [x] 步驟 2：辨識原型（`tools/prototype/tile_prototype.py`，免樣板，同畫面內比對）
- [x] 步驟 2b：辨識移植成 Android OpenCV 版本（`vision/`）
- [x] 步驟 3：解題器 `solver/Solver.kt` + `SolverTest`（尚未在 Android Studio 編譯驗證）
- [x] 步驟 4：串接點擊，完整循環（`BotLoop.kt`，尚待實機驗證）

## 使用方式

1. 用 Android Studio 開啟專案（或下載 GitHub Actions 產出的 APK），安裝到手機（Android 8.0+）。
2. 開啟 app，依序完成三個按鈕：懸浮視窗權限 → 無障礙服務 → 截圖授權。
3. 進入遊戲，**在新的一關剛開始、底下格子是空的時**按懸浮的「開始」。
   懸浮列有「☰（拖曳）、開始、暫停、狀態」。狀態會顯示「執行·N」，N 是目前偵測到的可點亮牌數量；顯示「無牌」代表辨識沒抓到牌。
4. 除錯檔在 `Android/data/com.example.linebot/files/debug/`：`last.jpg`（紅框 = 場上亮牌，數字 = 圖案分組，綠框 = 讀到的格子牌）和 `log.txt`（每一輪的決策紀錄）。有問題時把這兩個檔案傳給開發者。很多手機打不開 `Android/data`，請直接用 App 主畫面的「分享除錯檔」按鈕送出。

## 已知限制

- 底下格子優先讀畫面；如果讀不到，才改用程式模擬（log 裡會寫 `slot not readable`）。
- 只看得到亮牌，被壓住的牌看不到，每步只做 7 步內的預測。
- 關卡結束後的結算畫面、廣告需要自己處理。

## 注意

遊戲條款通常禁止外掛／自動化，請自行評估帳號風險。
