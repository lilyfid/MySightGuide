package com.lilianaisuan.mysightguide

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector.ObjectDetectorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.lilianaisuan.mysightguide.databinding.ActivityMainBinding
import com.lilianaisuan.mysightguide.utils.TextToSpeechManager
import com.lilianaisuan.mysightguide.utils.UtteranceCompletionListener
import com.lilianaisuan.mysightguide.utils.imageProxyToBitmap
import com.lilianaisuan.mysightguide.viewmodel.AppState
import com.lilianaisuan.mysightguide.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "MySightGuideApp"

private enum class AppMode {
    IDLE,
    OBJECT_DETECTION,
    TEXT_RECOGNITION,
    SCENE_DESCRIPTION
}

@AndroidEntryPoint
class MainActivity : AppCompatActivity(), UtteranceCompletionListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var ttsManager: TextToSpeechManager
    private lateinit var cameraExecutor: ExecutorService
    private val viewModel: MainViewModel by viewModels()

    private lateinit var textRecognizer: TextRecognizer
    private var objectDetector: ObjectDetector? = null
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var speechRecognizerIntent: Intent

    private lateinit var soundPool: SoundPool
    private var listeningSoundId: Int = 0
    private var processingSoundId: Int = 0
    private var successSoundId: Int = 0

    private var currentMode = AppMode.IDLE
    @Volatile private var captureNextFrame = false
    private var isShuttingDown = false

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) { Log.e(TAG, "SpeechRecognizer Error: $error"); startVoiceRecognition() }
        override fun onResults(results: Bundle?) {
            val spokenText = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.get(0)?.lowercase(Locale.getDefault())
            if (!spokenText.isNullOrBlank()) { processVoiceCommand(spokenText) } else { startVoiceRecognition() }
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) { startCamera() } else { viewModel.setError(getString(R.string.error_camera_permission)) }
    }
    private val microphonePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) { startVoiceRecognition() } else { viewModel.setError(getString(R.string.error_mic_permission)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        setupSpeechRecognizer()
        initSoundPool()
        cameraExecutor = Executors.newSingleThreadExecutor()

        ttsManager = TextToSpeechManager(this) {
            initiateWelcomeSequence()
        }

        val interruptListener = View.OnClickListener {
            ttsManager.stop()
            if (this::speechRecognizer.isInitialized) { speechRecognizer.stopListening() }

            if (currentMode != AppMode.IDLE) {
                playSound(processingSoundId)
                captureNextFrame = true
                viewModel.setBusy(getString(R.string.status_capturing))
            } else {
                startVoiceRecognition()
            }
        }
        binding.root.setOnClickListener(interruptListener)

        binding.root.post {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        loadObjectDetector()
        observeUiState()
    }

    private fun processVoiceCommand(command: String) {
        when {
            command.contains("describe") || command.contains("scene") -> {
                currentMode = AppMode.SCENE_DESCRIPTION
                speak(getString(R.string.prompt_scene_description))
            }
            command.contains("detect") || command.contains("what's around") -> {
                currentMode = AppMode.OBJECT_DETECTION
                speak(getString(R.string.prompt_object_detection))
            }
            command.contains("read") || command.contains("text") -> {
                currentMode = AppMode.TEXT_RECOGNITION
                speak(getString(R.string.prompt_text_reader))
            }
            command.contains("capture") || command.contains("now") || command.contains("go") -> {
                if (currentMode != AppMode.IDLE) {
                    playSound(processingSoundId)
                    captureNextFrame = true
                    viewModel.setBusy(getString(R.string.status_capturing))
                } else {
                    speak(getString(R.string.prompt_choose_mode))
                    startVoiceRecognition()
                }
            }
            command.contains("stop") || command.contains("cancel") -> {
                currentMode = AppMode.IDLE
                viewModel.setReadyState()
                speak(getString(R.string.response_analysis_stopped))
            }
            command.contains("shutdown") || command.contains("close app") || command.contains("exit") -> {
                isShuttingDown = true
                speak(getString(R.string.response_shutdown))
            }
            else -> {
                speak(getString(R.string.response_unknown_command))
                startVoiceRecognition()
            }
        }
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                binding.progressBar.visibility = if (state is AppState.Busy) View.VISIBLE else View.GONE

                when (state) {
                    is AppState.Listening -> binding.statusTextView.text = getString(R.string.status_listening)
                    is AppState.Ready -> binding.statusTextView.text = getString(R.string.status_ready)
                    is AppState.Busy -> binding.statusTextView.text = state.message
                    is AppState.Result -> {
                        playSound(successSoundId)
                        binding.statusTextView.text = getString(R.string.status_speaking)
                        speak(state.textToSpeak)
                    }
                    is AppState.Error -> {
                        val errorMessage = getString(R.string.error_with_message, state.message)
                        binding.statusTextView.text = errorMessage
                        speak(errorMessage)
                    }
                }
            }
        }
    }

    override fun onUtteranceCompleted() {
        runOnUiThread {
            if (isShuttingDown) {
                finish()
            } else {
                startVoiceRecognition()
            }
        }
    }

    private fun runInference(bitmap: Bitmap) {
        val imageWidth = bitmap.width
        when (currentMode) {
            AppMode.OBJECT_DETECTION -> {
                val detector = objectDetector ?: return
                try {
                    val mpImage = BitmapImageBuilder(bitmap).build()
                    val detectionResult = detector.detect(mpImage)
                    val detections = detectionResult?.detections() ?: emptyList()

                    if (detections.isEmpty()) {
                        viewModel.setResult(getString(R.string.response_no_objects_found))
                        return
                    }

                    val resultStrings = detections.mapNotNull {
                        val category = it.categories().firstOrNull() ?: return@mapNotNull null
                        val label = category.categoryName()
                        val direction = getObjectDirection(it.boundingBox(), imageWidth)
                        "$label $direction"
                    }

                    val fullDescription = "I see " + resultStrings.joinToString(separator = ", and ")
                    viewModel.setResult(fullDescription)
                } catch (e: Exception) { Log.e(TAG, "Object detection failed", e); viewModel.setError("Detection failed.") }
            }
            AppMode.SCENE_DESCRIPTION -> {
                // With the new multimodal SDK, we just pass the bitmap directly to the ViewModel.
                // The ViewModel and Service will handle the rest with Gemma 3N.
                viewModel.generateSceneDescription(bitmap)
            }
            AppMode.TEXT_RECOGNITION -> {
                val image = InputImage.fromBitmap(bitmap, 0)
                textRecognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        if (visionText.text.isNotBlank()) {
                            viewModel.setResult(visionText.text)
                        } else {
                            viewModel.setResult(getString(R.string.response_no_text_found))
                        }
                    }
                    .addOnFailureListener { e -> Log.e(TAG, "Text recognition failed", e); viewModel.setError("Text reading failed.") }
            }
            AppMode.IDLE -> { /* Do nothing */ }
        }
        currentMode = AppMode.IDLE
    }

    private fun getObjectDirection(box: android.graphics.RectF, imageWidth: Int): String {
        val objectCenter = box.centerX()
        val leftBoundary = imageWidth * 0.40f
        val rightBoundary = imageWidth * 0.60f

        return when {
            objectCenter < leftBoundary -> "to your left"
            objectCenter > rightBoundary -> "to your right"
            else -> "in front of you"
        }
    }

    private fun loadObjectDetector() {
        val modelName = "efficientdet_lite2.tflite"
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val options = ObjectDetectorOptions.builder().setBaseOptions(BaseOptions.builder().setModelAssetPath(modelName).build()).setMaxResults(5).setScoreThreshold(0.65f).build()
                objectDetector = ObjectDetector.createFromOptions(applicationContext, options)
            } catch (e: Exception) { Log.e(TAG, "Failed to load object detector", e); viewModel.setError(getString(R.string.error_object_detector_load)) }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(binding.cameraPreview.surfaceProvider) }
            val imageAnalysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888).build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                try {
                    if (captureNextFrame) {
                        captureNextFrame = false
                        val bitmap = imageProxyToBitmap(imageProxy)
                        bitmap?.let { runInference(it) }
                    }
                } finally {
                    imageProxy.close()
                }
            }
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            val errorMessage = getString(R.string.error_speech_recognition_na)
            Log.e(TAG, errorMessage); viewModel.setError(errorMessage)
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this); speechRecognizer.setRecognitionListener(recognitionListener)
        speechRecognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
    }

    private fun initiateWelcomeSequence() {
        lifecycleScope.launch { speak("Welcome to My Sight Guide."); delay(2000); startVoiceRecognition() }
    }

    private fun startVoiceRecognition() {
        if (!this::speechRecognizer.isInitialized || isShuttingDown) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            runOnUiThread {
                viewModel.setListeningState()
                playSound(listeningSoundId)
                speechRecognizer.startListening(speechRecognizerIntent)
            }
        } else {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun speak(text: String) { ttsManager.speak(text) }

    private fun initSoundPool() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(audioAttributes).build()
        listeningSoundId = soundPool.load(this, R.raw.sound_listening, 1)
        processingSoundId = soundPool.load(this, R.raw.sound_processing, 1)
        successSoundId = soundPool.load(this, R.raw.sound_success, 1)
    }

    private fun playSound(soundId: Int) {
        if (soundId != 0) {
            soundPool.play(soundId, 1.0f, 1.0f, 1, 0, 1.0f)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsManager.shutdown()
        cameraExecutor.shutdown()
        objectDetector?.close()
        textRecognizer.close()
        soundPool.release()
        if (this::speechRecognizer.isInitialized) { speechRecognizer.destroy() }
    }
}