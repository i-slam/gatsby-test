package com.example.ramcleaner

import android.content.Context
import android.graphics.*
import android.view.View

/** RAM % history line chart with a dashed threshold line. Data in, pixels out. */
class SparkView(ctx: Context) : View(ctx) {
    private var data = FloatArray(0)
    private var threshold = -1f
    private var capacity = 2
    private val dp = resources.displayMetrics.density

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * dp; color = 0xFF4FC3F7.toInt()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x334FC3F7 }
    private val thPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp; color = 0xFFFF7043.toInt()
        pathEffect = DashPathEffect(floatArrayOf(6 * dp, 4 * dp), 0f)
    }
    private val grid = Paint().apply { color = 0x22FFFFFF; strokeWidth = dp }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAAFFFFFF.toInt(); textSize = 10 * dp }

    fun set(values: List<Int>, thresholdPct: Int, capacity: Int) {
        data = FloatArray(values.size) { values[it].toFloat() }
        threshold = thresholdPct.toFloat()
        this.capacity = capacity.coerceAtLeast(2)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        fun y(v: Float) = h - v / 100f * h

        for (g in intArrayOf(25, 50, 75)) c.drawLine(0f, y(g.toFloat()), w, y(g.toFloat()), grid)
        if (threshold >= 0) {
            c.drawLine(0f, y(threshold), w, y(threshold), thPaint)
            c.drawText("${threshold.toInt()}%", 4 * dp, y(threshold) - 3 * dp, text)
        }
        if (data.size < 2) return

        val step = w / (capacity - 1)
        val x0 = w - (data.size - 1) * step // newest sample on the right edge
        val path = Path()
        data.forEachIndexed { i, v ->
            val x = x0 + i * step
            if (i == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v))
        }
        val area = Path(path).apply { lineTo(w, h); lineTo(x0, h); close() }
        c.drawPath(area, fill)
        c.drawPath(path, line)
        c.drawText("${data.last().toInt()}%", w - 30 * dp, y(data.last()) - 4 * dp, text)
    }
}
