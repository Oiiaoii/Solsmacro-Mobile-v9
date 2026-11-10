package com.solmacro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

private typealias WL = WindowManager.LayoutParams

private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

class MacroService : AccessibilityService() {

    companion object { var inst: MacroService? = null }

    private class Step(val x: Int, val y: Int, val hold: Long, val wait: Long)

    private val h = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var panel: LinearLayout? = null
    private var startBtn: TextView? = null
    private var running = false
    private var catches = 0
    private var tl: List<Step> = emptyList()
    private var idx = 0
    private var lastPkg = ""

    // detection state
    private var detectMode = false
    private var phase = 0
    private var miss = 0
    private var sellIdx = 0
    private var t0 = 0L
    private var lastShot = 0L
    private var lastMark = -1.0
    private var lastHeld = false
    private var votes = 0
    private val bg = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var dbgView: DebugView? = null
    private var pauseBtn: TextView? = null
    private var iconView: ImageView? = null
    private var paused = false
    private var lastSeen = 0L
    private var holdUntil = 0L
    private var failCasts = 0
    private var fpsT = 0L
    private var fpsN = 0
    private var dbgCount = 0

    override fun onServiceConnected() {
        inst = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        showPanel()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val p = e.packageName?.toString() ?: return
            if (p != packageName && p != "com.android.systemui") lastPkg = p
        }
    }

    override fun onInterrupt() { stop() }

    override fun onUnbind(intent: Intent?): Boolean {
        stop()
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null
        inst = null
        return super.onUnbind(intent)
    }

    private fun foreground(): String =
        if (lastPkg.isNotEmpty()) lastPkg else (rootInActiveWindow?.packageName?.toString() ?: "")

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    fun togglePause() {
        if (!running) return
        if (paused) {
            paused = false
            pauseBtn?.text = "PAUSE"
            Dbg.add("Resumed")
            if (phase == 6) phase = 0
            if (detectMode) dnext() else next()
        } else {
            paused = true
            h.removeCallbacksAndMessages(null)
            pauseBtn?.text = "RESUME"
            Dbg.state = "Paused"
            Dbg.add("Paused")
        }
    }

    private fun tapPoint(name: String) {
        val c = applicationContext
        tap(Cfg.get(c, name + "_x"), Cfg.get(c, name + "_y"), 60L)
    }

    private fun moveHold(dir: String, ms: Long) {
        val c = applicationContext
        val r = Cfg.get(c, "joyr")
        val d = when (dir) { "w" -> "up"; "s" -> "down"; "a" -> "left"; "d" -> "right"; else -> dir }
        val dx = when (d) { "left" -> -r; "right" -> r; else -> 0 }
        val dy = when (d) { "up" -> -r; "down" -> r; else -> 0 }
        val cx = Cfg.get(c, "joy_x").toFloat()
        val cy = Cfg.get(c, "joy_y").toFloat()
        val p1 = Path().apply { moveTo(cx, cy); lineTo(cx + dx, cy + dy) }
        val s1 = GestureDescription.StrokeDescription(p1, 0L, 120L, true)
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(s1).build(), object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                val p2 = Path().apply { moveTo(cx + dx, cy + dy) }
                val s2 = s1.continueStroke(p2, 0L, maxOf(ms, 1L), false)
                dispatchGesture(GestureDescription.Builder().addStroke(s2).build(), null, null)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Dbg.add("Move cancelled")
            }
        }, null)
        if (!ok) Dbg.add("Android refused the move")
    }

    private fun camSwipe(dir: String, amt: Int) {
        val c = applicationContext
        val cx = Cfg.get(c, "cam_x").toFloat()
        val cy = Cfg.get(c, "cam_y").toFloat()
        val dx = when (dir) { "left" -> -amt; "right" -> amt; else -> 0 }
        val dy = when (dir) { "up" -> -amt; "down" -> amt; else -> 0 }
        val p = Path().apply { moveTo(cx, cy); lineTo(cx + dx, cy + dy) }
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0L, 250L)).build(), null, null)
    }

    // script lines: move up 1.5 | camera left 200 | shiftlock | tap inv | wait 1
    private fun runScript(text: String, done: () -> Unit) {
        val lines = text.lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
        fun step(i: Int) {
            if (!running) return
            if (i >= lines.size) { done(); return }
            val p = lines[i].split(" ").filter { it.isNotEmpty() }
            var wait = 100L
            try {
                when (p[0].lowercase()) {
                    "wait" -> wait = (p[1].toDouble() * 1000).toLong()
                    "move" -> { val ms = (p[2].toDouble() * 1000).toLong(); moveHold(p[1].lowercase(), ms); wait = ms + 300 }
                    "camera" -> { camSwipe(p[1].lowercase(), p.getOrNull(2)?.toInt() ?: 250); wait = 450 }
                    "shiftlock" -> { tapPoint("shift"); wait = 350 }
                    "tap" -> { tapPoint(p[1].lowercase()); wait = 350 }
                    else -> Dbg.add("Unknown script line: " + lines[i])
                }
            } catch (e: Exception) {
                Dbg.add("Bad script line: " + lines[i])
            }
            h.postDelayed({ step(i + 1) }, wait)
        }
        step(0)
    }

    // ---------- timed macro loop ----------
    private fun build(): List<Step> {
        val c = applicationContext
        val l = ArrayList<Step>()
        l.add(Step(Cfg.get(c, "cast_x"), Cfg.get(c, "cast_y"), Cfg.get(c, "cast").toLong(), Cfg.get(c, "wait").toLong()))
        repeat(Cfg.get(c, "taps")) {
            l.add(Step(Cfg.get(c, "reel_x"), Cfg.get(c, "reel_y"), 40L, Cfg.get(c, "gap").toLong()))
        }
        val every = Cfg.get(c, "every")
        if (Cfg.get(c, "sell") == 1 && every > 0 && (catches + 1) % every == 0) {
            for (k in listOf("inv", "sellall", "confirm")) {
                l.add(Step(Cfg.get(c, k + "_x"), Cfg.get(c, k + "_y"), 50L, Cfg.get(c, "mdelay").toLong()))
            }
        }
        return l
    }

    fun toggle() { if (running) stop() else start() }

    private fun start() {
        if (foreground() != Cfg.ROBLOX) { toast("Open Roblox first"); return }
        detectMode = Cfg.get(applicationContext, "detect") == 1
        if (detectMode && Build.VERSION.SDK_INT < 30) { toast("Detection needs Android 11 or newer"); return }
        running = true
        idx = 0
        phase = 0
        miss = 0
        catches = 0
        Dbg.catches = 0
        Dbg.sells = 0
        Dbg.startMs = SystemClock.uptimeMillis()
        Dbg.state = "Starting"
        Dbg.add("Started, detection=$detectMode")
        Hook.send(applicationContext, "Macro started", "Screen detection: $detectMode", 0x38D39F)
        paused = false
        failCasts = 0
        label()
        runScript(Cfg.gets(applicationContext, "script_start")) {
            if (detectMode) {
                dnext()
            } else {
                tl = build()
                next()
            }
        }
    }

    fun stop() {
        val was = running
        running = false
        paused = false
        pauseBtn?.text = "PAUSE"
        h.removeCallbacksAndMessages(null)
        hideDebug()
        Dbg.state = "Idle"
        label()
        if (was) {
            Dbg.add("Stopped after $catches catches")
            Hook.send(applicationContext, "Macro stopped", "$catches catches, ${Dbg.sells} sells", 0xFF6B6B)
        }
    }

    private fun next() {
        if (!running) return
        if (foreground() != Cfg.ROBLOX) { stop(); toast("Roblox closed, macro stopped"); return }
        if (idx >= tl.size) { onCatch(); if (doneCheck()) return; tl = build(); idx = 0 }
        val s = tl[idx++]
        tap(s.x, s.y, s.hold)
        h.postDelayed({ next() }, s.hold + s.wait)
    }

    private fun tap(x: Int, y: Int, hold: Long) {
        val p = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0L, maxOf(hold, 1L)))
            .build()
        val ok = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Dbg.add("Tap cancelled at $x,$y")
            }
        }, null)
        if (!ok) Dbg.add("Android refused the tap at $x,$y")
    }

    private fun label() {
        startBtn?.text = if (running) "STOP ($catches)" else "START"
    }

    private fun onCatch() {
        catches++
        Dbg.catches = catches
        label()
        val every = Cfg.get(applicationContext, "whevery")
        if (every > 0 && catches % every == 0) {
            Hook.send(applicationContext, "Progress", "$catches catches, ${Dbg.sells} sells", 0x3EA6FF)
        }
    }

    private fun showDbg(r: DoubleArray?) {
        if (!::wm.isInitialized) return
        val c = applicationContext
        var v = dbgView
        if (v == null) {
            v = DebugView(this)
            wm.addView(v, WL(MATCH, MATCH, WL.TYPE_ACCESSIBILITY_OVERLAY,
                WL.FLAG_NOT_TOUCHABLE or WL.FLAG_NOT_FOCUSABLE or WL.FLAG_LAYOUT_IN_SCREEN or WL.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT))
            dbgView = v
        }
        val x1 = minOf(Cfg.get(c, "r1_x"), Cfg.get(c, "r2_x"))
        val x2 = maxOf(Cfg.get(c, "r1_x"), Cfg.get(c, "r2_x"))
        val y1 = minOf(Cfg.get(c, "r1_y"), Cfg.get(c, "r2_y"))
        val y2 = maxOf(Cfg.get(c, "r1_y"), Cfg.get(c, "r2_y"))
        v.rect.set(x1, y1, x2, y2)
        val need = maxOf(Cfg.get(c, "minpx"), 1)
        if (r == null) {
            v.color = Color.RED
            v.label = "No screenshot"
            v.zmax = -1.0
            v.mpos = -1.0
        } else {
            val zn = r[0].toInt()
            val mn = r[3].toInt()
            val pct = minOf(100, zn * 100 / need)
            v.color = if (zn >= need && mn >= 3) Color.GREEN else if (zn >= need / 2) Color.YELLOW else Color.RED
            v.label = "zone match $pct%   marker ${mn}px   ${Dbg.state}"
            v.vert = Cfg.get(c, "axis") == 1
            v.zmin = r[1]
            v.zmax = r[2]
            v.mpos = if (mn > 0) r[4] / mn else -1.0
        }
        v.invalidate()
    }

    private fun hideDebug() {
        if (!::wm.isInitialized) return
        dbgView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        dbgView = null
    }

    // ---------- screen detection loop ----------
    private fun dnext() {
        if (!running) return
        if (foreground() != Cfg.ROBLOX) { stop(); toast("Roblox closed, macro stopped"); return }
        val c = applicationContext
        Dbg.state = when (phase) { 0 -> "Casting"; 1 -> "Waiting for the bar"; 2 -> "Reeling"; 3 -> "Selling"; else -> "Walking" }
        when (phase) {
            0 -> {
                tap(Cfg.get(c, "cast_x"), Cfg.get(c, "cast_y"), Cfg.get(c, "cast").toLong())
                phase = 1
                t0 = SystemClock.uptimeMillis()
                h.postDelayed({ dnext() }, Cfg.get(c, "cast") + 600L)
            }
            1 -> track { r ->
                if (!running) return@track
                val n = r?.get(0)?.toInt() ?: 0
                if (n >= Cfg.get(c, "minpx")) {
                    phase = 2
                    failCasts = 0
                    lastMark = -1.0
                    lastHeld = false
                    votes = 0
                    t0 = SystemClock.uptimeMillis()
                    lastSeen = t0
                    Dbg.add("Bar found (zone $n px)")
                } else if (SystemClock.uptimeMillis() - t0 > Cfg.get(c, "maxwait") * 1000L) {
                    failCasts++
                    Dbg.add("Bar not found, cast again ($failCasts/3)")
                    if (failCasts >= 3) {
                        stop()
                        toast("Bar not found 3 times, stopped. Check COLOR and TEST.")
                        Hook.send(applicationContext, "Macro stopped", "The reeling bar was not found 3 times in a row.", 0xFF6B6B)
                        return@track
                    }
                    phase = 0
                }
                h.postDelayed({ dnext() }, 30L)
            }
            2 -> track { r ->
                if (!running) return@track
                var held = false
                if (r != null && r[0].toInt() >= Cfg.get(c, "minpx")) {
                    lastSeen = SystemClock.uptimeMillis()
                    if (r[3].toInt() >= 3) {
                        val zc = (r[1] + r[2]) / 2.0
                        val mc = r[4] / r[3]
                        var dir = Cfg.get(c, "holddir")
                        if (lastHeld && lastMark >= 0) {
                            val d = mc - lastMark
                            if (Math.abs(d) > 6) {
                                if ((d > 0) == (dir == 1)) votes++ else votes--
                                if (votes <= -3) {
                                    dir = 1 - dir
                                    Cfg.put(c, "holddir", dir)
                                    votes = 0
                                    toast("Hold direction flipped")
                                }
                            }
                        }
                        val db = Cfg.get(c, "deadband")
                        held = if (dir == 0) mc > zc + db else mc < zc - db
                        if (held && SystemClock.uptimeMillis() >= holdUntil - 40L) {
                            val hm = Cfg.get(c, "holdms").toLong()
                            tap(Cfg.get(c, "reel_x"), Cfg.get(c, "reel_y"), hm)
                            holdUntil = SystemClock.uptimeMillis() + hm
                        }
                        lastMark = mc
                    }
                }
                lastHeld = held
                dbgCount++
                if (dbgCount % 15 == 0) Dbg.add("reel zone=${r?.get(0)?.toInt()} marker=${r?.get(3)?.toInt()} held=$held")
                if (Cfg.get(c, "debug") == 1) showDbg(r)
                if (SystemClock.uptimeMillis() - lastSeen > 800L || SystemClock.uptimeMillis() - t0 > 60000L) {
                    onCatch()
                    if (doneCheck()) return@track
                    val every = Cfg.get(c, "every")
                    phase = if (Cfg.get(c, "sell") == 1 && every > 0 && catches % every == 0) 4 else 0
                    sellIdx = 0
                    h.postDelayed({ dnext() }, 800L)
                } else {
                    h.postDelayed({ dnext() }, 20L)
                }
            }
            4 -> {
                phase = 6
                runScript(Cfg.gets(c, "script_pre")) { phase = 3; sellIdx = 0; dnext() }
            }
            5 -> {
                phase = 6
                runScript(Cfg.gets(c, "script_post")) { phase = 0; dnext() }
            }
            6 -> { }
            else -> {
                val k = listOf("inv", "sellall", "confirm")[sellIdx]
                sellIdx++
                tap(Cfg.get(c, k + "_x"), Cfg.get(c, k + "_y"), 50L)
                if (sellIdx >= 3) {
                    phase = 5
                    Dbg.sells++
                    Dbg.add("Sold (#${Dbg.sells})")
                    Hook.send(applicationContext, "Sold items", "Sell #${Dbg.sells} after $catches catches", 0x38D39F)
                }
                h.postDelayed({ dnext() }, Cfg.get(c, "mdelay").toLong())
            }
        }
    }

    private fun shot(cb: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { cb(null); return }
        val wait = maxOf(0L, 380L - (SystemClock.uptimeMillis() - lastShot))
        h.postDelayed({
            lastShot = SystemClock.uptimeMillis()
            takeScreenshot(Display.DEFAULT_DISPLAY, bg, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                    val hb = r.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    hb.close()
                    cb(bmp)
                }
                override fun onFailure(errorCode: Int) { cb(null) }
            })
        }, wait)
    }

    private fun track(cb: (DoubleArray?) -> Unit) {
        val t = SystemClock.uptimeMillis()
        val after = { r: DoubleArray? ->
            val now = SystemClock.uptimeMillis()
            Dbg.latency = (now - t).toInt()
            fpsN++
            if (now - fpsT >= 1000L) { Dbg.fps = fpsN; fpsN = 0; fpsT = now }
            cb(r)
        }
        if (Cap.active) {
            val dm = android.util.DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            Cap.ensure(dm.widthPixels, dm.heightPixels, dm.densityDpi)
            val c = applicationContext
            val x1 = minOf(Cfg.get(c, "r1_x"), Cfg.get(c, "r2_x"))
            val x2 = maxOf(Cfg.get(c, "r1_x"), Cfg.get(c, "r2_x"))
            val y1 = minOf(Cfg.get(c, "r1_y"), Cfg.get(c, "r2_y"))
            val y2 = maxOf(Cfg.get(c, "r1_y"), Cfg.get(c, "r2_y"))
            bg.execute {
                val reg = Cap.region(x1, y1, x2 - x1, y2 - y1)
                val r = if (reg == null) null else scanPx(reg.px, reg.x, reg.y, reg.w, reg.h)
                h.post { after(r) }
            }
            return
        }
        shot { b ->
            val r = if (b == null) null else scan(b).also { b.recycle() }
            h.post { after(r) }
        }
    }

    private fun scanPx(px: IntArray, x1: Int, y1: Int, w: Int, hh: Int): DoubleArray {
        val c = applicationContext
        val out = doubleArrayOf(0.0, 1e9, -1.0, 0.0, 0.0)
        val zc = Cfg.get(c, "color")
        val mk = Cfg.get(c, "color2")
        val tol = Cfg.get(c, "tol")
        val vert = Cfg.get(c, "axis") == 1
        var i = 0
        while (i < px.size) {
            val p = px[i]
            val pos = (if (vert) y1 + i / w else x1 + i % w).toDouble()
            if (near(p, zc, tol)) {
                out[0] += 1.0
                if (pos < out[1]) out[1] = pos
                if (pos > out[2]) out[2] = pos
            } else if (near(p, mk, tol)) {
                out[3] += 1.0
                out[4] += pos
            }
            i += 2
        }
        return out
    }

    private fun near(p: Int, t: Int, tol: Int): Boolean =
        Math.abs(((p shr 16) and 255) - ((t shr 16) and 255)) <= tol &&
        Math.abs(((p shr 8) and 255) - ((t shr 8) and 255)) <= tol &&
        Math.abs((p and 255) - (t and 255)) <= tol

    // returns [zoneCount, zoneMin, zoneMax, markerCount, markerSum] along the chosen axis
    private fun scan(b: Bitmap): DoubleArray {
        val c = applicationContext
        val out = doubleArrayOf(0.0, 1e9, -1.0, 0.0, 0.0)
        val x1 = minOf(Cfg.get(c, "r1_x"), Cfg.get(c, "r2_x")).coerceIn(0, b.width - 1)
        val x2 = maxOf(Cfg.get(c, "r1_x"), Cfg.get(c, "r2_x")).coerceIn(0, b.width)
        val y1 = minOf(Cfg.get(c, "r1_y"), Cfg.get(c, "r2_y")).coerceIn(0, b.height - 1)
        val y2 = maxOf(Cfg.get(c, "r1_y"), Cfg.get(c, "r2_y")).coerceIn(0, b.height)
        val w = x2 - x1
        val hh = y2 - y1
        if (w <= 0 || hh <= 0) return out
        val px = IntArray(w * hh)
        b.getPixels(px, 0, w, x1, y1, w, hh)
        val zc = Cfg.get(c, "color")
        val mk = Cfg.get(c, "color2")
        val tol = Cfg.get(c, "tol")
        val vert = Cfg.get(c, "axis") == 1
        var i = 0
        while (i < px.size) {
            val p = px[i]
            val pos = (if (vert) y1 + i / w else x1 + i % w).toDouble()
            if (near(p, zc, tol)) {
                out[0] += 1.0
                if (pos < out[1]) out[1] = pos
                if (pos > out[2]) out[2] = pos
            } else if (near(p, mk, tol)) {
                out[3] += 1.0
                out[4] += pos
            }
            i += 2
        }
        return out
    }

    private fun shotMain(cb: (Bitmap?) -> Unit) = shot { b -> h.post { cb(b) } }

    private fun detect(cb: (Int) -> Unit) {
        shot { b ->
            val n = if (b == null) 0 else count(b).also { b.recycle() }
            h.post { cb(n) }
        }
    }

    private fun doneCheck(): Boolean {
        val m = Cfg.get(applicationContext, "maxcatch")
        if (m > 0 && catches >= m) { stop(); toast("Reached $m catches, stopped"); return true }
        return false
    }

    private fun count(b: Bitmap): Int {
        val c = applicationContext
        val x1 = minOf(Cfg.get(c, "r1_x"), Cfg.get(c, "r2_x")).coerceIn(0, b.width - 1)
        val x2 = maxOf(Cfg.get(c, "r1_x"), Cfg.get(c, "r2_x")).coerceIn(0, b.width)
        val y1 = minOf(Cfg.get(c, "r1_y"), Cfg.get(c, "r2_y")).coerceIn(0, b.height - 1)
        val y2 = maxOf(Cfg.get(c, "r1_y"), Cfg.get(c, "r2_y")).coerceIn(0, b.height)
        val w = x2 - x1
        val hh = y2 - y1
        if (w <= 0 || hh <= 0) return 0
        val px = IntArray(w * hh)
        b.getPixels(px, 0, w, x1, y1, w, hh)
        val tc = Cfg.get(c, "color")
        val tr = (tc shr 16) and 255
        val tg = (tc shr 8) and 255
        val tb = tc and 255
        val tol = Cfg.get(c, "tol")
        var n = 0
        var i = 0
        while (i < px.size) {
            val p = px[i]
            if (Math.abs(((p shr 16) and 255) - tr) <= tol &&
                Math.abs(((p shr 8) and 255) - tg) <= tol &&
                Math.abs((p and 255) - tb) <= tol) n++
            i += 3
        }
        return n
    }

    private fun test() {
        if (Build.VERSION.SDK_INT < 30) { toast("Detection needs Android 11 or newer"); return }
        track { r ->
            toast(if (r == null) "Could not capture the screen" else "Zone px: ${r[0].toInt()}, marker px: ${r[3].toInt()} (zone needs ${Cfg.get(applicationContext, "minpx")})")
            Dbg.add("TEST zone=${r?.get(0)?.toInt()} marker=${r?.get(3)?.toInt()}")
            showDbg(r)
            if (!running) h.postDelayed({ hideDebug() }, 4000L)
        }
    }

    // ---------- calibration on a frozen screenshot ----------
    private fun calibrate() {
        if (Build.VERSION.SDK_INT < 30) { toast("Detection needs Android 11 or newer"); return }
        toast("Start fishing now. The screen is captured in 8 seconds, so have the reeling bar showing.")
        h.postDelayed({ calibrateNow() }, 8000L)
    }

    private fun calibrateNow() {
        if (Build.VERSION.SDK_INT < 30) { toast("Detection needs Android 11 or newer"); return }
        shotMain { bmp ->
            if (bmp == null) { toast("Could not capture the screen"); return@shotMain }
            val c = applicationContext
            val box = FrameLayout(this)
            val img = ImageView(this).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.FIT_XY
            }
            box.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
            val prompts = listOf(
                "1/4: tap the TOP-LEFT corner of the reeling bar area",
                "2/4: tap the BOTTOM-RIGHT corner of the bar area",
                "3/4: tap the GREEN zone (its color is saved)",
                "4/4: tap the white MARKER (its color is saved)"
            )
            val t = pill(prompts[0], 0xDD000000.toInt())
            box.addView(t, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 60 })
            var step = 0
            box.setOnTouchListener { v, e ->
                if (e.action == MotionEvent.ACTION_UP) {
                    val x = (e.x / v.width * bmp.width).toInt().coerceIn(0, bmp.width - 1)
                    val y = (e.y / v.height * bmp.height).toInt().coerceIn(0, bmp.height - 1)
                    when (step) {
                        0 -> { Cfg.put(c, "r1_x", x); Cfg.put(c, "r1_y", y) }
                        1 -> { Cfg.put(c, "r2_x", x); Cfg.put(c, "r2_y", y) }
                        2 -> Cfg.put(c, "color", bmp.getPixel(x, y))
                        else -> Cfg.put(c, "color2", bmp.getPixel(x, y))
                    }
                    step++
                    if (step >= 4) {
                        wm.removeView(box)
                        toast("Saved. Tap TEST while the bar is showing.")
                    } else {
                        t.text = prompts[step]
                    }
                }
                true
            }
            wm.addView(box, WL(MATCH, MATCH, WL.TYPE_ACCESSIBILITY_OVERLAY,
                WL.FLAG_LAYOUT_IN_SCREEN or WL.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT))
        }
    }

    // ---------- floating panel ----------
    private fun pill(t: String, color: Int) = TextView(this).apply {
        text = t
        setTextColor(Color.WHITE)
        textSize = 13f
        setPadding(34, 22, 34, 22)
        background = GradientDrawable().apply { setColor(color); cornerRadius = 60f }
    }

    fun refreshIcon() { loadIcon() }

    private fun loadIcon() {
        val v = iconView ?: return
        val f = java.io.File(filesDir, "float_icon.png")
        if (Cfg.get(applicationContext, "icon_custom") == 1 && f.exists()) {
            val b = android.graphics.BitmapFactory.decodeFile(f.absolutePath)
            if (b != null) { v.setImageBitmap(b); return }
        }
        v.setImageResource(R.drawable.brry_icon)
    }

    private fun togglePanel(row: View) {
        val open = row.visibility != View.VISIBLE
        row.visibility = if (open) View.VISIBLE else View.GONE
        if (open) {
            row.alpha = 0f
            row.animate().alpha(1f).setDuration(180).start()
        }
    }

    private fun showPanel() {
        val d = resources.displayMetrics.density
        val size = (56 * d).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        val s = pill("START", 0xDD2E8BFF.toInt())
        val p = pill("SET", 0xDD6B4DFF.toInt())
        val cl = pill("COLOR", 0xDD1E9E75.toInt())
        val ts = pill("TEST", 0xDD8A6D00.toInt())
        val pa = pill("PAUSE", 0xDD555B73.toInt())
        row.addView(s)
        for (v in listOf(pa, p, cl, ts)) {
            row.addView(v, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = 12 })
        }
        val icon = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setShape(GradientDrawable.OVAL)
                setColor(0xFF000000.toInt())
                setStroke((2 * d).toInt(), 0xFFFFFFFF.toInt())
            }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            alpha = 0.92f
        }
        iconView = icon
        loadIcon()
        box.addView(icon, LinearLayout.LayoutParams(size, size))
        box.addView(row, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = (10 * d).toInt() })
        val lp = WL(WRAP, WRAP, WL.TYPE_ACCESSIBILITY_OVERLAY,
            WL.FLAG_NOT_FOCUSABLE or WL.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 120
        }
        drag(icon, box, lp) { togglePanel(row) }
        drag(s, box, lp) { toggle() }
        drag(pa, box, lp) { togglePause() }
        drag(p, box, lp) { if (!running) setPoints() else toast("Stop the macro first") }
        drag(cl, box, lp) { if (!running) calibrate() else toast("Stop the macro first") }
        drag(ts, box, lp) { test() }
        wm.addView(box, lp)
        panel = box
        startBtn = s
        pauseBtn = pa
    }

    private fun drag(v: View, row: View, lp: WL, click: () -> Unit) {
        var dx = 0f
        var dy = 0f
        var sx = 0
        var sy = 0
        var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = lp.x; sy = lp.y; moved = false; v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start() }
                MotionEvent.ACTION_MOVE -> {
                    val mx = (e.rawX - dx).toInt()
                    val my = (e.rawY - dy).toInt()
                    if (Math.abs(mx) > 12 || Math.abs(my) > 12) moved = true
                    if (moved) { lp.x = sx + mx; lp.y = sy + my; wm.updateViewLayout(row, lp) }
                }
                MotionEvent.ACTION_UP -> { v.animate().scaleX(1f).scaleY(1f).setDuration(120).start(); if (!moved) click() }
            }
            true
        }
    }

    // ---------- tap position picker ----------
    private fun setPoints() = pickAt(0)

    private fun pickAt(i: Int) {
        if (i >= Cfg.keys.size) { toast("Positions saved"); return }
        val k = Cfg.keys[i]
        val box = FrameLayout(this).apply { setBackgroundColor(0x99000000.toInt()) }
        val t = TextView(this).apply {
            text = "${i + 1}/${Cfg.keys.size}: tap the ${Cfg.label[k]}"
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
        }
        box.addView(t, FrameLayout.LayoutParams(MATCH, MATCH))
        box.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                Cfg.put(applicationContext, k + "_x", e.rawX.toInt())
                Cfg.put(applicationContext, k + "_y", e.rawY.toInt())
                wm.removeView(box)
                pickAt(i + 1)
            }
            true
        }
        wm.addView(box, WL(MATCH, MATCH, WL.TYPE_ACCESSIBILITY_OVERLAY,
            WL.FLAG_LAYOUT_IN_SCREEN or WL.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT))
    }
}
