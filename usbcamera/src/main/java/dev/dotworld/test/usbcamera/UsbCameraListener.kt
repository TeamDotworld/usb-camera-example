package dev.dotworld.test.usbcamera

import android.graphics.Bitmap
import android.hardware.camera2.CameraDevice

interface UsbCameraListener {
    fun currentCamera(camera: CameraDevice)
    fun takePicture(bitmap: Bitmap)
}