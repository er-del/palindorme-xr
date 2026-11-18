package com.example.xr.windows

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Built-in spatial QWERTY keyboard view for Browser URL entry and search inside headset mode,
 * since the Android system IME cannot render inside an off-screen 3D window.
 */
class SpatialKeyboardView(
    context: Context,
    private val onTextChange: (String) -> Unit,
    private val onSubmit: (String) -> Unit,
    private val onVoiceDictate: () -> Unit
) : LinearLayout(context) {

    private var buffer = StringBuilder()
    private var shift = false
    private val previewView: TextView
    private val keyButtons = mutableListOf<Pair<String, Button>>()
    private var hoveredSwipeKey: String? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#0D1526"))
        setPadding(24, 20, 24, 20)

        previewView = TextView(context).apply {
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#18243D"))
            setPadding(20, 14, 20, 14)
            text = "https://"
        }
        addView(previewView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 14
        })

        val rows = listOf(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", ".", "/"),
            listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p", "-", "_"),
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l", ":", "⌫"),
            listOf("⇧", "z", "x", "c", "v", "b", "n", "m", "SPACE", "🎤", "GO")
        )

        rows.forEach { keys ->
            val rowLayout = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
            }
            keys.forEach { key ->
                val weight = when (key) {
                    "SPACE" -> 2.4f
                    "GO" -> 1.5f
                    else -> 1.0f
                }
                val btn = Button(context).apply {
                    text = key
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    setTextColor(
                        if (key == "GO") Color.parseColor("#060913") else Color.parseColor("#F8FAFC")
                    )
                    setBackgroundColor(defaultBgColorForKey(key))
                    setOnClickListener { handleKey(key) }
                }
                keyButtons += key to btn
                rowLayout.addView(
                    btn,
                    LayoutParams(0, 88, weight).apply {
                        setMargins(5, 5, 5, 5)
                    }
                )
            }
            addView(rowLayout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    override fun onInterceptTouchEvent(ev: android.view.MotionEvent?): Boolean {
        if (ev?.actionMasked == android.view.MotionEvent.ACTION_MOVE) {
            highlightKeyAt(ev.x, ev.y)
        } else if (ev?.actionMasked == android.view.MotionEvent.ACTION_UP && hoveredSwipeKey != null) {
            val k = hoveredSwipeKey
            hoveredSwipeKey = null
            resetKeyColors()
            if (k != null && k.length == 1 && k != "⇧" && k != "⌫") {
                handleKey(k)
                return true
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    private fun highlightKeyAt(rx: Float, ry: Float) {
        val loc = IntArray(2)
        val rootLoc = IntArray(2)
        getLocationOnScreen(rootLoc)
        var hitKey: String? = null
        for ((key, btn) in keyButtons) {
            btn.getLocationOnScreen(loc)
            val lx = loc[0] - rootLoc[0]
            val ly = loc[1] - rootLoc[1]
            if (rx >= lx && rx <= lx + btn.width && ry >= ly && ry <= ly + btn.height) {
                hitKey = key
                btn.setBackgroundColor(Color.parseColor("#2563EB"))
            } else {
                btn.setBackgroundColor(defaultBgColorForKey(key))
            }
        }
        hoveredSwipeKey = hitKey
    }

    private fun resetKeyColors() {
        for ((key, btn) in keyButtons) {
            btn.setBackgroundColor(defaultBgColorForKey(key))
        }
    }

    private fun defaultBgColorForKey(key: String): Int = when (key) {
        "GO" -> Color.parseColor("#38BDF8")
        "🎤" -> Color.parseColor("#818CF8")
        "⌫" -> Color.parseColor("#3B1D2A")
        "⇧" -> if (shift) Color.parseColor("#0284C7") else Color.parseColor("#18243D")
        else -> Color.parseColor("#18243D")
    }

    fun setInitialText(initial: String) {
        buffer.clear()
        buffer.append(initial)
        refreshPreview()
    }

    private fun handleKey(key: String) {
        when (key) {
            "⌫" -> if (buffer.isNotEmpty()) buffer.deleteCharAt(buffer.length - 1)
            "⇧" -> shift = !shift
            "SPACE" -> buffer.append(' ')
            "🎤" -> {
                onVoiceDictate()
                return
            }
            "GO" -> {
                onSubmit(buffer.toString())
                return
            }
            else -> {
                val ch = if (shift) key.uppercase() else key
                buffer.append(ch)
                if (shift) shift = false
            }
        }
        refreshPreview()
        onTextChange(buffer.toString())
    }

    private fun refreshPreview() {
        previewView.text = if (buffer.isEmpty()) "Type URL or search..." else buffer.toString()
    }
}
