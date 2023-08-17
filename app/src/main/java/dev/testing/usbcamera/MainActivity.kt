package dev.testing.usbcamera

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraDevice
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import dev.dotworld.test.usbcamera.UsbCamera
import dev.dotworld.test.usbcamera.UsbCameraListener
import dev.testing.usbcamera.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), UsbCameraListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var usbCamere: UsbCamera

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // init class
        usbCamere = UsbCamera(this)
        // init Listener
        usbCamere.setListener(this)
        // check permission check
        if (usbCamere.checkAllCameraPermissions()) {
            if (binding.textureView.surfaceTexture != null) {
                usbCamere.openUsbCamera(context = this, preView = binding.textureView)
            } else {
                Handler(Looper.getMainLooper()).postDelayed({
                    usbCamere.openUsbCamera(context = this, preView = binding.textureView)
                }, 100)
            }
        } else {

        }
        binding.takeImage.setOnClickListener {
            //if un-want to save local auto save set to false
            usbCamere.takePicture(autoSave = true)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            usbCamere.ALL_PERMISSIONS_CODE -> {
                var allPermissionsGranted = true
                for (result in grantResults) {
                    if (result != PackageManager.PERMISSION_GRANTED) {
                        allPermissionsGranted = false
                        break
                    }
                }

                if (allPermissionsGranted) {
                    // All permissions granted, proceed with operations
                    if (binding.textureView.surfaceTexture != null) {
                        usbCamere.openUsbCamera(context = this, preView = binding.textureView)
                    } else {
                        Handler(Looper.getMainLooper()).postDelayed({
                            usbCamere.openUsbCamera(context = this, preView = binding.textureView)
                        }, 100)
                    }
                } else {
                    // Some permissions were denied, handle accordingly
                }
            }
        }
    }

    override fun currentCamera(camera: CameraDevice) {
        //selected camera
        Log.d(TAG, "currentCamera: camera - $camera")
    }

    override fun takePicture(bitmap: Bitmap) {
        // if want to save in local file next line save bitmap to image
        //usbCamere.saveBitmapToFile(bitmap, "SampleAndTest_${usbCamere.getTime()}.jpg")
        binding.preview.setImageBitmap(bitmap)
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}