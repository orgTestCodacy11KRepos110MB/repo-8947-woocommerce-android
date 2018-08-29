package com.woocommerce.android.widgets

import android.os.Handler
import android.support.annotation.LayoutRes
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import com.facebook.shimmer.ShimmerFrameLayout
import com.woocommerce.android.util.WooAnimUtils
import com.woocommerce.android.util.WooAnimUtils.Duration

class WPSkeletonView {
    private lateinit var parentView: ViewGroup
    private lateinit var dataView: ViewGroup
    private lateinit var skeletonView: View
    private lateinit var shimmerView: ShimmerFrameLayout

    private val handler = Handler()

    private var skeletonLayoutId: Int = 0
    private var isShowing = false
    private var wasHidden = false

    /**
     * Replaces the passed ViewGroup with a skeleton view inflated from the passed layout id
     * and starts a shimmer animation on the skeleton view
     *
     * @param view The view containing the "real" data which will be hidden
     * @param layoutId The resource id of the skeleton layout which will replace the above view
     * @param delayed Whether to show the skeleton after a brief delay, which avoids the skeleton appearing
     * and then immediately disappearing if the network request completes very quickly
     */
    fun show(view: ViewGroup, @LayoutRes layoutId: Int, delayed: Boolean = true) {
        if (isShowing) { return }

        if (delayed) {
            wasHidden = false
            handler.postDelayed({
                if (!wasHidden) {
                    show(view, layoutId, false)
                }
            }, 250)
            return
        }

        val viewParent = view.parent ?: throw IllegalStateException("Source view isn't attached")

        parentView = viewParent as ViewGroup
        dataView = view
        skeletonLayoutId = layoutId

        // create the shimmer view
        shimmerView = ShimmerFrameLayout(parentView.context)
        shimmerView.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)

        // add our skeleton layout to the shimmer view
        skeletonView = LayoutInflater.from(parentView.context).inflate(layoutId, parentView, false)
        shimmerView.addView(skeletonView)

        // hide the data view, add the shimmer view, then start the shimmer animation
        dataView.visibility = View.GONE
        parentView.addView(shimmerView)
        shimmerView.startShimmer()

        isShowing = true
    }

    /**
     * hides the shimmer and skeleton layout then restores the real data layout
     */
    fun hide() {
        wasHidden = true

        if (!isShowing) { return }

        // stop the shimmer, remove the skeleton view, then remove the shimmer view from the parent
        shimmerView.stopShimmer()
        shimmerView.removeView(skeletonView)
        parentView.removeView(shimmerView)

        // fade in the real data view
        WooAnimUtils.fadeIn(dataView, Duration.MEDIUM)

        isShowing = false
    }
}
