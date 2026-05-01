package com.example.voicecommandsapp

import android.Manifest
import android.os.*
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.*
import java.io.File
import android.graphics.Bitmap
import android.graphics.BitmapFactory

class MainActivity : AppCompatActivity(), RecognitionListener {

    companion object {
        const val SPRITE_SIZE = 140
    }

    // --- UI ---
    private lateinit var textSpeech: TextView
    private lateinit var textCommand: TextView
    private lateinit var textCharacterState: TextView
    private lateinit var character: ImageView
    private lateinit var btnStartStop: Button

    // --- VOSK ---
    private lateinit var speechService: SpeechService
    private lateinit var model: Model
    private var isModelReady = false
    private var isListening = false

    // --- animation ---
    private val handler = Handler(Looper.getMainLooper())
    private val animHandler = Handler(Looper.getMainLooper())
    private var animRunnable: Runnable? = null
    private var clearRunnable: Runnable? = null

    private var currentState = "idle"

    private var idleFrame = 0
    private var runFrame = 0
    private var attackFrame = 0
    private var rollFrame = 0

    // --- movement ---
    private var posX = 0f
    private var screenWidth = 0f
    private var facingRight = true

    // --- frames ---
    private val idleFrames = arrayOf(
        R.drawable.sonic_idle0,
        R.drawable.sonic_idle1,
        R.drawable.sonic_idle2,
        R.drawable.sonic_idle3,
        R.drawable.sonic_idle4,
        R.drawable.sonic_idle5,
        R.drawable.sonic_idle6,
        R.drawable.sonic_idle7
    )

    private val runFrames = arrayOf(
        R.drawable.sonic_run4_0,
        R.drawable.sonic_run4_1,
        R.drawable.sonic_run4_2,
        R.drawable.sonic_run4_3,
        R.drawable.sonic_run4_4,
        R.drawable.sonic_run4_5,
        R.drawable.sonic_run4_6,
        R.drawable.sonic_run4_7
    )

    private val attackFrames = arrayOf(
        R.drawable.sonic_handattack2,
        R.drawable.sonic_handattack3,
        R.drawable.sonic_handattack4,
        R.drawable.sonic_handattack5,
        R.drawable.sonic_handattack6,
        R.drawable.sonic_handattack7
    )

    private val rollFrames = arrayOf(
        R.drawable.roll_0,
        R.drawable.roll_1,
        R.drawable.roll_2,
        R.drawable.roll_3
    )

    // ---------------- INIT ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        character = findViewById(R.id.character)
        textSpeech = findViewById(R.id.textSpeech)
        textCommand = findViewById(R.id.textCommand)
        textCharacterState = findViewById(R.id.textCharacterState)
        btnStartStop = findViewById(R.id.btnStartStop)

        val displayMetrics = resources.displayMetrics
        screenWidth = displayMetrics.widthPixels.toFloat()

        character.scaleType = ImageView.ScaleType.CENTER
        character.adjustViewBounds = false

        btnStartStop.setOnClickListener {
            if (!isModelReady) {
                textCommand.text = "⏳ Модель загружается..."
                return@setOnClickListener
            }

            if (isListening) {
                speechService.stop()
                btnStartStop.text = "START"
                isListening = false
            } else {
                startRecognition()
                btnStartStop.text = "STOP"
                isListening = true
            }
        }

        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            1
        )

        // load model
        Thread {
            val modelDir = File(filesDir, "vosk-model-small-ru-0.22")
            if (modelDir.exists()) modelDir.deleteRecursively()

            copyAssets("vosk-model-small-ru-0.22", modelDir)

            model = Model(modelDir.absolutePath)
            isModelReady = true
        }.start()

        startAnimationLoop()
    }

    // ---------------- ANIMATION LOOP ----------------

    private fun startAnimationLoop() {
        animRunnable?.let { animHandler.removeCallbacks(it) }

        animRunnable = object : Runnable {
            override fun run() {
                when (currentState) {
                    "idle" -> playIdle()
                    "run" -> playRun()
                    "attack" -> playAttack()
                    "jump" -> playJump()
                }
                animHandler.postDelayed(this, 120)
            }
        }

        animHandler.post(animRunnable!!)
    }

    // ---------------- SPRITE RENDER ----------------

    private fun normalizeFrame(frameId: Int) {
        val bitmap = BitmapFactory.decodeResource(resources, frameId)

        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            SPRITE_SIZE,
            SPRITE_SIZE,
            false
        )

        character.setImageBitmap(scaled)
    }

    // ---------------- ANIMATIONS ----------------

    private fun playIdle() {
        idleFrame = (idleFrame + 1) % idleFrames.size
        normalizeFrame(idleFrames[idleFrame])
    }

    private fun playRun() {
        runFrame = (runFrame + 1) % runFrames.size
        normalizeFrame(runFrames[runFrame])
    }

    private fun playAttack() {
        attackFrame = (attackFrame + 1) % attackFrames.size
        normalizeFrame(attackFrames[attackFrame])
    }

    private fun playJump() {
        rollFrame = (rollFrame + 1) % rollFrames.size
        normalizeFrame(rollFrames[rollFrame])
    }

    // ---------------- MOVEMENT ----------------

    private fun runRight() {
        currentState = "run"
        facingRight = true
        character.scaleX = 1f

        posX += 20f
        clampPosition()
    }

    private fun runLeft() {
        currentState = "run"
        facingRight = false
        character.scaleX = -1f

        posX -= 20f
        clampPosition()
    }

    private fun clampPosition() {
        val maxX = screenWidth - SPRITE_SIZE

        if (posX < 0f) posX = 0f
        if (posX > maxX) posX = maxX

        character.translationX = posX
    }

    private fun jumpCharacter() {
        currentState = "jump"

        character.animate()
            .translationYBy(-250f)
            .setDuration(180)
            .withEndAction {
                character.animate()
                    .translationY(0f)
                    .setDuration(180)
                    .start()
            }
            .start()

        handler.postDelayed({
            currentState = "idle"
        }, 500)
    }

    // ---------------- COMMANDS ----------------

    private fun handleCommand(text: String) {

        val command = text.lowercase().trim()

        when {

            command.contains("стой") -> {
                currentState = "idle"
                animateText(textCharacterState)
            }

            command.contains("вперёд") ||
                    command.contains("вперед") ||
                    command.contains("беги") -> {
                runRight()
                animateText(textCharacterState)
            }

            command.contains("назад") ||
                    command.contains("влево") -> {
                runLeft()
                animateText(textCharacterState)
            }

            command.contains("прыж") ||
                    command.contains("рол") -> {
                jumpCharacter()
                animateText(textCharacterState)
            }

            command.contains("удар") -> {
                currentState = "attack"
                animateText(textCharacterState)
            }
        }
    }

    private fun animateText(view: TextView) {
        view.animate()
            .scaleX(1.3f)
            .scaleY(1.3f)
            .setDuration(120)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(120)
                    .start()
            }
            .start()
    }

    // ---------------- VOSK ----------------

    private fun startRecognition() {
        val recognizer = Recognizer(model, 16000.0f)
        speechService = SpeechService(recognizer, 16000.0f)
        speechService.startListening(this)

        textCommand.text = "🎤 Слушаю..."
    }

    override fun onPartialResult(hypothesis: String?) {
        textSpeech.text = extractText(hypothesis)
    }

    override fun onResult(hypothesis: String?) {
        val text = extractText(hypothesis)
        textSpeech.text = text
        handleCommand(text)
        keepTextVisible()
    }

    override fun onFinalResult(hypothesis: String?) {
        val text = extractText(hypothesis)
        textSpeech.text = text
        handleCommand(text)
        keepTextVisible()
    }

    override fun onError(exception: Exception?) {}
    override fun onTimeout() {}

    override fun onDestroy() {
        super.onDestroy()
        if (::speechService.isInitialized) speechService.stop()
    }

    private fun keepTextVisible() {
        clearRunnable?.let { handler.removeCallbacks(it) }

        clearRunnable = Runnable {
            textSpeech.text = ""
        }

        handler.postDelayed(clearRunnable!!, 3000)
    }

    private fun extractText(json: String?): String {
        if (json == null) return ""

        val partial = """"partial"\s*:\s*"([^"]*)"""".toRegex()
        val full = """"text"\s*:\s*"([^"]*)"""".toRegex()

        return partial.find(json)?.groupValues?.get(1)
            ?: full.find(json)?.groupValues?.get(1)
            ?: ""
    }

    // ---------------- ASSETS ----------------

    private fun copyAssets(assetPath: String, destPath: File) {
        val files = assets.list(assetPath) ?: return

        if (!destPath.exists()) destPath.mkdirs()

        for (file in files) {
            val inPath = "$assetPath/$file"
            val outFile = File(destPath, file)

            val subFiles = assets.list(inPath)

            if (subFiles != null && subFiles.isNotEmpty()) {
                copyAssets(inPath, outFile)
            } else {
                assets.open(inPath).use { input ->
                    outFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }
}
