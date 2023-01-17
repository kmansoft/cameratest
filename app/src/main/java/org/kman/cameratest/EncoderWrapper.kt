package org.kman.cameratest

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.Surface
import java.lang.ref.WeakReference
import java.nio.ByteBuffer

// From
// https://github.com/android/camera-samples/blob/main/Camera2Video/app/src/main/java/com/example/android/camera2/video/EncoderWrapper.kt

class EncoderWrapper(
    width: Int,
    height: Int,
    bitRate: Int,
    frameRate: Int,
    mimeType: String,
    codecProfile: Int
) {
    companion object {
        private val VERBOSE = BuildConfig.DEBUG

        private const val TAG = "EncoderWrapper"
        private const val IFRAME_INTERVAL = 1 // sync one frame every second
    }

    private val mEncoder = MediaCodec.createEncoderByType(mimeType)

    private val mInputSurface: Surface by lazy {
        mEncoder.createInputSurface()
    }

    private val mEncoderThread: EncoderThread by lazy {
        EncoderThread(mEncoder)
    }

    /**
     * Configures encoder
     */
    init {
        val format = MediaFormat.createVideoFormat(mimeType, width, height)

        // Set some properties.  Failing to specify some of these can cause the MediaCodec
        // configure() call to throw an unhelpful exception.
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL)

        if (codecProfile != -1) {
            format.setInteger(MediaFormat.KEY_PROFILE, codecProfile)
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, getTransferFunction(codecProfile))
        }

        if (VERBOSE) MyLog.d(TAG, "format: %s", format)

        // Create a MediaCodec encoder, and configure it with our format.  Get a Surface
        // we can use for input and wrap it with a class that handles the EGL work.
        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    private fun getTransferFunction(codecProfile: Int) = when (codecProfile) {
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 -> MediaFormat.COLOR_TRANSFER_HLG
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ->
            MediaFormat.COLOR_TRANSFER_ST2084
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus ->
            MediaFormat.COLOR_TRANSFER_ST2084
        else -> MediaFormat.COLOR_TRANSFER_SDR_VIDEO
    }

    /**
     * Returns the encoder's input surface.
     */
    fun getInputSurface(): Surface {
        return mInputSurface
    }

    fun start() {
        mEncoder.start()

        // Start the encoder thread last.  That way we're sure it can see all of the state
        // we've initialized.
        mEncoderThread.start()
        mEncoderThread.waitUntilReady()
    }

    /**
     * Shuts down the encoder thread, and releases encoder resources.
     * <p>
     * Does not return until the encoder thread has stopped.
     */
    fun shutdown() {
        if (VERBOSE) MyLog.d(TAG, "releasing encoder objects")

        val handler = mEncoderThread.getHandler()
        handler.sendMessage(handler.obtainMessage(EncoderThread.EncoderHandler.MSG_SHUTDOWN))
        try {
            mEncoderThread.join()
        } catch (ie: InterruptedException) {
            MyLog.w(TAG, "Encoder thread join() was interrupted", ie)
        }

        mEncoder.stop()
        mEncoder.release()
    }

    /**
     * Notifies the encoder thread that a new frame is available to the encoder.
     */
    fun frameAvailable() {
        val handler = mEncoderThread.getHandler()
        handler.sendMessage(
            handler.obtainMessage(
                EncoderThread.EncoderHandler.MSG_FRAME_AVAILABLE
            )
        )
    }

    fun waitForFirstFrame() {
        mEncoderThread.waitForFirstFrame()
    }

    /**
     * Object that encapsulates the encoder thread.
     * <p>
     * We want to sleep until there's work to do.  We don't actually know when a new frame
     * arrives at the encoder, because the other thread is sending frames directly to the
     * input surface.  We will see data appear at the decoder output, so we can either use
     * an infinite timeout on dequeueOutputBuffer() or wait() on an object and require the
     * calling app wake us.  It's very useful to have all of the buffer management local to
     * this thread -- avoids synchronization.
     * So, it's best to sleep on an object and do something appropriate when awakened.
     * <p>
     * This class does not manage the MediaCodec encoder startup/shutdown.  The encoder
     * should be fully started before the thread is created, and not shut down until this
     * thread has been joined.
     */
    private class EncoderThread(
        mediaCodec: MediaCodec,
    ) : Thread() {
        val mEncoder = mediaCodec
        var mEncodedFormat: MediaFormat? = null
        val mBufferInfo = MediaCodec.BufferInfo()

        var mHandler: EncoderHandler? = null
        var mFrameNum: Int = 0

        val mLock = Object()

        @Volatile
        var mReady: Boolean = false

        /**
         * Thread entry point.
         * <p>
         * Prepares the Looper, Handler, and signals anybody watching that we're ready to go.
         */
        override fun run() {
            Looper.prepare()
            mHandler = EncoderHandler(this)    // must create on encoder thread

            MyLog.i(TAG, "encoder thread ready")
            synchronized(mLock) {
                mReady = true
                mLock.notify()    // signal waitUntilReady()
            }

            Looper.loop()

            synchronized(mLock) {
                mReady = false
                mHandler = null
            }
            MyLog.i(TAG, "looper quit")
        }

        /**
         * Waits until the encoder thread is ready to receive messages.
         * <p>
         * Call from non-encoder thread.
         */
        fun waitUntilReady() {
            synchronized(mLock) {
                while (!mReady) {
                    try {
                        mLock.wait()
                    } catch (ie: InterruptedException) { /* not expected */
                    }
                }
            }
        }

        /**
         * Waits until the encoder has processed a single frame.
         * <p>
         * Call from non-encoder thread.
         */
        fun waitForFirstFrame() {
            synchronized(mLock) {
                while (mFrameNum < 1) {
                    try {
                        mLock.wait()
                    } catch (ie: InterruptedException) {
                        MyLog.w(TAG, "waitForFirstFrame", ie)
                    }
                }
            }
            MyLog.i(TAG, "Waited for first frame")
        }

        /**
         * Returns the Handler used to send messages to the encoder thread.
         */
        fun getHandler(): EncoderHandler {
            synchronized(mLock) {
                // Confirm ready state.
                if (!mReady) {
                    throw RuntimeException("not ready")
                }
            }
            return requireNotNull(mHandler)
        }

        /**
         * Drains all pending output from the encoder, and adds it to the circular buffer.
         */
        fun drainEncoder(): Boolean {
            val TIMEOUT_USEC: Long = 0     // no timeout -- check for buffers, bail if none
            var encodedFrame = false

            while (true) {
                val encoderStatus = mEncoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC)
                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // no output available yet
                    break;
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Should happen before receiving buffers, and should only happen once.
                    // The MediaFormat contains the csd-0 and csd-1 keys, which we'll need
                    // for MediaMuxer.  It's unclear what else MediaMuxer might want, so
                    // rather than extract the codec-specific data and reconstruct a new
                    // MediaFormat later, we just grab it here and keep it around.
                    mEncodedFormat = mEncoder.outputFormat
                    MyLog.d(TAG, "encoder output format changed: %s", mEncodedFormat)
                } else if (encoderStatus < 0) {
                    MyLog.w(
                        TAG, "unexpected result from encoder.dequeueOutputBuffer: $encoderStatus"
                    )
                    // let's ignore it
                } else {
                    val encodedData: ByteBuffer = mEncoder.getOutputBuffer(encoderStatus)
                        ?: throw RuntimeException(
                            "encoderOutputBuffer $encoderStatus was null"
                        )

                    if ((mBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        // The codec config data was pulled out when we got the
                        // INFO_OUTPUT_FORMAT_CHANGED status.  The MediaMuxer won't accept
                        // a single big blob -- it wants separate csd-0/csd-1 chunks --
                        // so simply saving this off won't work.
                        if (VERBOSE) MyLog.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG")
                        // CSD frames mBufferInfo.size = 0
                    }

                    if (mBufferInfo.size != 0) {
                        encodedFrame = true

                        var peek: ByteArray?
                        val isKeyFrame = (mBufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        if (isKeyFrame) {
                            peek = ByteArray(16)
                            encodedData.get(peek)
                        }

                        if (VERBOSE) {
                            MyLog.d(
                                TAG,
                                "Got %d  encoded bytes, ts=%d, key = %b",
                                mBufferInfo.size, mBufferInfo.presentationTimeUs,
                                isKeyFrame
                            )
                        }
                    }

                    mEncoder.releaseOutputBuffer(encoderStatus, false)

                    if ((mBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        MyLog.w(TAG, "reached end of stream unexpectedly")
                        break      // out of while
                    }
                }
            }

            return encodedFrame
        }

        /**
         * Drains the encoder output.
         * <p>
         * See notes for {@link EncoderWrapper#frameAvailable()}.
         */
        fun frameAvailable() {
            if (VERBOSE) MyLog.d(TAG, "frameAvailable")
            if (drainEncoder()) {
                synchronized(mLock) {
                    mFrameNum++
                    mLock.notify()
                }
            }
        }

        /**
         * Tells the Looper to quit.
         */
        fun shutdown() {
            if (VERBOSE) MyLog.d(TAG, "shutdown")
            Looper.myLooper()?.quit()
        }

        /**
         * Handler for EncoderThread.  Used for messages sent from the UI thread (or whatever
         * is driving the encoder) to the encoder thread.
         * <p>
         * The object is created on the encoder thread.
         */
        class EncoderHandler(et: EncoderThread) : Handler(Looper.myLooper()!!) {
            companion object {
                const val MSG_FRAME_AVAILABLE: Int = 0
                const val MSG_SHUTDOWN: Int = 1
            }

            private val mWeakEncoderThread = WeakReference(et)

            // runs on encoder thread
            override fun handleMessage(msg: Message) {
                val what: Int = msg.what
                val encoderThread: EncoderThread? = mWeakEncoderThread.get()
                if (encoderThread == null) {
                    MyLog.w(TAG, "EncoderHandler.handleMessage: weak ref is null")
                    return
                }

                when (what) {
                    MSG_FRAME_AVAILABLE -> encoderThread.frameAvailable()
                    MSG_SHUTDOWN -> encoderThread.shutdown()
                    else -> throw RuntimeException("unknown message $what")
                }
            }
        }
    }
}
