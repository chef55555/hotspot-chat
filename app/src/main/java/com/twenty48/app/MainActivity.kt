package com.twenty48.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// ---------------------------------------------------------------------------
// Theme palette (light / dark). Tile colors are shared between themes.
// ---------------------------------------------------------------------------

object Palette {
    var dark = false
        private set
    var bg = 0
    var headerText = 0
    var board = 0
    var emptyCell = 0
    var scoreBox = 0
    var scoreLabel = 0
    var overlayLose = 0
    var overlayLoseText = 0
    val button = 0xFF8F7A66.toInt()
    val overlayWin = 0xB3EDC22E.toInt()
    val textLight = 0xFFF9F6F2.toInt()

    fun apply(isDark: Boolean) {
        dark = isDark
        if (isDark) {
            bg = 0xFF1C1A17.toInt()
            headerText = 0xFFEEE4DA.toInt()
            board = 0xFF3A342C.toInt()
            emptyCell = 0xFF4A4339.toInt()
            scoreBox = 0xFF3A342C.toInt()
            scoreLabel = 0xFFB8AC9F.toInt()
            overlayLose = 0xC42B2722.toInt()
            overlayLoseText = 0xFFEEE4DA.toInt()
        } else {
            bg = 0xFFFAF8EF.toInt()
            headerText = 0xFF776E65.toInt()
            board = 0xFFBBADA0.toInt()
            emptyCell = 0xFFCDC1B4.toInt()
            scoreBox = 0xFFBBADA0.toInt()
            scoreLabel = 0xFFEEE4DA.toInt()
            overlayLose = 0xBBEEE4DA.toInt()
            overlayLoseText = 0xFF776E65.toInt()
        }
    }
}

// ---------------------------------------------------------------------------
// Game logic (no Android dependencies)
// ---------------------------------------------------------------------------

/** One tile's journey during a move, for animation. */
class Slide(val value: Int, val fromR: Int, val fromC: Int, val toR: Int, val toC: Int)

class MoveResult(
    val slides: List<Slide>,
    val mergedCells: List<Int>,
    val spawnCell: Int,
    val won2048: Boolean,
    val gained: Int,
)

class Game {
    var grid = IntArray(16)
    var score = 0
    var best = 0
    var won = false // 2048 reached this game (win banner shown at most once)

    private var prevGrid: IntArray? = null
    private var prevScore = 0
    val canUndo: Boolean get() = prevGrid != null

    fun newGame() {
        grid = IntArray(16)
        score = 0
        won = false
        prevGrid = null
        spawn()
        spawn()
    }

    private fun spawn(): Int {
        val empty = (0 until 16).filter { grid[it] == 0 }
        if (empty.isEmpty()) return -1
        val cell = empty[Random.nextInt(empty.size)]
        grid[cell] = if (Random.nextInt(10) == 0) 4 else 2
        return cell
    }

    /** Index of the i-th cell of the given line, walking in the move direction. */
    private fun posAt(dir: Int, line: Int, i: Int): Int = when (dir) {
        DIR_LEFT -> line * 4 + i
        DIR_UP -> i * 4 + line
        DIR_RIGHT -> line * 4 + (3 - i)
        else -> (3 - i) * 4 + line // DIR_DOWN
    }

    /** Apply a move. Returns null if nothing shifted. */
    fun move(dir: Int): MoveResult? {
        val slides = ArrayList<Slide>()
        val merged = ArrayList<Int>()
        val next = IntArray(16)
        var moved = false
        var gained = 0
        var wonNow = false

        for (line in 0 until 4) {
            val occupied = ArrayList<Int>()
            for (i in 0 until 4) {
                val p = posAt(dir, line, i)
                if (grid[p] != 0) occupied.add(p)
            }
            var target = 0
            var j = 0
            while (j < occupied.size) {
                val tp = posAt(dir, line, target)
                val p1 = occupied[j]
                if (j + 1 < occupied.size && grid[p1] == grid[occupied[j + 1]]) {
                    val p2 = occupied[j + 1]
                    val v = grid[p1] * 2
                    next[tp] = v
                    gained += v
                    slides.add(Slide(grid[p1], p1 / 4, p1 % 4, tp / 4, tp % 4))
                    slides.add(Slide(grid[p2], p2 / 4, p2 % 4, tp / 4, tp % 4))
                    merged.add(tp)
                    if (v == 2048 && !won) wonNow = true
                    moved = true
                    j += 2
                } else {
                    next[tp] = grid[p1]
                    slides.add(Slide(grid[p1], p1 / 4, p1 % 4, tp / 4, tp % 4))
                    if (tp != p1) moved = true
                    j += 1
                }
                target++
            }
        }

        if (!moved) return null
        prevGrid = grid.copyOf()
        prevScore = score
        grid = next
        score += gained
        if (score > best) best = score
        if (wonNow) won = true
        val spawnCell = spawn()
        return MoveResult(slides, merged, spawnCell, wonNow, gained)
    }

    fun undo(): Boolean {
        val pg = prevGrid ?: return false
        grid = pg
        score = prevScore
        prevGrid = null
        return true
    }

    fun isGameOver(): Boolean {
        for (i in 0 until 16) if (grid[i] == 0) return false
        for (r in 0 until 4) for (c in 0 until 4) {
            val v = grid[r * 4 + c]
            if (c < 3 && grid[r * 4 + c + 1] == v) return false
            if (r < 3 && grid[(r + 1) * 4 + c] == v) return false
        }
        return true
    }

    companion object {
        const val DIR_LEFT = 0
        const val DIR_UP = 1
        const val DIR_RIGHT = 2
        const val DIR_DOWN = 3
    }
}

// ---------------------------------------------------------------------------
// Board view: rendering, animation, swipe input
// ---------------------------------------------------------------------------

@SuppressLint("ViewConstructor")
class GameView(
    context: Context,
    private val game: Game,
    private val onStateChange: (Int) -> Unit,
) : View(context) {

    private val boardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val rect = RectF()

    private var pad = 0f
    private var cell = 0f
    private var corner = 0f

    private var anim: MoveResult? = null
    private var animStart = 0L
    private val slideMs = 110f
    private val popMs = 90f

    private var overlay = OVERLAY_NONE
    private var pendingWin = false

    private var downX = 0f
    private var downY = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = min(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        pad = w * 0.028f
        cell = (w - pad * 5f) / 4f
        corner = cell * 0.08f
    }

    private fun cellX(c: Int) = pad + c * (cell + pad)
    private fun cellY(r: Int) = pad + r * (cell + pad)

    override fun onDraw(canvas: Canvas) {
        boardPaint.color = Palette.board
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, corner * 1.5f, corner * 1.5f, boardPaint)

        cellPaint.color = Palette.emptyCell
        for (r in 0 until 4) for (c in 0 until 4) {
            rect.set(cellX(c), cellY(r), cellX(c) + cell, cellY(r) + cell)
            canvas.drawRoundRect(rect, corner, corner, cellPaint)
        }

        val a = anim
        if (a != null) {
            val t = (SystemClock.uptimeMillis() - animStart).toFloat()
            if (t < slideMs) {
                // Phase 1: tiles glide; merge results and the spawn stay hidden.
                val p = decelerate(t / slideMs)
                for (s in a.slides) {
                    val x = cellX(s.fromC) + (cellX(s.toC) - cellX(s.fromC)) * p
                    val y = cellY(s.fromR) + (cellY(s.toR) - cellY(s.fromR)) * p
                    drawTile(canvas, x, y, s.value, 1f)
                }
                postInvalidateOnAnimation()
                return
            }
            if (t < slideMs + popMs) {
                // Phase 2: merged tiles pop, the new tile scales in.
                val p = (t - slideMs) / popMs
                val popScale = 1f + 0.2f * sin(PI * p).toFloat()
                for (i in 0 until 16) {
                    val v = game.grid[i]
                    if (v == 0) continue
                    val scale = when {
                        i == a.spawnCell -> p
                        a.mergedCells.contains(i) -> popScale
                        else -> 1f
                    }
                    drawTile(canvas, cellX(i % 4), cellY(i / 4), v, scale)
                }
                postInvalidateOnAnimation()
                return
            }
            anim = null
            if (pendingWin) {
                pendingWin = false
                overlay = OVERLAY_WIN
            } else if (game.isGameOver()) {
                overlay = OVERLAY_LOSE
            }
        }

        for (i in 0 until 16) {
            val v = game.grid[i]
            if (v != 0) drawTile(canvas, cellX(i % 4), cellY(i / 4), v, 1f)
        }

        if (overlay != OVERLAY_NONE) drawOverlay(canvas)
    }

    private fun drawTile(canvas: Canvas, x: Float, y: Float, v: Int, scale: Float) {
        if (scale <= 0f) return
        val cx = x + cell / 2f
        val cy = y + cell / 2f
        val half = cell / 2f * scale
        rect.set(cx - half, cy - half, cx + half, cy + half)
        tilePaint.color = tileColor(v)
        canvas.drawRoundRect(rect, corner * scale, corner * scale, tilePaint)
        textPaint.color = if (v <= 4) COLOR_TEXT_DARK else COLOR_TEXT_LIGHT
        val sizeFactor = when {
            v < 100 -> 0.50f
            v < 1000 -> 0.42f
            v < 10000 -> 0.33f
            else -> 0.27f
        }
        textPaint.textSize = cell * sizeFactor * scale
        val ty = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(v.toString(), cx, ty, textPaint)
    }

    private fun drawOverlay(canvas: Canvas) {
        overlayPaint.color = if (overlay == OVERLAY_WIN) Palette.overlayWin else Palette.overlayLose
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, corner * 1.5f, corner * 1.5f, overlayPaint)

        textPaint.color = if (overlay == OVERLAY_WIN) Palette.textLight else Palette.overlayLoseText
        textPaint.textSize = width * 0.12f
        val cx = width / 2f
        canvas.drawText(if (overlay == OVERLAY_WIN) "You win!" else "Game over!", cx, height * 0.46f, textPaint)
        textPaint.textSize = width * 0.045f
        canvas.drawText(
            if (overlay == OVERLAY_WIN) "Tap to keep going" else "Tap to try again",
            cx, height * 0.58f, textPaint,
        )
    }

    private fun decelerate(p: Float) = 1f - (1f - p) * (1f - p)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                val threshold = cell * 0.35f
                if (abs(dx) < threshold && abs(dy) < threshold) {
                    // Tap: dismiss overlays.
                    if (overlay == OVERLAY_WIN) {
                        overlay = OVERLAY_NONE
                        invalidate()
                    } else if (overlay == OVERLAY_LOSE) {
                        newGame()
                    }
                    performClick()
                    return true
                }
                val dir = if (abs(dx) > abs(dy)) {
                    if (dx > 0) Game.DIR_RIGHT else Game.DIR_LEFT
                } else {
                    if (dy > 0) Game.DIR_DOWN else Game.DIR_UP
                }
                doMove(dir)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun doMove(dir: Int) {
        if (overlay != OVERLAY_NONE) return
        anim = null // a still-running animation's grid is already final; skip to it
        val res = game.move(dir) ?: return
        if (res.mergedCells.isNotEmpty()) {
            performHapticFeedback(
                if (res.won2048) HapticFeedbackConstants.LONG_PRESS
                else HapticFeedbackConstants.KEYBOARD_TAP
            )
        }
        if (res.won2048) pendingWin = true
        anim = res
        animStart = SystemClock.uptimeMillis()
        onStateChange(res.gained)
        invalidate()
    }

    fun newGame() {
        game.newGame()
        anim = null
        overlay = OVERLAY_NONE
        pendingWin = false
        onStateChange(0)
        invalidate()
    }

    fun undo() {
        if (!game.undo()) return
        anim = null
        overlay = OVERLAY_NONE
        pendingWin = false
        onStateChange(0)
        invalidate()
    }

    private fun tileColor(v: Int): Int = when (v) {
        2 -> 0xFFEEE4DA.toInt()
        4 -> 0xFFEDE0C8.toInt()
        8 -> 0xFFF2B179.toInt()
        16 -> 0xFFF59563.toInt()
        32 -> 0xFFF67C5F.toInt()
        64 -> 0xFFF65E3B.toInt()
        128 -> 0xFFEDCF72.toInt()
        256 -> 0xFFEDCC61.toInt()
        512 -> 0xFFEDC850.toInt()
        1024 -> 0xFFEDC53F.toInt()
        2048 -> 0xFFEDC22E.toInt()
        else -> 0xFF3C3A32.toInt()
    }

    companion object {
        private const val OVERLAY_NONE = 0
        private const val OVERLAY_LOSE = 1
        private const val OVERLAY_WIN = 2

        private val COLOR_TEXT_DARK = 0xFF776E65.toInt()
        private val COLOR_TEXT_LIGHT = 0xFFF9F6F2.toInt()
    }
}

// ---------------------------------------------------------------------------
// Activity: programmatic UI (header, score boxes, buttons, board), theme
// toggle, floating score gains, in-app auto-update from GitHub Releases
// ---------------------------------------------------------------------------

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var game: Game
    private lateinit var gameView: GameView
    private lateinit var rootFrame: FrameLayout
    private lateinit var rootColumn: LinearLayout
    private lateinit var scoreValue: TextView
    private lateinit var bestValue: TextView
    private lateinit var undoButton: TextView
    private var updateBanner: TextView? = null

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("twenty48", MODE_PRIVATE)
        game = Game()
        restoreState()

        // Theme: explicit choice wins; otherwise follow the system setting.
        val dark = if (prefs.contains("dark")) {
            prefs.getBoolean("dark", false)
        } else {
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
        Palette.apply(dark)

        window.statusBarColor = Palette.bg
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            if (Palette.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        rootColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.bg)
            val p = dp(16f).toInt()
            setPadding(p, p, p, p)
        }

        // -- header: title + score boxes --
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "2048"
            setTextColor(Palette.headerText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
            setTypeface(typeface, Typeface.BOLD)
        }
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val scoreBox = makeScoreBox("SCORE")
        scoreValue = scoreBox.second
        header.addView(scoreBox.first)

        val bestBox = makeScoreBox("BEST")
        bestValue = bestBox.second
        header.addView(bestBox.first, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(8f).toInt() })

        rootColumn.addView(header)

        // -- subtitle + buttons --
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val subtitle = TextView(this).apply {
            text = "Swipe to join the numbers\nand reach 2048!"
            setTextColor(Palette.headerText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        controls.addView(subtitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val themeButton = makeButton(if (Palette.dark) "☀" else "☾") {
            prefs.edit().putBoolean("dark", !Palette.dark).apply()
            recreate()
        }
        controls.addView(themeButton)

        undoButton = makeButton("UNDO") { gameView.undo() }
        controls.addView(undoButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(8f).toInt() })

        val newButton = makeButton("NEW") { confirmNewGame() }
        controls.addView(newButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(8f).toInt() })

        rootColumn.addView(controls, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12f).toInt() })

        // -- board (kept square by GameView.onMeasure, centered in leftover space) --
        gameView = GameView(this, game) { gained -> onGameStateChanged(gained) }
        val boardFrame = FrameLayout(this)
        boardFrame.addView(gameView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        rootColumn.addView(boardFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = dp(12f).toInt() })

        rootFrame = FrameLayout(this)
        rootFrame.addView(rootColumn, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        setContentView(rootFrame)
        onGameStateChanged(0)
        checkForUpdate()
    }

    private fun makeScoreBox(label: String): Pair<LinearLayout, TextView> {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Palette.scoreBox)
                cornerRadius = dp(6f)
            }
            val ph = dp(14f).toInt()
            val pv = dp(6f).toInt()
            setPadding(ph, pv, ph, pv)
            minimumWidth = dp(72f).toInt()
        }
        val labelView = TextView(this).apply {
            text = label
            setTextColor(Palette.scoreLabel)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val valueView = TextView(this).apply {
            text = "0"
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        box.addView(labelView)
        box.addView(valueView)
        return Pair(box, valueView)
    }

    private fun makeButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(Palette.textLight)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Palette.button)
                cornerRadius = dp(6f)
            }
            val ph = dp(16f).toInt()
            val pv = dp(10f).toInt()
            setPadding(ph, pv, ph, pv)
            setOnClickListener { onClick() }
        }

    private fun themedDialog(): AlertDialog.Builder {
        val style = if (Palette.dark) {
            androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert
        } else {
            androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert
        }
        return AlertDialog.Builder(this, style)
    }

    private fun confirmNewGame() {
        themedDialog()
            .setMessage("Start a new game?")
            .setPositiveButton("New game") { _, _ -> gameView.newGame() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onGameStateChanged(gained: Int) {
        scoreValue.text = game.score.toString()
        bestValue.text = game.best.toString()
        undoButton.alpha = if (game.canUndo) 1f else 0.4f
        saveState()
        if (gained > 0) showScoreGain(gained)
    }

    /** Floating "+N" that drifts up from the score box and fades out. */
    private fun showScoreGain(gained: Int) {
        val scoreLoc = IntArray(2)
        scoreValue.getLocationInWindow(scoreLoc)
        val rootLoc = IntArray(2)
        rootFrame.getLocationInWindow(rootLoc)
        val float = TextView(this).apply {
            text = "+$gained"
            setTextColor(Palette.headerText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, Typeface.BOLD)
            alpha = 0.95f
        }
        rootFrame.addView(float, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = scoreLoc[0] - rootLoc[0]
            topMargin = scoreLoc[1] - rootLoc[1]
        })
        float.animate()
            .translationY(-dp(42f))
            .alpha(0f)
            .setDuration(700)
            .withEndAction { rootFrame.removeView(float) }
            .start()
    }

    private fun restoreState() {
        game.best = prefs.getInt("best", 0)
        val saved = prefs.getString("grid", null)
        if (saved != null) {
            val parts = saved.split(",")
            if (parts.size == 16) {
                val g = IntArray(16)
                var ok = true
                for (i in 0 until 16) {
                    val v = parts[i].toIntOrNull()
                    if (v == null) ok = false else g[i] = v
                }
                if (ok && g.any { it != 0 }) {
                    game.grid = g
                    game.score = prefs.getInt("score", 0)
                    game.won = prefs.getBoolean("won", false)
                    return
                }
            }
        }
        game.newGame()
    }

    private fun saveState() {
        prefs.edit()
            .putString("grid", game.grid.joinToString(","))
            .putInt("score", game.score)
            .putInt("best", game.best)
            .putBoolean("won", game.won)
            .apply()
    }

    override fun onPause() {
        super.onPause()
        saveState()
    }

    // -----------------------------------------------------------------------
    // Auto-update: every CI build publishes version.json + the APK to the
    // repo's latest GitHub Release. On launch, compare versionCode and offer
    // a one-tap download-and-install. Silent on any failure (e.g. offline).
    // -----------------------------------------------------------------------

    private fun checkForUpdate() {
        if (updateChecked) return
        updateChecked = true
        Thread {
            try {
                val conn = URL(VERSION_JSON_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val json = JSONObject(text)
                val remoteCode = json.getLong("versionCode")
                val remoteName = json.getString("versionName")
                val apkUrl = json.getString("apkUrl")
                val myCode = PackageInfoCompat.getLongVersionCode(
                    packageManager.getPackageInfo(packageName, 0)
                )
                if (remoteCode > myCode) {
                    runOnUiThread { showUpdateBanner(remoteName, apkUrl) }
                }
            } catch (_: Exception) {
                // No network / no release yet — stay quiet.
            }
        }.start()
    }

    private fun showUpdateBanner(versionName: String, apkUrl: String) {
        if (updateBanner != null || isFinishing) return
        val banner = TextView(this).apply {
            text = "Update $versionName available — tap to install"
            setTextColor(Palette.textLight)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Palette.button)
                cornerRadius = dp(6f)
            }
            val p = dp(12f).toInt()
            setPadding(p, p, p, p)
        }
        banner.setOnClickListener { downloadAndInstall(apkUrl, banner) }
        updateBanner = banner
        rootColumn.addView(banner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12f).toInt() })
    }

    private fun downloadAndInstall(apkUrl: String, banner: TextView) {
        banner.text = "Downloading update…"
        banner.isEnabled = false
        Thread {
            try {
                val dir = File(cacheDir, "updates")
                dir.mkdirs()
                val file = File(dir, "update.apk")
                val conn = URL(apkUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 60000
                conn.inputStream.use { input ->
                    FileOutputStream(file).use { output -> input.copyTo(output) }
                }
                conn.disconnect()
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runOnUiThread {
                    banner.text = "Installing…"
                    startActivity(intent)
                }
            } catch (_: Exception) {
                runOnUiThread {
                    banner.text = "Update failed — tap to retry"
                    banner.isEnabled = true
                }
            }
        }.start()
    }

    companion object {
        private const val VERSION_JSON_URL =
            "https://github.com/chef55555/hotspot-chat/releases/latest/download/version.json"
        private var updateChecked = false
    }
}
