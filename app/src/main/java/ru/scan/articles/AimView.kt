package ru.scan.articles

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Рамка-прицел по центру экрана: кадр анализируется целиком, но числа ближе к центру получают приоритет. */
class AimView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private val shade = Paint().apply { color = 0x66000000 }
    private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4 * dp
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val rw = w * 0.86f
        val rh = h * 0.22f
        rect.set((w - rw) / 2, (h - rh) / 2, (w + rw) / 2, (h + rh) / 2)

        canvas.drawRect(0f, 0f, w, rect.top, shade)
        canvas.drawRect(0f, rect.bottom, w, h, shade)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, shade)
        canvas.drawRect(rect.right, rect.top, w, rect.bottom, shade)

        val c = 28 * dp
        path.reset()
        path.moveTo(rect.left, rect.top + c); path.lineTo(rect.left, rect.top); path.lineTo(rect.left + c, rect.top)
        path.moveTo(rect.right - c, rect.top); path.lineTo(rect.right, rect.top); path.lineTo(rect.right, rect.top + c)
        path.moveTo(rect.right, rect.bottom - c); path.lineTo(rect.right, rect.bottom); path.lineTo(rect.right - c, rect.bottom)
        path.moveTo(rect.left + c, rect.bottom); path.lineTo(rect.left, rect.bottom); path.lineTo(rect.left, rect.bottom - c)
        canvas.drawPath(path, corner)
    }
}
