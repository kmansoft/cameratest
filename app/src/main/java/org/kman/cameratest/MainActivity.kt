package org.kman.cameratest

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.hardware.camera2.*
import android.media.MediaFormat
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        mHelloView = findViewById(R.id.hello_text)
        mSurfaceLayout = findViewById(R.id.surface_layout)

        mSurfaceView = mSurfaceLayout.surfaceView
        mSurfaceHolder = mSurfaceView.holder

        mSurfaceHolder.addCallback(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })
    }

    override fun onStop() {
        super.onStop()

        mIsStopped = true
    }

    override fun onResume() {
        super.onResume()

        if (hasPermissions()) {
            mHelloView.setText(R.string.has_permissions)

            init()
        } else {
            mHelloView.setText(R.string.no_permissions)

            if (mIsStopped) {
                requestPermissions(PERM_LIST, PERM_CODE)
            }
        }

        mIsStopped = false
    }

    override fun onDestroy() {
        super.onDestroy()

        cleanup()
    }

    // Surface holder callback

    override fun surfaceCreated(holder: SurfaceHolder) {
        mPreviewSurface = holder.surface

        if (hasPermissions()) {
            init()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        MyLog.i(TAG, "surfaceChanged: %d x %d", width, height)

        mSurfaceLayout.requestLayout()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        mPreviewSurface = null

        cleanup()

        mIsInitDone = false
    }

    private fun cleanup() {
        mVideoEncoder?.shutdown()
        mVideoEncoder = null
        mVideoEncoderSurface?.release()
        mVideoEncoderSurface = null

        mCaptureSession?.close()
        mCaptureSession = null

        mCamera?.close()
        mCamera = null
    }

    private fun createVideoEncoder(width: Int, height: Int): EncoderWrapper {
        val mimeType = MediaFormat.MIMETYPE_VIDEO_AVC
        val codecProfile = -1

        return EncoderWrapper(
            width, height, RECORDER_VIDEO_BITRATE, 30,
            mimeType, codecProfile
        )
    }

    // Camera callback

    private fun onCameraOpened(camera: CameraDevice) {
        mCamera = camera

        val previewSurface = mPreviewSurface ?: return
        val encodeSize = mVideoEncodeSize ?: return

        val videoEncoder = createVideoEncoder(encodeSize.width, encodeSize.height)
        val videoEncoderSurface = videoEncoder.getInputSurface()

        mVideoEncoder = videoEncoder
        mVideoEncoderSurface = videoEncoderSurface

        val surfaceList = arrayListOf(previewSurface, videoEncoderSurface)
        @Suppress("DEPRECATION")
        camera.createCaptureSession(surfaceList, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                onCaptureSessionConfigured(session)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                onCaptureSessionConfigureFailed(session)
            }
        }, mHandler)
    }

    private fun onCameraDisconnected(camera: CameraDevice) {
        mHelloView.text = getString(R.string.camera_disconnected)
    }

    private fun onCameraError(camera: CameraDevice, error: Int) {
        mHelloView.text = getString(R.string.camera_error, error)
    }

    private fun onCaptureSessionConfigured(session: CameraCaptureSession) {
        mCaptureSession = session

        val previewSurface = mPreviewSurface ?: return
        val videoEncoderSurface = mVideoEncoderSurface ?: return

        val camera = mCamera ?: return

        val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
            addTarget(videoEncoderSurface)
        }.build()

        session.setRepeatingRequest(
            captureRequest,
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    mVideoEncoder?.frameAvailable()
                }
            },
            mHandler
        )

        mVideoEncoder?.start()
    }

    private fun onCaptureSessionConfigureFailed(session: CameraCaptureSession) {
        mHelloView.setText(R.string.camera_session_configure_failed)
    }

    @SuppressLint("MissingPermission")
    private fun init() {
        if (mIsInitDone || mPreviewSurface == null) {
            return
        }
        mIsInitDone = true

        mHelloView.setText(R.string.init_start)

        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        mCameraManager = cameraManager

        val frontCameraId = cameraManager.cameraIdList.find { cameraId ->
            val char = cameraManager.getCameraCharacteristics(cameraId)
            val facing = char.get(CameraCharacteristics.LENS_FACING)
            facing == CameraCharacteristics.LENS_FACING_FRONT
        }

        if (frontCameraId == null) {
            mHelloView.setText(R.string.no_front_facing_camera)
            return
        }

        // Set preview size
        val char = cameraManager.getCameraCharacteristics(frontCameraId)
        val streamConfigMap =
            requireNotNull(char.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
        val previewSizeList = streamConfigMap.getOutputSizes(SurfaceHolder::class.java)
        val previewSize = choosePreviewSize(previewSizeList, 640, 480)

        mSurfaceHolder.setFixedSize(previewSize.width, previewSize.height)
        mSurfaceLayout.setVideoSize(previewSize.width, previewSize.height)

        mVideoEncodeSize = previewSize

        // Open the camera
        cameraManager.openCamera(frontCameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                onCameraOpened(camera)
            }

            override fun onDisconnected(camera: CameraDevice) {
                onCameraDisconnected(camera)
            }

            override fun onError(camera: CameraDevice, error: Int) {
                onCameraError(camera, error)
            }
        }, mHandler)
    }

    private fun hasPermissions(): Boolean {
        return PERM_LIST.find { perm ->
            checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED
        } == null
    }

    private fun choosePreviewSize(list: Array<Size>, desiredWidth: Int, desiredHeight: Int): Size {
        var found = list.find { size ->
            size.width == desiredWidth && size.height == desiredHeight
        }
        if (found != null) {
            return found
        }

        val sorted = list.sortedArrayWith { o1, o2 ->
            (o1.width * o1.height).compareTo(o2.width * o2.height)
        }
        found = sorted.find { size ->
            size.width * size.height >= desiredWidth * desiredHeight &&
                    size.width * desiredHeight == size.height * desiredWidth
        }
        if (found != null) {
            return found
        }

        found = sorted.find { size ->
            size.width * size.height >= desiredWidth * desiredHeight
        }
        if (found != null) {
            return found
        }

        return sorted.first()
    }

    private lateinit var mHelloView: TextView
    private lateinit var mSurfaceLayout: SurfaceLayout
    private lateinit var mSurfaceView: SurfaceView
    private lateinit var mSurfaceHolder: SurfaceHolder

    private val mHandler = Handler(Looper.getMainLooper())

    private var mIsStopped = true
    private var mIsInitDone = false
    private var mCameraManager: CameraManager? = null
    private var mPreviewSurface: Surface? = null
    private var mCamera: CameraDevice? = null
    private var mCaptureSession: CameraCaptureSession? = null
    private var mVideoEncoder: EncoderWrapper? = null
    private var mVideoEncoderSurface: Surface? = null
    private var mVideoEncodeSize: Size? = null

    companion object {
        private const val TAG = "MainActivity"

        private val PERM_LIST = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        private const val PERM_CODE = 1

        private const val RECORDER_VIDEO_BITRATE = 10_000_000
    }
}
