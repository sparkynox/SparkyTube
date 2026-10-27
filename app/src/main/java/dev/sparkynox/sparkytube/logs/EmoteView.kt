package dev.sparkynox.sparkytube.logs

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DrawableRes

// artwork + text body for the plain system dialogs. the crash screens can't use Compose
// (the crash might be Compose itself), so they get this instead
object EmoteView {
    fun body(ctx: Context, @DrawableRes img: Int, text: String): View {
        val d = ctx.resources.displayMetrics.density
        val pad = (20 * d).toInt()
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad / 2, pad, 0)
        }
        col.addView(ImageView(ctx).apply {
            setImageResource(img)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (110 * d).toInt())
        })
        col.addView(TextView(ctx).apply {
            this.text = text
            textSize = 14f
            setPadding(0, pad / 2, 0, 0)
        })
        return ScrollView(ctx).apply { addView(col) }
    }
}
