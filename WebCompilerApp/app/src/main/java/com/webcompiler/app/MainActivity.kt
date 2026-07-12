package com.webcompiler.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.webcompiler.app.databinding.ActivityMainBinding
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val engine = CompilerEngine(this)
    private var selectedFileUri: Uri? = null
    private var isBuilding = false

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedFileUri = it
            binding.codeInput.setText("File selected: $it")
            binding.codeInput.isEnabled = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Extract bundled template.apk + apktool.jar from APK assets
        engine.extractBundledAssets()

        checkSetup()

        binding.selectFileBtn.setOnClickListener {
            filePicker.launch("text/html")
        }

        binding.buildBtn.setOnClickListener {
            if (!isBuilding) startBuild()
        }

        binding.saveApkBtn.setOnClickListener {
            saveApk()
        }
    }

    private fun checkSetup() {
        val apktoolFile = getApktoolFile()
        val templateFile = getTemplateFile()

        if (!apktoolFile.exists() || !templateFile.exists()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Setup Required")
                .setMessage(buildString {
                    append("WebCompiler needs two files to work:\n\n")
                    if (!apktoolFile.exists()) {
                        append("• apktool.jar (download from https://bitbucket.org/iBotPeaches/apktool/downloads)\n")
                        append("  → Place at: ${apktoolFile.absolutePath}\n\n")
                    }
                    if (!templateFile.exists()) {
                        append("• template.apk (build from WebViewTemplate project)\n")
                        append("  → Place at: ${templateFile.absolutePath}\n")
                    }
                    append("\nIf you built from GitHub Actions, these files are bundled automatically.\n")
                    append("For development builds, place them manually.")
                })
                .setPositiveButton("OK") { _, _ -> }
                .show()
        }
    }

    private fun getApktoolFile(): File {
        return File(filesDir, "apktool.jar").takeIf { it.exists() }
            ?: File(getExternalFilesDir(null) ?: filesDir, "apktool.jar")
    }

    private fun getTemplateFile(): File {
        return File(filesDir, "template.apk").takeIf { it.exists() }
            ?: File(getExternalFilesDir(null) ?: filesDir, "template.apk")
    }

    private fun startBuild() {
        val apktoolFile = getApktoolFile()
        val templateFile = getTemplateFile()

        if (!apktoolFile.exists()) {
            toast("apktool.jar not found. Run setup first.")
            return
        }
        if (!templateFile.exists()) {
            toast("template.apk not found. Run setup first.")
            return
        }

        val htmlCode = if (selectedFileUri != null) {
            readUriContent(selectedFileUri!!)
        } else {
            binding.codeInput.text.toString()
        }

        if (htmlCode.isBlank()) {
            toast("Enter HTML code or select a file")
            return
        }

        val appName = binding.appNameInput.editText?.text?.toString()
            ?.ifBlank { "WebApp" } ?: "WebApp"
        val pkgName = binding.packageInput.editText?.text?.toString()
            ?.ifBlank { "com.webapp" } ?: "com.webapp"

        val permissions = getSelectedPermissions()

        val config = CompilerEngine.Config(
            appName = appName,
            packageName = pkgName,
            permissions = permissions,
            htmlCode = htmlCode,
            templateApk = templateFile,
            apktoolJar = apktoolFile
        )

        isBuilding = true
        binding.buildBtn.isEnabled = false
        binding.saveApkBtn.isEnabled = false
        binding.logOutput.text = ""

        Thread {
            engine.build(config) { log ->
                runOnUiThread {
                    binding.logOutput.append("$log\n")
                    // Auto-scroll
                    binding.logScroll.post {
                        binding.logScroll.fullScroll(android.view.View.FOCUS_DOWN)
                    }
                }
            }.onSuccess {
                runOnUiThread {
                    binding.logOutput.append("\n✅ Build successful!\n")
                    binding.saveApkBtn.isEnabled = true
                }
            }.onFailure { err ->
                runOnUiThread {
                    binding.logOutput.append("\n❌ Build failed: ${err.message}\n")
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
            val chip = chipGroup.getChildAt(i) as? Chip ?: continue
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

    private fun saveApk() {
        val outputDir = File(getExternalFilesDir(null), "WebCompiler")
        val apks = outputDir.listFiles { f -> f.extension == "apk" }
        if (apks.isNullOrEmpty()) {
            toast("No APK found to save")
            return
        }

        // Get the latest APK
        val apk = apks.maxByOrNull { it.lastModified() } ?: return

        // Copy to Downloads
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
