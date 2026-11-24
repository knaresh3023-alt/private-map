package com.naveen.privatemap

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect

class DeliveryService : AccessibilityService() {

    private class Line(val t: String, val top: Int, val left: Int)

    private val main = Handler(Looper.getMainLooper())
    private var lastScan = 0L
    private var scanPending = false
    private var lastKey: String? = null
    private var active = false
    private var missStart = 0L

    private val pollRun = Runnable { scan() }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null) return
        if (e.packageName?.toString() == packageName) return
        schedule()
    }

    override fun onInterrupt() {}

    private fun schedule() {
        if (scanPending) return
        scanPending = true
        val wait = maxOf(0L, 120L - (SystemClock.uptimeMillis() - lastScan))
        main.postDelayed({
            scanPending = false
            scan()
        }, wait)
    }

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<Line>, budget: IntArray) {
        if (n == null || budget[0] <= 0) return
        budget[0]--
        val t = (n.text ?: n.contentDescription)?.toString()?.trim()
        if (!t.isNullOrEmpty()) {
            val r = Rect()
            n.getBoundsInScreen(r)
            out.add(Line(t, r.top, r.left))
        }
        for (i in 0 until n.childCount) collect(n.getChild(i), out, budget)
    }

    private fun parseRoot(r: AccessibilityNodeInfo): Drop? {
        val pkg = r.packageName?.toString()
        if (pkg == packageName || pkg == "com.android.systemui") return null
        val lines = ArrayList<Line>()
        collect(r, lines, intArrayOf(500))
        if (lines.isEmpty()) return null
        val texts = lines.sortedWith(compareBy({ it.top }, { it.left })).map { it.t }
        return Parser.parse(texts)
    }

    private fun scan() {
        main.removeCallbacks(pollRun)
        lastScan = SystemClock.uptimeMillis()
        if (Bus.resetFlag) {
            Bus.resetFlag = false
            lastKey = null
            active = false
            missStart = 0L
        }
        var drop: Drop? = null
        try {
            val ws = windows
            if (ws != null) {
                for (w in ws) {
                    val r = w.root ?: continue
                    drop = parseRoot(r)
                    if (drop != null) break
                }
            }
            if (drop == null) {
                val r = rootInActiveWindow
                if (r != null) drop = parseRoot(r)
            }
        } catch (e: Exception) {
        }

        if (drop != null) {
            missStart = 0L
            if (drop.key != lastKey) {
                lastKey = drop.key
                active = true
                Bus.listener?.onDrop(drop)
            }
            main.postDelayed(pollRun, 400)
        } else if (active) {
            val now = SystemClock.uptimeMillis()
            if (missStart == 0L) missStart = now
            if (now - missStart >= 500) {
                active = false
                lastKey = null
                missStart = 0L
                Bus.listener?.onPopupGone()
            } else {
                main.postDelayed(pollRun, 200)
            }
        }
    }
}
