package com.example.earthwallet.ui.pages.anml

import com.example.earthwallet.R
import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutionException
import java.util.regex.Pattern

class CameraMRZScannerFragment : Fragment() {

    companion object {
        private const val TAG = "CameraMRZScanner"
        private const val CAMERA_PERMISSION_REQUEST_CODE = 200

        // MRZ parsing patterns
        private val MRZ_LINE1_PATTERN = Pattern.compile("P<([A-Z]{3})([A-Z<]+)")
        private val MRZ_LINE2_PATTERN = Pattern.compile("([A-Z0-9<]{9})([0-9]{1})([A-Z]{3})([0-9]{6})([0-9]{1})([MF<]{1})([0-9]{6})([0-9]{1})([A-Z0-9<]{14})([0-9]{1})([0-9]{1})")

        @JvmStatic
        fun newInstance(): CameraMRZScannerFragment = CameraMRZScannerFragment()
    }

    private var previewView: PreviewView? = null
    private var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>? = null
    private var textRecognizer: TextRecognizer? = null
    private var imageAnalysis: ImageAnalysis? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_camera_mrz_scanner, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        Log.d(TAG, "onViewCreated called")

        // Hide bottom navigation and status bar - keep portrait orientation
        try {
            val activity = activity
            if (activity is com.example.earthwallet.ui.host.HostActivity) {
                Log.d(TAG, "Hiding navigation and status bar")
                activity.hideBottomNavigation()

                // Hide status bar
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)

                Log.d(TAG, "UI setup completed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up UI", e)
        }

        previewView = view.findViewById(R.id.camera_preview)
        val backButton = view.findViewById<Button>(R.id.btn_back)
        val manualEntryButton = view.findViewById<Button>(R.id.btn_manual_entry)

        Log.d(TAG, "Views found - previewView: ${previewView != null}, " +
                   "backButton: ${backButton != null}, " +
                   "manualEntryButton: ${manualEntryButton != null}")

        backButton?.setOnClickListener {
            val activity = activity
            if (activity is com.example.earthwallet.ui.host.HostActivity) {
                activity.showBottomNavigation()

                // Show status bar
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)

                activity.showFragment("anml")
            }
        }

        manualEntryButton?.setOnClickListener {
            Log.d(TAG, "Manual entry button clicked")
            val activity = activity
            if (activity is com.example.earthwallet.ui.host.HostActivity) {
                // Show status bar before navigating
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)

                activity.showFragment("mrz_input")
            }
        }

        // Initialize ML Kit Text Recognizer
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        // Check camera permission
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(requireActivity(), arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called")

        // Check if we need to start camera (in case permission was granted while paused)
        if (allPermissionsGranted() && previewView != null && cameraProviderFuture == null) {
            Log.d(TAG, "Permission available on resume, starting camera")
            startCamera()
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause called")
    }

    private fun allPermissionsGranted(): Boolean {
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (allPermissionsGranted()) {
                Log.d(TAG, "Camera permission granted, starting camera...")
                // Add a small delay to ensure views are ready
                view?.post {
                    if (isAdded && !isDetached && context != null) {
                        startCamera()
                    } else {
                        Log.w(TAG, "Fragment not ready to start camera after permission grant")
                    }
                }
            } else {
                Toast.makeText(context, "Camera permission is required to scan passport", Toast.LENGTH_LONG).show()
                // Go back to ANML screen and restore UI
                val activity = activity
                if (activity is com.example.earthwallet.ui.host.HostActivity) {
                    activity.showBottomNavigation()

                    // Show status bar
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)

                    activity.showFragment("anml")
                }
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun startCamera() {
        Log.d(TAG, "Starting camera...")
        if (context == null || previewView == null) {
            Log.e(TAG, "Context or previewView is null, cannot start camera")
            return
        }

        if (!allPermissionsGranted()) {
            Log.e(TAG, "Camera permission not granted, cannot start camera")
            return
        }

        try {
            cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
            cameraProviderFuture?.addListener({
                try {
                    // Double-check that we're still in a valid state
                    if (context == null || previewView == null || !isAdded) {
                        Log.w(TAG, "Fragment not in valid state when camera provider ready")
                        return@addListener
                    }

                    val cameraProvider = cameraProviderFuture?.get()
                    Log.d(TAG, "Camera provider obtained, binding preview...")
                    cameraProvider?.let { bindPreview(it) }
                } catch (e: ExecutionException) {
                    Log.e(TAG, "Error starting camera", e)
                } catch (e: InterruptedException) {
                    Log.e(TAG, "Error starting camera", e)
                }
            }, ContextCompat.getMainExecutor(requireContext()))
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing camera provider", e)
        }
    }

    private fun bindPreview(cameraProvider: ProcessCameraProvider) {
        Log.d(TAG, "Binding camera preview...")

        if (context == null || previewView == null) {
            Log.e(TAG, "Context or preview view is null, cannot bind camera")
            return
        }

        try {
            val preview = Preview.Builder().build()

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()

            Log.d(TAG, "Setting surface provider...")
            preview.setSurfaceProvider(previewView!!.surfaceProvider)

            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis?.setAnalyzer(ContextCompat.getMainExecutor(requireContext()), MRZAnalyzer())

            // Unbind all use cases before rebinding
            cameraProvider.unbindAll()

            Log.d(TAG, "Binding to lifecycle...")
            val camera = cameraProvider.bindToLifecycle(this as LifecycleOwner, cameraSelector, preview, imageAnalysis)
            Log.d(TAG, "Camera successfully bound!")
        } catch (e: Exception) {
            Log.e(TAG, "Error binding camera", e)
        }
    }

    private inner class MRZAnalyzer : ImageAnalysis.Analyzer {
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

                textRecognizer?.process(image)
                    ?.addOnSuccessListener { visionText ->
                        processMRZ(visionText.text)
                    }
                    ?.addOnFailureListener { e ->
                        Log.e(TAG, "Text recognition failed", e)
                    }
                    ?.addOnCompleteListener {
                        imageProxy.close()
                    }
            } else {
                imageProxy.close()
            }
        }
    }

    private fun processMRZ(text: String) {
        val lines = text.split("\n")

        for (i in 0 until lines.size - 1) {
            val line1 = lines[i].replace("\\s".toRegex(), "").uppercase()
            val line2 = lines[i + 1].replace("\\s".toRegex(), "").uppercase()

            // Look for MRZ pattern
            if (line1.startsWith("P<") && line2.length >= 36) {
                val mrzInfo = parseMRZ(line1, line2)
                if (mrzInfo?.isValid() == true) {
                    Log.d(TAG, "MRZ detected and parsed successfully")
                    onMRZDetected(mrzInfo)
                    return
                }
            }
        }
    }

    private fun parseMRZ(line1: String, line2: String): MRZInfo? {
        return try {
            // Parse passport number, date of birth, and expiry date from line2
            if (line2.length >= 36) {
                val passportNumber = line2.substring(0, 9).replace('<', ' ').trim()
                val dobStr = line2.substring(13, 19) // YYMMDD
                val expiryStr = line2.substring(21, 27) // YYMMDD

                MRZInfo(passportNumber, dobStr, expiryStr)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing MRZ", e)
            null
        }
    }

    private fun onMRZDetected(mrzInfo: MRZInfo) {
        // Stop camera analysis
        imageAnalysis?.clearAnalyzer()

        // Save MRZ data and navigate to manual entry screen
        val prefs = context?.getSharedPreferences("mrz_data", Context.MODE_PRIVATE)
        val editor = prefs?.edit()
        editor?.apply {
            putString("passportNumber", mrzInfo.passportNumber)
            putString("dateOfBirth", mrzInfo.dateOfBirth)
            putString("dateOfExpiry", mrzInfo.dateOfExpiry)
            apply()
        }

        Log.d(TAG, "MRZ data saved, navigating to manual entry")

        // Navigate to manual entry screen with captured data
        activity?.runOnUiThread {
            val activity = activity
            if (activity is com.example.earthwallet.ui.host.HostActivity) {
                // Show status bar before navigating
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)

                activity.showFragment("mrz_input")
            }
        }
    }

    private fun stopCamera() {
        Log.d(TAG, "Attempting to stop camera...")
        try {
            imageAnalysis?.let {
                Log.d(TAG, "Clearing image analyzer")
                it.clearAnalyzer()
                imageAnalysis = null
            }
            if (cameraProviderFuture?.isDone == true) {
                Log.d(TAG, "Unbinding camera provider")
                val cameraProvider = cameraProviderFuture?.get()
                cameraProvider?.unbindAll()
                Log.d(TAG, "Camera successfully stopped and unbound")
            } else {
                Log.w(TAG, "Camera provider future is null or not done")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping camera", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        textRecognizer?.close()
    }

    // Helper class to store MRZ information
    private data class MRZInfo(
        val passportNumber: String,
        val dateOfBirth: String,
        val dateOfExpiry: String
    ) {
        fun isValid(): Boolean {
            return passportNumber.trim().isNotEmpty() &&
                   dateOfBirth.length == 6 &&
                   dateOfExpiry.length == 6
        }
    }
}