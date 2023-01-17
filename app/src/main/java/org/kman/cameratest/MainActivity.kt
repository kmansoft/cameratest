package org.kman.cameratest

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
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
import androidx.core.app.ActivityCompat

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

        mCaptureSession?.close()
        mCamera?.close()
    }

    // Surface holder callback

    override fun surfaceCreated(holder: SurfaceHolder) {
        mSurface = holder.surface

        if (hasPermissions()) {
            init()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        MyLog.i(TAG, "surfaceChanged: %d x %d", width, height)

        mSurfaceLayout.requestLayout()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        mSurface = null

        mCaptureSession?.close()
        mCamera?.close()

        mIsInitDone = false
    }

    // Camera callback

    private fun onCameraOpened(camera: CameraDevice) {
        mCamera = camera

        val surface = mSurface ?: return

        val surfaceList = arrayListOf(surface)
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

        val surface = mSurface ?: return
        val camera = mCamera ?: return
        val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
        }.build()

        session.setRepeatingRequest(captureRequest, null, mHandler)
    }

    private fun onCaptureSessionConfigureFailed(session: CameraCaptureSession) {
        mHelloView.setText(R.string.camera_session_configure_failed)
    }

    @SuppressLint("MissingPermission")
    private fun init() {
        if (mIsInitDone || mSurface == null) {
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
        val streamConfigMap = requireNotNull(char.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
        val previewSizeList = streamConfigMap.getOutputSizes(SurfaceHolder::class.java)
        val previewSize = choosePreviewSize(previewSizeList, 640, 480)

        mSurfaceHolder.setFixedSize(previewSize.width, previewSize.height)
        mSurfaceLayout.setVideoSize(previewSize.width, previewSize.height)

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
    private var mSurface: Surface? = null
    private var mCamera: CameraDevice? = null
    private var mCaptureSession: CameraCaptureSession? = null

    companion object {
        private const val TAG = "MainActivity"

        private val PERM_LIST = arrayOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO
        )
        private const val PERM_CODE = 1
    }
}
