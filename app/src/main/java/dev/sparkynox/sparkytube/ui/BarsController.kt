package dev.sparkynox.sparkytube.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator

// slides the top and bottom bar away by shrinking their height, so the content grows into the space
// at the same speed instead of jumping. only the height is touched, visibility stays with MainActivity
class BarsController(private val top: View, private val bottom: View) {

    private var hidden = false
    private var frac = 0f           // 0 = fully shown, 1 = fully hidden
    private var topFull = 0
    private var bottomFull = 0
    private var anim: ValueAnimator? = null

    fun setHidden(hide: Boolean) {
        if (hide == hidden) return
        if (!hidden) {
            // measured while the bars are out. 0 means they are GONE right now (fullscreen), leave them alone
            if (top.height <= 0 || bottom.height <= 0) return
            topFull = top.height
            bottomFull = bottom.height
        }
        hidden = hide
        anim?.cancel()
        val to = if (hide) 1f else 0f
        anim = ValueAnimator.ofFloat(frac, to).apply {
            duration = 190
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                frac = it.animatedValue as Float
                top.layoutParams.height = (topFull * (1f - frac)).toInt()
                bottom.layoutParams.height = (bottomFull * (1f - frac)).toInt()
                top.alpha = 1f - frac
                bottom.alpha = 1f - frac
                top.requestLayout()
                bottom.requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    if (to == 0f) {
                        // back to the original sizes, in case the font scale or insets change later
                        top.layoutParams.height = topFull
                        bottom.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        top.requestLayout()
                        bottom.requestLayout()
                    }
                }
            })
            start()
        }
    }

    fun show() = setHidden(false)
}
