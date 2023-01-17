package org.kman.cameratest

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.core.view.get

class SurfaceLayout(context: Context, attributes: AttributeSet?) : ViewGroup(context, attributes)
{
    private val mSurfaceView = SurfaceView(context)
    private var mVideoWidth = 0
    private var mVideoHeight = 0

    val surfaceView: SurfaceView
        get() {
            return mSurfaceView
        }

    init {
        addView(mSurfaceView)
    }

    fun setVideoSize(width: Int, height: Int) {
       if (mVideoWidth != width || mVideoHeight != height) {
           mVideoWidth = width
           mVideoHeight = height

           requestLayout()
       }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        if (mVideoWidth == 0 || mVideoHeight == 0) {
            mSurfaceView.measure(
                MeasureSpec.makeMeasureSpec(widthSize, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(heightSize, MeasureSpec.EXACTLY)
            )
        } else {
            val surfaceWidth: Int
            val surfaceHeight: Int

            if (mVideoWidth * heightSize > widthSize * mVideoHeight) {
                surfaceWidth = widthSize
                surfaceHeight = mVideoHeight * widthSize / mVideoWidth
            } else {
                surfaceWidth  = mVideoWidth * heightSize / mVideoHeight
                surfaceHeight = heightSize
            }

            val videoAspect = mVideoWidth / mVideoHeight.toFloat()
            val surfaceAspect = surfaceWidth / surfaceHeight.toFloat()

            mSurfaceView.measure(
                MeasureSpec.makeMeasureSpec(surfaceWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(surfaceHeight, MeasureSpec.EXACTLY)
            )
        }

        setMeasuredDimension(widthSize, heightSize)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = mSurfaceView.measuredWidth
        val h = mSurfaceView.measuredHeight
        val left = ((r - l) - w) / 2
        val top = ((b - t) - h) / 2

        mSurfaceView.layout(left, top, left + w, top + h)
    }
}
