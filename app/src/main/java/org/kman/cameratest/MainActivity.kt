package org.kman.cameratest

import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.TextView

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        mHelloView = findViewById(R.id.hello_text)
    }

    override fun onResume() {
        super.onResume()

        if (hasPermissions()) {
            mHelloView.setText(R.string.has_permissions)
        } else {
            mHelloView.text = getString(R.string.no_permissions)
            requestPermissions(PERM_LIST, PERM_CODE)
        }
    }

    private fun hasPermissions(): Boolean {
        return PERM_LIST.find { perm ->
            checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED
        } == null
    }

    private lateinit var mHelloView: TextView

    companion object {
        private val PERM_LIST = arrayOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO
        )
        private const val PERM_CODE = 1
    }
}
