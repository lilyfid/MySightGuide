package com.lilianaisuan.mysightguide

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.get
import androidx.lifecycle.lifecycleScope
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector.ObjectDetectorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.lilianaisuan.mysightguide.databinding.ActivityMainBinding
import com.lilianaisuan.mysightguide.utils.ConnectivityHelper
import com.lilianaisuan.mysightguide.utils.TextToSpeechManager
import com.lilianaisuan.mysightguide.utils.UtteranceCompletionListener
import com.lilianaisuan.mysightguide.utils.imageProxyToBitmap
import com.lilianaisuan.mysightguide.viewmodel.AppState
import com.lilianaisuan.mysightguide.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.pow
import kotlin.math.sqrt

private const val TAG = "MySightGuideApp"

private enum class AppMode {
    IDLE,
    SCENE_DESCRIPTION,
    OBJECT_DETECTION,
    TEXT_RECOGNITION,
    CURRENCY_DETECTION,
    ASSISTANT_QUERY,
    AWAITING_NAME // <-- THIS IS THE FIX
}

class MainActivity : AppCompatActivity(), UtteranceCompletionListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var ttsManager: TextToSpeechManager
    private lateinit var cameraExecutor: ExecutorService
    private val viewModel: MainViewModel by viewModels()

    private lateinit var connectivityHelper: ConnectivityHelper
    private var onlineGenerativeModel: GenerativeModel? = null
    private var offlineObjectDetector: ObjectDetector? = null
    private var isOfflineDetectorLoaded = false

    private var lastCapturedBitmap: Bitmap? = null

    private lateinit var textRecognizer: TextRecognizer
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var speechRecognizerIntent: Intent

    private lateinit var soundPool: SoundPool
    private var listeningSoundId: Int = 0
    private var processingSoundId: Int = 0
    private var successSoundId: Int = 0

    private var currentMode = AppMode.IDLE
    @Volatile private var captureNextFrame = false
    private var isShuttingDown = false

    private var isVoiceOfflineFailed = false
    private lateinit var gestureDetector: GestureDetector

    private var isOnboardingNamePrompt = false

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {
            Log.e(TAG, "SpeechRecognizer Error: $error")
            if (!connectivityHelper.isOnline()) {
                isVoiceOfflineFailed = true
                speak(getString(R.string.response_voice_command_failed_offline))
                lifecycleScope.launch {
                    delay(4000)
                    speak(getString(R.string.prompt_tap_only_mode))
                }
            } else {
                isVoiceOfflineFailed = false
                lifecycleScope.launch {
                    delay(500)
                    startVoiceRecognition()
                }
            }
        }
        override fun onResults(results: Bundle?) {
            isVoiceOfflineFailed = false
            val spokenText = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.lowercase(Locale.getDefault())
            if (!spokenText.isNullOrBlank()) {
                processVoiceCommand(spokenText)
            } else {
                startVoiceRecognition()
            }
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) startCamera() else viewModel.setError("Camera permission is required.")
    }
    private val microphonePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) startVoiceRecognition() else viewModel.setError("Microphone permission is required.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        connectivityHelper = ConnectivityHelper(applicationContext)
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        setupSpeechRecognizer()
        initSoundPool()
        cameraExecutor = Executors.newSingleThreadExecutor()
        ttsManager = TextToSpeechManager(this) { initiateWelcomeSequence() }

        setupGestureDetector()
        binding.root.setOnTouchListener { view, event ->
            gestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) {
                view.performClick()
            }
            true
        }

        binding.root.post {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        initializeModels()
        observeUiState()
    }

    private fun initializeModels() {
        if (BuildConfig.GEMINI_API_KEY.isNotBlank()) {
            onlineGenerativeModel = GenerativeModel(
                modelName = "gemini-1.5-flash",
                apiKey = BuildConfig.GEMINI_API_KEY
            )
        }
        loadOfflineObjectDetector()
    }

    private fun processVoiceCommand(command: String) {
        if (currentMode == AppMode.AWAITING_NAME) {
            val userName = command.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            val prefs = getSharedPreferences("MySightGuidePrefs", MODE_PRIVATE)
            prefs.edit {
                putString("userName", userName)
                putBoolean("isFirstLaunch", false)
            }
            currentMode = AppMode.IDLE
            speak(getString(R.string.response_name_saved, userName))
            return
        }

        val mainCommands = listOf("describe", "scene", "see", "what is there", "detect", "color", "read", "text", "money", "currency", "capture", "go", "now", "stop", "cancel", "shutdown", "exit", "close app", "help", "what can i say")
        val isFollowUpQuestion = lastCapturedBitmap != null && mainCommands.none { command.contains(it) }

        if (isFollowUpQuestion) {
            runFollowUpQuestion(command)
            return
        }

        lastCapturedBitmap = null

        when {
            "describe" in command || "scene" in command || "see" in command || "what is there" in command || "what's in front" in command -> {
                currentMode = AppMode.SCENE_DESCRIPTION
                speak(getString(R.string.prompt_scene_description))
            }
            "detect" in command || "what's around" in command || "color" in command || "what colour" in command -> {
                if (isOfflineDetectorLoaded) {
                    currentMode = AppMode.OBJECT_DETECTION
                    speak(getString(R.string.prompt_object_detection))
                } else {
                    speak(getString(R.string.error_object_detector_not_ready))
                    startVoiceRecognition()
                }
            }
            "read" in command || "text" in command -> {
                currentMode = AppMode.TEXT_RECOGNITION
                speak(getString(R.string.prompt_text_reader_detailed))
            }
            "money" in command || "currency" in command -> {
                currentMode = AppMode.CURRENCY_DETECTION
                speak(getString(R.string.prompt_currency_detection))
            }
            "help" in command || "what can i say" in command -> {
                speak(getString(R.string.response_help))
            }
            "capture" in command || "go" in command || "now" in command -> {
                if (currentMode != AppMode.IDLE) {
                    playSound(processingSoundId)
                    captureNextFrame = true
                    viewModel.setBusy("Capturing image...")
                } else {
                    speak(getString(R.string.prompt_choose_mode))
                    startVoiceRecognition()
                }
            }
            "stop" in command || "cancel" in command -> {
                ttsManager.stop()
                currentMode = AppMode.IDLE
                viewModel.setReadyState()
                speak(getString(R.string.response_analysis_stopped))
            }
            "shutdown" in command || "exit" in command || "close app" in command -> {
                isShuttingDown = true
                speak(getString(R.string.response_shutdown))
            }
            else -> {
                currentMode = AppMode.ASSISTANT_QUERY
                runAssistantQuery(command)
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
                        speak(state.textToSpeak)
                        binding.statusTextView.text = getString(R.string.status_speaking)
                    }
                    is AppState.Error -> {
                        val errorMessage = getString(R.string.error_with_message, state.message)
                        speak(errorMessage)
                        binding.statusTextView.text = errorMessage
                    }
                }
            }
        }
    }

    override fun onUtteranceCompleted() {
        runOnUiThread {
            if (isShuttingDown) {
                finish()
            } else if (isOnboardingNamePrompt) {
                isOnboardingNamePrompt = false
                currentMode = AppMode.AWAITING_NAME
                speak(getString(R.string.prompt_ask_for_name))
            } else if (!isVoiceOfflineFailed) {
                startVoiceRecognition()
            }
        }
    }

    private fun runInference(bitmap: Bitmap) {
        viewModel.setBusy("Processing image...")
        when (currentMode) {
            AppMode.SCENE_DESCRIPTION -> runSceneDescription(bitmap)
            AppMode.OBJECT_DETECTION -> runObjectAndColorDetection(bitmap)
            AppMode.TEXT_RECOGNITION -> runTextRecognition(bitmap)
            AppMode.CURRENCY_DETECTION -> runCurrencyDetection(bitmap)
            else -> {}
        }
        currentMode = AppMode.IDLE
    }

    private fun runSceneDescription(bitmap: Bitmap) {
        this.lastCapturedBitmap = bitmap

        lifecycleScope.launch(Dispatchers.IO) {
            if (connectivityHelper.isOnline() && onlineGenerativeModel != null) {
                runOnUiThread { viewModel.setBusy("Connecting to online AI...") }
                try {
                    val prompt = "You are MySightGuide. Describe this scene for a visually impaired user in a short, helpful sentence."
                    val inputContent = content {
                        image(bitmap)
                        text(prompt)
                    }
                    val response = onlineGenerativeModel!!.generateContent(inputContent)
                    val resultText = (response.text ?: "Could not generate a description.") + " " + getString(R.string.response_scene_described_follow_up)
                    runOnUiThread { viewModel.setResult(resultText) }
                } catch (e: Exception) {
                    Log.e(TAG, "Online Gemma inference failed, falling back to offline.", e)
                    runOfflineSceneDescription(bitmap)
                }
            } else {
                runOfflineSceneDescription(bitmap)
            }
        }
    }

    private fun runFollowUpQuestion(prompt: String) {
        val image = lastCapturedBitmap
        if (image == null) {
            viewModel.setError("Something went wrong, please describe the scene again.")
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            if (connectivityHelper.isOnline() && onlineGenerativeModel != null) {
                runOnUiThread { viewModel.setBusy("Answering your question...") }
                try {
                    val inputContent = content {
                        image(image)
                        text(prompt)
                    }
                    val response = onlineGenerativeModel!!.generateContent(inputContent)
                    runOnUiThread { viewModel.setResult(response.text ?: "I am not sure how to answer that.") }
                } catch (e: Exception) {
                    Log.e(TAG, "Follow-up question failed: ${e.message}", e)
                    runOnUiThread { viewModel.setError("I'm sorry, I couldn't answer that question.") }
                }
            } else {
                runOnUiThread { viewModel.setResult("Follow-up questions require an internet connection.") }
            }
        }
    }

    private fun runCurrencyDetection(bitmap: Bitmap) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (connectivityHelper.isOnline() && onlineGenerativeModel != null) {
                runOnUiThread { viewModel.setBusy("Identifying currency...") }
                try {
                    val prompt = "Identify the currency and denomination of the banknote in this image. If you are not sure, say 'Unknown currency'."
                    val inputContent = content {
                        image(bitmap)
                        text(prompt)
                    }
                    val response = onlineGenerativeModel!!.generateContent(inputContent)
                    runOnUiThread { viewModel.setResult(response.text ?: "Could not identify the currency.") }
                } catch (e: Exception) {
                    Log.e(TAG, "Online currency detection failed: ${e.message}", e)
                    runOnUiThread { viewModel.setError("Failed to identify currency.") }
                }
            } else {
                runOnUiThread { viewModel.setResult(getString(R.string.response_currency_requires_internet)) }
            }
        }
    }

    private fun runAssistantQuery(command: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (connectivityHelper.isOnline() && onlineGenerativeModel != null) {
                runOnUiThread { viewModel.setBusy(getString(R.string.status_thinking)) }
                try {
                    val prompt = "You are MySightGuide, a friendly and helpful AI assistant. Provide a direct and concise answer to the user's question. Do not add any introductory phrases like 'Of course' or 'Certainly'. Never use the abbreviation 'e.g.'; instead, use the full phrase 'for example'. Now, answer this question: '$command'"
                    val response = onlineGenerativeModel!!.generateContent(prompt)
                    runOnUiThread { viewModel.setResult(response.text ?: "I'm sorry, I don't have an answer for that.") }
                } catch (e: Exception) {
                    Log.e(TAG, "Assistant query failed: ${e.message}", e)
                    runOnUiThread { viewModel.setError("I'm sorry, I couldn't process your request.") }
                }
            } else {
                runOnUiThread { viewModel.setResult(getString(R.string.response_assistant_requires_internet)) }
            }
        }
    }

    private fun runOfflineSceneDescription(bitmap: Bitmap) {
        val detector = offlineObjectDetector
        if (detector == null || !isOfflineDetectorLoaded) {
            runOnUiThread { viewModel.setError("Offline model is not available.") }
            return
        }
        runOnUiThread { viewModel.setBusy("No internet. Using offline mode...") }
        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val detectionResult = detector.detect(mpImage)
            val detections = detectionResult?.detections() ?: emptyList()

            if (detections.isEmpty()) {
                runOnUiThread { viewModel.setResult(getString(R.string.response_no_objects_found)) }
                return
            }

            val resultStrings = detections.mapNotNull { detection ->
                val category = detection.categories().firstOrNull() ?: return@mapNotNull null
                val label = category.categoryName()
                val direction = getObjectDirection(detection.boundingBox(), bitmap.width)
                "$label $direction"
            }

            val fullDescription = "Offline mode. I see " + resultStrings.joinToString(separator = ", and ")
            runOnUiThread { viewModel.setResult(fullDescription) }
        } catch (e: Exception) {
            Log.e(TAG, "Offline object detection failed", e)
            runOnUiThread { viewModel.setError("Offline detection failed.") }
        }
    }

    private fun runTextRecognition(bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)
        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                val text = visionText.text.replace("\n", " ").trim()
                viewModel.setResult(text.ifEmpty { getString(R.string.response_no_text_found) })
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Text recognition failed: ${e.message}", e)
                viewModel.setError("Text recognition failed.")
            }
    }

    private fun runObjectAndColorDetection(bitmap: Bitmap) {
        val detector = offlineObjectDetector ?: return
        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val detectionResult = detector.detect(mpImage)
            val detections = detectionResult?.detections() ?: emptyList()

            if (detections.isEmpty()) {
                viewModel.setResult(getString(R.string.response_no_objects_found))
                return
            }

            val resultStrings = detections.mapNotNull { detection ->
                val category = detection.categories().firstOrNull() ?: return@mapNotNull null
                val label = category.categoryName()
                val direction = getObjectDirection(detection.boundingBox(), bitmap.width)

                val box = detection.boundingBox()
                if (box.width() > 0 && box.height() > 0 && box.right <= bitmap.width && box.bottom <= bitmap.height) {
                    val croppedBitmap = Bitmap.createBitmap(
                        bitmap,
                        box.left.toInt(),
                        box.top.toInt(),
                        box.width().toInt(),
                        box.height().toInt()
                    )
                    val colorName = getAverageColorName(croppedBitmap)
                    "$colorName $label $direction"
                } else {
                    "$label $direction"
                }
            }

            val fullDescription = "I see " + resultStrings.joinToString(separator = ", and ")
            viewModel.setResult(fullDescription)
        } catch (e: Exception) {
            Log.e(TAG, "Object and color detection failed", e)
            viewModel.setError("Detection failed.")
        }
    }

    private fun getObjectDirection(box: RectF, imageWidth: Int): String {
        val objectCenter = box.centerX()
        val leftBoundary = imageWidth * 0.40f
        val rightBoundary = imageWidth * 0.60f

        return when {
            objectCenter < leftBoundary -> "to your left"
            objectCenter > rightBoundary -> "to your right"
            else -> "in front of you"
        }
    }

    private fun loadOfflineObjectDetector() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val modelName = "efficientdet_lite2.tflite"
                val options = ObjectDetectorOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(modelName).build())
                    .setMaxResults(5)
                    .setScoreThreshold(0.65f)
                    .build()
                offlineObjectDetector = ObjectDetector.createFromOptions(applicationContext, options)
                isOfflineDetectorLoaded = true
                Log.i(TAG, "MediaPipe Offline Object detector loaded successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load offline object detector: ${e.message}", e)
                runOnUiThread {
                    viewModel.setError(getString(R.string.error_object_detector_load))
                }
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(binding.cameraPreview.surfaceProvider) }
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                imageProxy.use { proxy ->
                    if (captureNextFrame) {
                        captureNextFrame = false
                        val bitmap = imageProxyToBitmap(proxy)
                        bitmap?.let { bmp -> runInference(bmp) }
                    }
                }
            }
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            viewModel.setError(getString(R.string.error_speech_recognition_na))
            return
        }
        speechRecognizer = try {
            SpeechRecognizer.createSpeechRecognizer(this, ComponentName.unflattenFromString("com.google.android.googlequicksearchbox/com.google.android.voicesearch.serviceapi.GoogleRecognitionService"))
        } catch (e: Exception) {
            SpeechRecognizer.createSpeechRecognizer(this)
        }
        speechRecognizer.setRecognitionListener(recognitionListener)
        speechRecognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
    }

    private fun startVoiceRecognition() {
        if (!::speechRecognizer.isInitialized || isShuttingDown) return
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

    private fun speak(text: String) {
        ttsManager.speak(text)
    }

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

    private fun initiateWelcomeSequence() {
        val prefs = getSharedPreferences("MySightGuidePrefs", MODE_PRIVATE)
        prefs.edit(commit = true) {
            val isFirstLaunch = prefs.getBoolean("isFirstLaunch", true)
            if (isFirstLaunch) {
                isOnboardingNamePrompt = true
                speak(getString(R.string.welcome_onboarding))
            } else {
                val userName = prefs.getString("userName", null)
                if (userName != null) {
                    speak(getString(R.string.welcome_back, userName))
                } else {
                    speak("Welcome to My Sight Guide.")
                }
            }
        }
    }

    private fun getAverageColorName(bitmap: Bitmap): String {
        var totalRed = 0L
        var totalGreen = 0L
        var totalBlue = 0L
        val pixelCount = bitmap.width * bitmap.height

        if (pixelCount == 0) return "Unknown"

        for (x in 0 until bitmap.width) {
            for (y in 0 until bitmap.height) {
                val pixel = bitmap[x, y]
                totalRed += Color.red(pixel)
                totalGreen += Color.green(pixel)
                totalBlue += Color.blue(pixel)
            }
        }

        val avgRed = (totalRed / pixelCount).toInt()
        val avgGreen = (totalGreen / pixelCount).toInt()
        val avgBlue = (totalBlue / pixelCount).toInt()

        return getColorNameFromRgb(avgRed, avgGreen, avgBlue)
    }

    private fun getColorNameFromRgb(r: Int, g: Int, b: Int): String {
        val colorList = arrayListOf(
            ColorName("Red", 255, 0, 0),
            ColorName("Green", 0, 128, 0),
            ColorName("Blue", 0, 0, 255),
            ColorName("Yellow", 255, 255, 0),
            ColorName("Cyan", 0, 255, 255),
            ColorName("Magenta", 255, 0, 255),
            ColorName("White", 255, 255, 255),
            ColorName("Black", 0, 0, 0),
            ColorName("Gray", 128, 128, 128),
            ColorName("Orange", 255, 165, 0),
            ColorName("Purple", 128, 0, 128),
            ColorName("Brown", 165, 42, 42),
            ColorName("Pink", 255, 192, 203)
        )

        var closestColor = colorList[0]
        var minDistance = Double.MAX_VALUE

        for (color in colorList) {
            val distance = sqrt(
                (r - color.r).toDouble().pow(2.0) +
                        (g - color.g).toDouble().pow(2.0) +
                        (b - color.b).toDouble().pow(2.0)
            )
            if (distance < minDistance) {
                minDistance = distance
                closestColor = color
            }
        }
        return closestColor.name
    }

    private data class ColorName(val name: String, val r: Int, val g: Int, val b: Int)

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (isVoiceOfflineFailed) {
                    currentMode = AppMode.TEXT_RECOGNITION
                    playSound(processingSoundId)
                    captureNextFrame = true
                    viewModel.setBusy("Capturing image...")
                } else {
                    ttsManager.stop()
                    if (::speechRecognizer.isInitialized) speechRecognizer.stopListening()
                    if (currentMode != AppMode.IDLE) {
                        playSound(processingSoundId)
                        captureNextFrame = true
                        viewModel.setBusy("Capturing image...")
                    } else {
                        startVoiceRecognition()
                    }
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isVoiceOfflineFailed) {
                    if (isOfflineDetectorLoaded) {
                        currentMode = AppMode.OBJECT_DETECTION
                        playSound(processingSoundId)
                        captureNextFrame = true
                        viewModel.setBusy("Capturing image...")
                    } else {
                        speak(getString(R.string.error_object_detector_not_ready))
                    }
                    return true
                }
                return false
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsManager.shutdown()
        cameraExecutor.shutdown()
        textRecognizer.close()
        offlineObjectDetector?.close()
        soundPool.release()
        if (::speechRecognizer.isInitialized) {
            speechRecognizer.destroy()
        }
    }
}