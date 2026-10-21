package it.liberofiles.app

import android.graphics.*
import android.graphics.drawable.Drawable

/** Small, consistent vector icons; no fonts, emoji, bitmap loading or network assets. */
class UiIcon(private val kind: String, private val tint: Int, private val size: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Drawable API") override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        paint.color = tint; paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.7f
        paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        fun line(vararg coordinates: Float) {
            val path = Path(); path.moveTo(coordinates[0], coordinates[1])
            for (i in 2 until coordinates.size step 2) path.lineTo(coordinates[i], coordinates[i + 1])
            canvas.drawPath(path, paint)
        }
        fun rect(l: Float, t: Float, r: Float, b: Float, radius: Float = 2f) = canvas.drawRoundRect(l,t,r,b,radius,radius,paint)
        when(kind) {
            "cloud" -> { val p=Path().apply { moveTo(6f,19f); cubicTo(0f,19f,0f,10f,6f,10f); cubicTo(7f,2f,18f,2f,19f,11f); cubicTo(25f,11f,24f,19f,19f,19f); close() }; canvas.drawPath(p,paint) }
            "folder" -> { val p=Path().apply { moveTo(3f,6f); quadTo(3f,4f,5f,4f); lineTo(10f,4f); lineTo(12f,7f); lineTo(19f,7f); quadTo(21f,7f,21f,9f); lineTo(21f,18f); quadTo(21f,20f,19f,20f); lineTo(5f,20f); quadTo(3f,20f,3f,18f); close() }; paint.style=Paint.Style.FILL; canvas.drawPath(p,paint); paint.color=Color.argb(70,255,255,255); canvas.drawRect(3f,9f,21f,11f,paint) }
            "file", "pdf", "zip", "image", "music" -> { line(14f,3f,5f,3f,5f,21f,19f,21f,19f,8f,14f,3f,14f,8f,19f,8f); when(kind) {
                "image" -> { canvas.drawCircle(9f,12f,1f,paint); line(7f,18f,11f,14f,14f,17f,16f,15f) }
                "zip" -> { for(y in 10..18 step 3) line(11f,y.toFloat(),13f,y.toFloat()) }
                "music" -> { line(13f,17f,13f,11f,16f,10f); canvas.drawCircle(11f,17f,2f,paint) }
                else -> { line(8f,12f,15f,12f); line(8f,16f,13f,16f) }
            } }
            "home" -> { rect(4f,3f,20f,21f,3f); line(8f,7f,16f,7f); line(10f,17f,14f,17f) }
            "star" -> line(12f,3f,14.8f,8.8f,21f,9.7f,16.5f,14f,17.6f,20.5f,12f,17.4f,6.4f,20.5f,7.5f,14f,3f,9.7f,9.2f,8.8f,12f,3f)
            "clock" -> { canvas.drawCircle(12f,12f,9f,paint); line(12f,7f,12f,12f,16f,14f) }
            "trash" -> { line(4f,7f,20f,7f); line(9f,4f,15f,4f); line(6f,7f,7f,21f,17f,21f,18f,7f); line(10f,11f,10f,17f); line(14f,11f,14f,17f) }
            "grid" -> { rect(3f,3f,10f,10f,1f); rect(14f,3f,21f,10f,1f); rect(3f,14f,10f,21f,1f); rect(14f,14f,21f,21f,1f) }
            "list" -> { for(y in listOf(5f,12f,19f)) { line(3f,y,5f,y); line(9f,y,21f,y) } }
            "search" -> { canvas.drawCircle(10.5f,10.5f,6.5f,paint); line(15.5f,15.5f,21f,21f) }
            "sort" -> { line(4f,6f,20f,6f); line(4f,12f,15f,12f); line(4f,18f,10f,18f) }
            "plus" -> { line(12f,5f,12f,19f); line(5f,12f,19f,12f) }
            "up" -> line(5f,12f,12f,5f,19f,12f,12f,5f,12f,20f)
            "check" -> line(5f,12f,10f,17f,19f,7f)
            "close" -> { line(6f,6f,18f,18f); line(18f,6f,6f,18f) }
            "copy" -> { rect(8f,8f,21f,21f); line(16f,4f,3f,4f,3f,16f) }
            "move" -> { line(3f,12f,21f,12f,16f,7f); line(21f,12f,16f,17f) }
            "paste" -> { rect(5f,5f,19f,21f); rect(9f,3f,15f,7f,1f) }
            "settings" -> { canvas.drawCircle(12f,12f,4f,paint); canvas.drawCircle(12f,12f,9f,paint); for(i in 0..7) { canvas.save(); canvas.rotate(i*45f,12f,12f); line(12f,1f,12f,3f); canvas.restore() } }
            "usb" -> { line(12f,3f,12f,19f); line(9f,6f,12f,3f,15f,6f); line(12f,14f,6f,10f,6f,7f); line(12f,16f,18f,12f,18f,9f); canvas.drawCircle(12f,20f,2f,paint); rect(16.5f,6f,19.5f,9f,.3f) }
            else -> for(y in listOf(5f,12f,19f)) { paint.style=Paint.Style.FILL; canvas.drawCircle(12f,y,1.7f,paint) }
        }
        canvas.restoreToCount(save)
    }
}
