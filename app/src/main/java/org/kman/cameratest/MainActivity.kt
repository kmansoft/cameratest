package org.kman.cameratest

import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
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

    override fun surfaceCreated(holder: SurfaceHolder) {
        mSurface = holder.surface

        if (hasPermissions()) {
            init()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        mSurface = null
    }

    private fun init() {
        val surface = mSurface ?: return

        if (mIsInitDone) {
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

        val char = cameraManager.getCameraCharacteristics(frontCameraId)
        val streamConfigMap = requireNotNull(char.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
        val previewSizeList = streamConfigMap.getOutputSizes(SurfaceHolder::class.java)
        val previewSize = choosePreviewSize(previewSizeList, 720, 480)

        mSurfaceLayout.setVideoSize(previewSize.width, previewSize.height)
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

    private var mIsStopped = true
    private var mIsInitDone = false
    private var mCameraManager: CameraManager? = null
    private var mSurface: Surface? = null

    companion object {
        private val PERM_LIST = arrayOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO
        )
        private const val PERM_CODE = 1
    }
}
