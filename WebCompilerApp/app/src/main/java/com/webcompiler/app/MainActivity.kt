package com.webcompiler.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.webcompiler.app.databinding.ActivityMainBinding
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val engine = CompilerEngine(this)
    private var selectedFileUri: Uri? = null
    private var selectedFileName: String? = null
    private var isBuilding = false
    private var selectedIcon: String? = null

    private val iconColors = listOf(
        "#2196F3" to "Blue",
        "#F44336" to "Red",
        "#4CAF50" to "Green",
        "#FF9800" to "Orange",
        "#9C27B0" to "Purple",
        "#009688" to "Teal"
    )

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedFileUri = it
            selectedFileName = it.lastPathSegment ?: "file"
            binding.codeInput.setText("File selected: ${selectedFileName}")
            binding.codeInput.isEnabled = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        engine.extractBundledAssets()
        setupIconPicker()

        binding.selectFileBtn.setOnClickListener {
            filePicker.launch("*/*")
        }

        binding.buildBtn.setOnClickListener {
            if (!isBuilding) startBuild()
        }

        binding.saveApkBtn.setOnClickListener {
            saveApk()
        }
    }

    private fun setupIconPicker() {
        val container = binding.iconContainer
        val size = 56
        val margin = 8

        for ((index, colorInfo) in iconColors.withIndex()) {
            val (color, name) = colorInfo
            val imageView = ImageView(this)
            val layoutParams = LinearLayout.LayoutParams(size, size)
            layoutParams.setMargins(if (index == 0) 0 else margin, 0, 0, 0)
            imageView.layoutParams = layoutParams
            imageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
            imageView.setPadding(8, 8, 8, 8)
            imageView.tag = name

            val circle = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setSize(size, size)
                setColor(android.graphics.Color.parseColor(color))
            }
            imageView.background = circle

            if (selectedIcon == null && index == 0) {
                selectedIcon = name
                imageView.setImageResource(android.R.drawable.ic_menu_compass)
                imageView.setColorFilter(android.graphics.Color.WHITE)
            }

            imageView.setOnClickListener { v ->
                selectedIcon = v.tag as? String
                for (i in 0 until container.childCount) {
                    val child = container.getChildAt(i)
                    if (child is ImageView) {
                        child.setImageDrawable(null)
                        child.colorFilter = null
                    }
                }
                val iv = v as ImageView
                iv.setImageResource(android.R.drawable.ic_menu_compass)
                iv.setColorFilter(android.graphics.Color.WHITE)
            }

            container.addView(imageView)
        }
    }

    private fun getIconColor(name: String): Int {
        val entry = iconColors.find { it.second == name }
        return entry?.let { android.graphics.Color.parseColor(it.first) }
            ?: android.graphics.Color.parseColor("#2196F3")
    }

    private fun generateIconBitmap(color: Int): ByteArray {
        val size = 144
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        canvas.drawCircle((size / 2).toFloat(), (size / 2).toFloat(), (size / 2).toFloat(), bgPaint)

        val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.WHITE
            style = Paint.Style.FILL
            textSize = 72f
            textAlign = Paint.Align.CENTER
        }
        val x = (size / 2).toFloat()
        val y = (size / 2 + 24).toFloat()
        canvas.drawText("W", x, y, innerPaint)

        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        bitmap.recycle()
        return stream.toByteArray()
    }

    private fun startBuild() {
        val templateFile = getTemplateFile()
        if (!templateFile.exists()) {
            toast("template.apk not found")
            return
        }

        val appName = binding.appNameInput.editText?.text?.toString()
            ?.ifBlank { "WebApp" } ?: "WebApp"
        val pkgName = binding.packageInput.editText?.text?.toString()
            ?.ifBlank { "com.webapp" } ?: "com.webapp"

        val permissions = getSelectedPermissions()

        val htmlCode: String
        val zipEntries: Map<String, ByteArray>?

        if (selectedFileUri != null && selectedFileName?.endsWith(".zip") == true) {
            htmlCode = ""
            zipEntries = readZipUri(selectedFileUri!!)
            if (zipEntries.isEmpty()) {
                toast("Empty or invalid zip file")
                return
            }
        } else if (selectedFileUri != null) {
            htmlCode = readUriContent(selectedFileUri!!)
            zipEntries = null
        } else {
            htmlCode = binding.codeInput.text.toString()
            zipEntries = null
        }

        if (htmlCode.isBlank() && zipEntries == null) {
            toast("Enter HTML code or select a file")
            return
        }

        val iconBytes = selectedIcon?.let { colorName ->
            generateIconBitmap(getIconColor(colorName))
        }

        val config = CompilerEngine.Config(
            appName = appName,
            packageName = pkgName,
            permissions = permissions,
            htmlCode = htmlCode,
            templateApk = templateFile,
            iconPng = iconBytes,
            zipEntries = zipEntries
        )

        binding.buildBtn.isEnabled = false
        binding.saveApkBtn.isEnabled = false
        binding.logOutput.text = ""
        isBuilding = true

        Thread {
            engine.build(config) { log ->
                runOnUiThread {
                    binding.logOutput.append("$log\n")
                    binding.logScroll.post {
                        binding.logScroll.fullScroll(android.view.View.FOCUS_DOWN)
                    }
                }
            }.onSuccess {
                runOnUiThread {
                    binding.logOutput.append("\nBuild successful!\n")
                    binding.saveApkBtn.isEnabled = true
                }
            }.onFailure { err ->
                runOnUiThread {
                    binding.logOutput.append("\nBuild failed: ${err.message}\n")
                }
            }.also {
                runOnUiThread {
                    isBuilding = false
                    binding.buildBtn.isEnabled = true
                }
            }
        }.start()
    }

    private fun getSelectedPermissions(): Set<String> {
        val selected = mutableSetOf<String>()
        val chipGroup = binding.permissionGroup
        for (i in 0 until chipGroup.childCount) {
            val chip = chipGroup.getChildAt(i) as? com.google.android.material.chip.Chip ?: continue
            if (chip.isChecked) {
                selected.add(chip.text.toString().uppercase())
            }
        }
        return selected
    }

    private fun readUriContent(uri: Uri): String {
        return try {
            val inputStream = contentResolver.openInputStream(uri)
                ?: return "<!-- Could not read file -->"
            inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "<!-- Error reading file: ${e.message} -->"
        }
    }

    private fun readZipUri(uri: Uri): Map<String, ByteArray> {
        val entries = mutableMapOf<String, ByteArray>()
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return entries
            ZipInputStream(inputStream).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val data = zis.readBytes()
                        entries[entry.name] = data
                    }
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            toast("Error reading zip: ${e.message}")
        }
        return entries
    }

    private fun getTemplateFile(): File {
        return File(filesDir, "template.apk").takeIf { it.exists() }
            ?: File(getExternalFilesDir(null) ?: filesDir, "template.apk")
    }

    private fun saveApk() {
        val outputDir = File(getExternalFilesDir(null), "WebCompiler")
        val apks = outputDir.listFiles { f -> f.extension == "apk" }
        if (apks.isNullOrEmpty()) {
            toast("No APK found to save")
            return
        }
        val apk = apks.maxByOrNull { it.lastModified() } ?: return
        val downloadsDir = File("/storage/emulated/0/Download")
        if (downloadsDir.exists()) {
            val dest = File(downloadsDir, apk.name)
            apk.copyTo(dest, overwrite = true)
            toast("Saved to Downloads/${apk.name}")
        } else {
            toast("APK at: ${apk.absolutePath}")
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
