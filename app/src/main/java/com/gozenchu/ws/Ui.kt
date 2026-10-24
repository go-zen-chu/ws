package com.gozenchu.ws

import android.content.Context
import kotlin.math.roundToInt

object Palette {
    val BG = 0xFF1E1E1E.toInt()
    val PANEL = 0xFF252526.toInt()
    val PANEL_DARK = 0xFF181818.toInt()
    val INPUT = 0xFF3C3C3C.toInt()
    val SELECTED = 0xFF37373D.toInt()
    val ACCENT = 0xFF0E639C.toInt()
    val TEXT = 0xFFCCCCCC.toInt()
    val MUTED = 0xFF8B8B8B.toInt()
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

const val SHORTCUTS = """Ctrl+T          新しいタブ
Ctrl+W          タブを閉じる
Ctrl+Shift+T    閉じたタブを開き直す
Ctrl+Tab        次のタブ (Shift で前へ)
Ctrl+1〜9       タブへ移動 (9 は最後)
Ctrl+L          アドレスバー
Ctrl+R / F5     再読み込み
Alt+← / Alt+→   戻る / 進む
Ctrl+Shift+B    サイドバーの表示切替
Ctrl+Shift+S    セッションを保存
Ctrl+Alt+T      Termux を開く"""
