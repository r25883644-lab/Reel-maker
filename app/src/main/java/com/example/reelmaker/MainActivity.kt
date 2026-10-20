package com.example.reelmaker

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.reelmaker.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var videoUri: Uri? = null
    private var logoUri: Uri? = null
    private var resultUri: Uri? = null

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            videoUri = uri
            b.videoName.text = "Selected: ${uri.lastPathSegment}"
        }
    }

    private val pickLogo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            logoUri = uri
            b.logoName.text = "Logo added"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnVideo.setOnClickListener { pickVideo.launch("video/*") }
        b.btnLogo.setOnClickListener { pickLogo.launch("image/*") }
        b.btnMake.setOnClickListener { makeReel() }
        b.btnShare.setOnClickListener {
            val u = resultUri ?: return@setOnClickListener
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "video/mp4"
                        putExtra(Intent.EXTRA_STREAM, u)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Share reel"
                )
            )
        }
    }

    private fun setBusy(busy: Boolean) {
        b.btnMake.isEnabled = !busy
        b.progress.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) b.btnShare.visibility = View.GONE
    }

    private fun makeReel() {
        val v = videoUri ?: run {
            Toast.makeText(this, "Choose a video first", Toast.LENGTH_SHORT).show()
            return
        }
        setBusy(true)
        b.progress.progress = 0

        lifecycleScope.launch {
            val totalSec = withContext(Dispatchers.IO) { AudioAnalyzer.durationSec(this@MainActivity, v) }
            val clipSec = minOf(30, totalSec)
            val manual = b.startInput.text.toString().toIntOrNull()

            val startSec = if (manual != null) {
                manual
            } else {
                b.status.text = "Finding the best 30 seconds…"
                withContext(Dispatchers.Default) {
                    AudioAnalyzer.findBestStartSec(this@MainActivity, v, clipSec) { p ->
                        runOnUiThread { b.progress.progress = p; b.status.text = "Analyzing audio… $p%" }
                    }
                }
            }.coerceIn(0, maxOf(0, totalSec - clipSec))

            b.status.text = "Creating reel (start at ${startSec}s)…"
            b.progress.progress = 0
            val out = File(cacheDir, "reel.mp4")

            ReelExporter.export(
                this@MainActivity, v,
                startSec * 1000L, clipSec * 1000L,
                logoUri, b.logoPosition.selectedItemPosition,
                b.logoSize.progress / 100f,
                out,
                onProgress = { p -> b.progress.progress = p; b.status.text = "Exporting… $p%" },
                onResult = { err ->
                    setBusy(false)
                    if (err != null) {
                        b.status.text = "Failed: ${err.message}"
                    } else {
                        resultUri = saveToGallery(out)
                        b.status.text = "Done! Saved in Movies/ReelMaker"
                        b.btnShare.visibility = View.VISIBLE
                    }
                }
            )
        }
    }

    private fun saveToGallery(file: File): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "reel_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ReelMaker")
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        contentResolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
        return uri
    }
}
