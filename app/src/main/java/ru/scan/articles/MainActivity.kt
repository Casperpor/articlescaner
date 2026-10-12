package ru.scan.articles

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.text.InputType
import android.util.Size
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var store: ArticleStore
    private lateinit var previewView: PreviewView
    private lateinit var counter: TextView
    private lateinit var hint: TextView
    private lateinit var scanBtn: MaterialButton
    private lateinit var torchBtn: MaterialButton

    private var camera: Camera? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private lateinit var executor: ExecutorService
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var captureRequested = false
    @Volatile private var dialogOpen = false
    private var lastLiveTs = 0L
    private var liveTop: Candidate? = null
    private var torchOn = false

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else toast("Без доступа к камере приложение не сможет распознавать ценники")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = ArticleStore(this)
        executor = Executors.newSingleThreadExecutor()

        previewView = findViewById(R.id.preview)
        counter = findViewById(R.id.counter)
        hint = findViewById(R.id.hint)
        scanBtn = findViewById(R.id.scanBtn)
        torchBtn = findViewById(R.id.torch)

        scanBtn.setOnClickListener { requestCapture() }
        findViewById<MaterialButton>(R.id.listBtn).setOnClickListener { showList() }
        findViewById<MaterialButton>(R.id.exportBtn).setOnClickListener { exportXlsx() }
        torchBtn.setOnClickListener {
            torchOn = !torchOn
            camera?.cameraControl?.enableTorch(torchOn)
            torchBtn.text = if (torchOn) "Фонарик ✓" else "Фонарик"
        }
        hint.setOnClickListener { liveTop?.let { addArticle(it.value) } }
        setupTapToFocus()
        updateCounter()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            startCamera()
        else permLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
        recognizer.close()
    }

    // ---------------- Камера ----------------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val selector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                ).build()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(selector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { proxy -> analyze(proxy) }
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                torchBtn.visibility = if (camera?.cameraInfo?.hasFlashUnit() == true) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                toast("Не удалось запустить камеру: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTapToFocus() {
        previewView.setOnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_UP) {
                val point = previewView.meteringPointFactory.createPoint(ev.x, ev.y)
                camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                v.performClick()
            }
            true
        }
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        val now = SystemClock.elapsedRealtime()
        val wantCapture = captureRequested
        // Живой режим — не чаще ~1.5 раза в секунду, чтобы не грелся телефон
        if (media == null || dialogOpen || (!wantCapture && now - lastLiveTs < 650)) {
            proxy.close()
            return
        }
        if (wantCapture) captureRequested = false
        lastLiveTs = now

        val rot = proxy.imageInfo.rotationDegrees
        val w = (if (rot % 180 == 0) proxy.width else proxy.height).toFloat()
        val h = (if (rot % 180 == 0) proxy.height else proxy.width).toFloat()
        val input = InputImage.fromMediaImage(media, rot)

        recognizer.process(input)
            .addOnSuccessListener { text ->
                val lines = ArrayList<OcrLine>()
                for (block in text.textBlocks) for (line in block.lines) {
                    val r = line.boundingBox ?: continue
                    lines.add(OcrLine(line.text, r.exactCenterX() / w, r.exactCenterY() / h))
                }
                onResult(ArticleParser.parse(lines, store.learn()), wantCapture)
            }
            .addOnFailureListener { if (wantCapture) onResult(emptyList(), true) }
            .addOnCompleteListener { proxy.close() }
    }

    private fun requestCapture() {
        if (camera == null) { toast("Камера не запущена"); return }
        scanBtn.isEnabled = false
        scanBtn.text = "Распознаю…"
        captureRequested = true
        mainHandler.postDelayed({ resetScanButton() }, 5000)
    }

    private fun resetScanButton() {
        scanBtn.isEnabled = true
        scanBtn.text = "Распознать"
    }

    private fun onResult(cands: List<Candidate>, capture: Boolean) {
        if (capture) {
            resetScanButton()
            if (cands.isEmpty()) toast("Цифры не найдены. Поднесите телефон ближе к ценнику")
            else showChooser(cands)
            return
        }
        if (dialogOpen) return
        val top = cands.firstOrNull { it.hasMarker || it.score >= 90 }
        liveTop = top
        if (top == null) hint.visibility = View.GONE
        else {
            hint.text = "Артикул ${top.value}  ➕"
            hint.visibility = View.VISIBLE
        }
    }

    // ---------------- Выбор артикула ----------------

    private fun showChooser(cands: List<Candidate>) {
        dialogOpen = true
        hint.visibility = View.GONE
        val pad = dp(16)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8), pad, 0)
        }
        box.addView(TextView(this).apply {
            text = "Нажмите на артикул. Удерживайте — чтобы исправить перед добавлением."
            setPadding(0, 0, 0, dp(8))
        })

        lateinit var dialog: AlertDialog
        cands.forEachIndexed { i, c ->
            val recommended = i == 0 && (c.hasMarker || c.score >= 90)
            val btn = if (recommended) MaterialButton(this)
            else MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            val note = when {
                recommended -> "★ скорее всего артикул"
                c.hasMarker -> "рядом «арт.»"
                c.isBarcode -> "похоже на штрихкод"
                c.value.length <= 4 -> "похоже на цену"
                else -> ""
            }
            btn.text = if (note.isEmpty()) c.value else "${c.value}\n$note"
            btn.textSize = 20f
            btn.typeface = Typeface.MONOSPACE
            btn.isAllCaps = false
            btn.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            btn.setOnClickListener {
                dialog.dismiss()
                addArticle(c.value)
            }
            btn.setOnLongClickListener {
                dialog.dismiss()
                showManualInput(c.value)
                true
            }
            box.addView(btn)
        }

        val scroll = android.widget.ScrollView(this).apply { addView(box) }
        dialog = AlertDialog.Builder(this)
            .setTitle("Какое число — артикул?")
            .setView(scroll)
            .setNeutralButton("Ввести вручную") { _, _ -> showManualInput("") }
            .setNegativeButton("Отмена", null)
            .create()
        dialog.setOnDismissListener { dialogOpen = false }
        dialog.show()
    }

    private fun showManualInput(initial: String) {
        dialogOpen = true
        val edit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(initial)
            setSelection(text.length)
            textSize = 22f
            gravity = Gravity.CENTER
        }
        val wrap = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(edit, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Артикул")
            .setView(wrap)
            .setPositiveButton("Добавить") { _, _ ->
                val v = edit.text.toString().filter { it.isDigit() }
                if (v.isNotEmpty()) addArticle(v) else toast("Пустой артикул не добавлен")
            }
            .setNegativeButton("Отмена", null)
            .create()
        dialog.setOnDismissListener { dialogOpen = false }
        dialog.show()
    }

    private fun addArticle(value: String) {
        if (store.contains(value)) {
            val times = store.items.count { it.article == value }
            dialogOpen = true
            val d = AlertDialog.Builder(this)
                .setTitle("Уже в списке")
                .setMessage("Артикул $value уже добавлен ($times раз). Добавить ещё раз?")
                .setPositiveButton("Добавить") { _, _ -> doAdd(value) }
                .setNegativeButton("Нет", null)
                .create()
            d.setOnDismissListener { dialogOpen = false }
            d.show()
        } else doAdd(value)
    }

    private fun doAdd(value: String) {
        store.add(value)
        previewView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        toast("Добавлен: $value")
        hint.visibility = View.GONE
        liveTop = null
        updateCounter()
    }

    private fun updateCounter() {
        counter.text = "Собрано: ${store.items.size}"
    }

    // ---------------- Список ----------------

    private fun showList() {
        if (store.items.isEmpty()) { toast("Список пока пуст"); return }
        val fmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        val labels = store.items.mapIndexed { i, e -> "${i + 1}.  ${e.article}    ${fmt.format(Date(e.time))}" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        dialogOpen = true
        val d = AlertDialog.Builder(this)
            .setTitle("Собрано: ${store.items.size} (нажмите, чтобы удалить)")
            .setAdapter(adapter) { _, which -> confirmDelete(which) }
            .setNeutralButton("Очистить всё") { _, _ -> confirmClear() }
            .setPositiveButton("Закрыть", null)
            .create()
        d.setOnDismissListener { dialogOpen = false }
        d.show()
    }

    private fun confirmDelete(index: Int) {
        val e = store.items.getOrNull(index) ?: return
        AlertDialog.Builder(this)
            .setMessage("Удалить артикул ${e.article}?")
            .setPositiveButton("Удалить") { _, _ ->
                store.removeAt(index)
                updateCounter()
                showList()
            }
            .setNegativeButton("Отмена") { _, _ -> showList() }
            .show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage("Удалить все ${store.items.size} артикулов из списка?")
            .setPositiveButton("Очистить") { _, _ ->
                store.clear()
                updateCounter()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---------------- Экспорт ----------------

    private fun exportXlsx() {
        if (store.items.isEmpty()) { toast("Список пуст — нечего сохранять"); return }
        val name = "Артикулы_" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()) + ".xlsx"
        val mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: run { toast("Не удалось создать файл в «Загрузках»"); return }

        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
        val rows = store.items.mapIndexed { i, e -> listOf((i + 1).toString(), e.article, fmt.format(Date(e.time))) }
        try {
            resolver.openOutputStream(uri)!!.use { out ->
                XlsxWriter.write(out, "Артикулы", listOf("№", "Артикул", "Дата и время"), rows, listOf(6.0, 18.0, 22.0))
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            toast("Ошибка сохранения: ${e.message}")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Файл сохранён")
            .setMessage("Папка «Загрузки» (Download):\n$name\n\nСтрок: ${rows.size}")
            .setPositiveButton("Поделиться") { _, _ ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, "Отправить файл"))
            }
            .setNeutralButton("Очистить список") { _, _ -> confirmClear() }
            .setNegativeButton("OK", null)
            .show()
    }

    // ---------------- Утилиты ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
