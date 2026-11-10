package com.kooritea.fcmfix

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.kooritea.fcmfix.ui.FcmFixApp
import com.kooritea.fcmfix.ui.MainViewModel
import com.kooritea.fcmfix.ui.theme.FcmFixTheme
import com.kooritea.fcmfix.util.IceboxUtils

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestMissingPermissions()
        setContent {
            FcmFixTheme {
                FcmFixApp(viewModel)
            }
        }
    }

    /**
     * ColorOS hides every other app from an app that does not hold its app-list permission, so
     * ask for it up front rather than leaving the list empty. Ice Box's SDK permission is for
     * "允许唤醒被冰箱冻结的应用" and is ignored when Ice Box is not installed.
     */
    private fun requestMissingPermissions() {
        val missing = REQUESTED_PERMISSIONS.filter {
            try {
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            } catch (ignored: Throwable) {
                false
            }
        }
        if (missing.isEmpty()) return
        try {
            requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        } catch (ignored: Throwable) {
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // The list is read once at start; read it again now that the answer is in.
        if (requestCode == PERMISSION_REQUEST_CODE) viewModel.scanApps()
    }

    private companion object {
        const val PERMISSION_REQUEST_CODE = IceboxUtils.REQUEST_CODE
        val REQUESTED_PERMISSIONS = listOf("com.android.permission.GET_INSTALLED_APPS", IceboxUtils.SDK_PERMISSION)
    }
}
