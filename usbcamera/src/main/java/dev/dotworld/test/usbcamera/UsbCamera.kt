package dev.dotworld.test.usbcamera

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.icu.text.SimpleDateFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.SparseIntArray
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.io.ByteArrayOutputStream
import java.util.Date
import java.util.Locale

class UsbCamera(activity: Activity) {

    private lateinit var cameraDevice: CameraDevice
    private lateinit var captureSession: CameraCaptureSession
    private lateinit var imageReader: ImageReader

    private lateinit var captureRequestBuilder: CaptureRequest.Builder
    private lateinit var cameraCharacteristics: CameraCharacteristics
    private lateinit var backgroundHandler: Handler

    val ALL_PERMISSIONS_CODE = 100

    private var cap = false

    private lateinit var textureView: TextureView

    private lateinit var gContext: Context
    private var gActivity: Activity = activity


    private var usbCameraListener: UsbCameraListener? = null

    fun getListener(): UsbCameraListener? {
        return usbCameraListener
    }

    fun setListener(listener: UsbCameraListener) {
        usbCameraListener = listener
    }

    fun checkAllCameraPermissions(activity: Activity = gActivity): Boolean {
        gActivity = activity
        val permissionsToCheck = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )

        val permissionsToRequest = mutableListOf<String>()

        for (permission in permissionsToCheck) {
            if (ContextCompat.checkSelfPermission(
                    activity, permission
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(permission)
            }
        }

        return if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                activity, permissionsToRequest.toTypedArray(), ALL_PERMISSIONS_CODE
            )
            false
        } else {
            true
        }
    }

    fun openUsbCamera(context: Context = gActivity, preView: TextureView) {
        textureView = preView
        if (!checkAllCameraPermissions()) {
            return
        } else {

            gContext = context
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            try {
                Log.i(TAG, "openCamera: ${textureView.surfaceTexture}")
                val cameraIds = cameraManager.cameraIdList
                if (cameraIds.isEmpty()) {
                    return
                }
                val characteristics = cameraManager.getCameraCharacteristics(cameraIds.first())
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)

                if (lensFacing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                    val map =
                        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val imageFormat = ImageFormat.YUV_420_888

                    val sizes = map?.getOutputSizes(imageFormat)

                    val bestSize = sizes?.maxByOrNull { it.width * it.height }
                    if (bestSize != null) {
                        cameraCharacteristics = characteristics
                        imageReader = ImageReader.newInstance(
                            bestSize.width, bestSize.height, imageFormat, 2
                        )
                        imageReader.setOnImageAvailableListener(imageReaderListener, null)

                        if (ActivityCompat.checkSelfPermission(
                                context, Manifest.permission.CAMERA
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            // Request CAMERA permission here and handle the response
                            return
                        }
                        cameraManager.openCamera(cameraIds.first(), cameraStateCallback, null)
                    } else {
                        Log.e(TAG, "No suitable capture size found.")
                    }
                } else {
                    Log.e(TAG, "External camera not found.")
                }
            } catch (e: CameraAccessException) {
                e.printStackTrace()
                Log.e(TAG, "openCamera: Camera access exception.")
            }
        }
    }


    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera

            Log.d(TAG, "onOpened: $usbCameraListener")
            usbCameraListener?.currentCamera(camera)
            Log.d(TAG, "onOpened: $cameraDevice / $camera")
            createCameraPreviewSession()
            val backgroundThread = HandlerThread("CameraBackground");
            backgroundThread.start();
            backgroundHandler = Handler(backgroundThread.looper);
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraDevice.close()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            cameraDevice.close()
        }
    }

    private fun createCameraPreviewSession() {
        try {
            Log.d(TAG, "createCameraPreviewSession: ${textureView}")
            val texture = textureView.surfaceTexture
            Log.d(TAG, "createCameraPreviewSession: $texture")
            texture?.setDefaultBufferSize(imageReader.width, imageReader.height)
            val surface = Surface(texture)
            val captureRequestBuilder =
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder.addTarget(surface)
            captureRequestBuilder.addTarget(imageReader.surface)

            cameraDevice.createCaptureSession(
                listOf(surface, imageReader.surface), captureSessionStateCallback, null
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private val captureSessionStateCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            captureSession = session
            updatePreview()
        }

        override fun onConfigureFailed(session: CameraCaptureSession) {
            // Handle configuration failure.
        }
    }

    private fun updatePreview() {
        try {
            Log.d(TAG, "updatePreview: ")
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            val texture = textureView.surfaceTexture
            texture?.setDefaultBufferSize(imageReader.width, imageReader.height)
            val surface = Surface(texture)

            captureRequestBuilder.addTarget(surface)
            captureRequestBuilder.addTarget(imageReader.surface)
            captureRequestBuilder.set(
                CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )

            captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private val imageReaderListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireLatestImage()
        if (cap) {
            processCapturedImage(image)
            cap = !cap
        }
        image?.close()
    }

    private fun processCapturedImage(image: Image?) {
        Log.d(TAG, "processCapturedImage: bitmap = $image")
        if (image != null) {
            // Convert the Image to a format you can work with (e.g., Bitmap)
            val bitmap = imageToBitmap(image)
            usbCameraListener?.takePicture(bitmap)
            Log.d(TAG, "processCapturedImage: bitmap = ${bitmap.byteCount}")
            // Perform your post-processing tasks here, such as saving the image or displaying it to the user.
            if (autoSaveOption) {
                saveBitmapToFile(bitmap, "UsbCam_${getTime()}.jpg")
            }
            // Don't forget to close the Image object when you're done with it.
            image.close()
        }
    }

    fun saveBitmapToFile(bitmap: Bitmap, filename: String) {
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, savePath)
        }

        val contentResolver = gContext.contentResolver
        val uri =
            contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        uri?.let {
            val outputStream = contentResolver.openOutputStream(uri)
            outputStream?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
            }
        }
        // Optionally, you can notify the MediaScanner to update the gallery
        MediaScannerConnection.scanFile(gContext, arrayOf(uri.toString()), null, null)
    }

    fun getTime(): String? {
        val currentDate = Date()
        val dateFormat = SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.getDefault())
        return dateFormat.format(currentDate)
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)

        val jpegBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }

    fun getCamera(): CameraDevice {
        return cameraDevice
    }

    fun takePicture(requireActivity: FragmentActivity, camera: CameraDevice) {
        try {
            gActivity = requireActivity
            gContext = gActivity.applicationContext
            Log.d(TAG, "takePicture: ")
            cameraDevice = camera
            if (this::cameraDevice.isInitialized) {
                val captureBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                captureBuilder.addTarget(imageReader.surface)
                captureRequestBuilder.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
                captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation())
                captureSession.capture(captureBuilder.build(), captureCallback, backgroundHandler)
            } else {
                Toast.makeText(gActivity.applicationContext, "camera not init", Toast.LENGTH_SHORT)
                    .show()
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
            Log.d(TAG, "takePicture: error = ${e.message}")
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
        ) {
            // Image captured and saved. Handle post-processing here if needed.
            imageReaderListener.onImageAvailable(imageReader)
            Log.d(TAG, "onCaptureCompleted: ${imageReader.acquireLatestImage()}")
            cap = true
            //processCapturedImage(imageReader.acquireLatestImage())
        }
    }


    private fun getJpegOrientation(): Int {
        val sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
        val deviceRotation = gActivity.windowManager.defaultDisplay.rotation

        if (sensorOrientation != null) {
            return (sensorOrientation + ORIENTATIONS.get(deviceRotation) + 360) % 360
        }
        return 0
    }

    fun takePicture(autoSave: Boolean = true) {
        try {
            autoSaveOption = autoSave
            gContext = gActivity.applicationContext
            Log.d(TAG, "takePicture: ")
            if (this::cameraDevice.isInitialized) {
                val captureBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                captureBuilder.addTarget(imageReader.surface)
                captureRequestBuilder.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
                captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation())
                captureSession.capture(captureBuilder.build(), captureCallback, backgroundHandler)
            } else {
                Toast.makeText(gActivity.applicationContext, "camera not init", Toast.LENGTH_SHORT)
                    .show()
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
            Log.d(TAG, "takePicture: error = ${e.message}")
        }
    }

    private val ORIENTATIONS = SparseIntArray()

    init {
        ORIENTATIONS.append(Surface.ROTATION_0, 90)
        ORIENTATIONS.append(Surface.ROTATION_90, 0)
        ORIENTATIONS.append(Surface.ROTATION_180, 270)
        ORIENTATIONS.append(Surface.ROTATION_270, 180)
    }

    fun closeCamera() {
        if (this::captureSession.isInitialized) {
            captureSession.close()
        }

        if (this::cameraDevice.isInitialized) {
            cameraDevice.close()
        }

        if (this::imageReader.isInitialized) {
            imageReader.close()
        }
    }

    companion object {
        var autoSaveOption: Boolean = true
        private const val TAG = "UsbCamera"
        private val savePath: String = Environment.DIRECTORY_DCIM + "/UsbCam"
    }
}