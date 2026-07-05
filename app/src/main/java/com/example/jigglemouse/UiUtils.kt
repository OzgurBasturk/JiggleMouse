package com.example.jigglemouse

import android.view.View
import android.view.animation.DecelerateInterpolator

/** Subtle fade+rise entrance used consistently across every screen. */
fun View.fadeInUp(duration: Long = 320L, startDelay: Long = 0L) {
    alpha = 0f
    translationY = 24f
    animate()
        .alpha(1f)
        .translationY(0f)
        .setStartDelay(startDelay)
        .setDuration(duration)
        .setInterpolator(DecelerateInterpolator())
        .start()
}

/** Quick scale-pop, useful for confirming an action (e.g. a value was applied). */
fun View.pulseOnce() {
    animate().scaleX(1.06f).scaleY(1.06f).setDuration(90).withEndAction {
        animate().scaleX(1f).scaleY(1f).setDuration(120).start()
    }.start()
}
