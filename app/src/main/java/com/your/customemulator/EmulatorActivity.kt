package com.your.customemulator

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class EmulatorActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        setContentView(R.layout.activity_emulator)

        val gameExePath = intent.getStringExtra("EXTRA_GAME_PATH")
        if (!gameExePath.isNullOrEmpty()) {
            launchGameDirectly(gameExePath)
        } else {
            finish()
        }
    }

    private fun launchGameDirectly(exePath: String) {
        Thread {
            val winePrefix = File(filesDir, "wineprefix").absolutePath
            val box64Path = File(filesDir, "usr/bin/box64").absolutePath
            val winePath = File(filesDir, "usr/bin/wine64").absolutePath

            val script = StringBuilder().apply {
                append("export PROOT_NO_SECURE=1\n")
                append("export WINEPREFIX=$winePrefix\n")
                append("export WINEDEBUG=-all\n")
                append("export WINEARCH=win64\n")
                append("$box64Path $winePath \"$exePath\"\n")
            }

            try {
                val process = ProcessBuilder("sh", "-c", script.toString())
                    .redirectErrorStream(true)
                    .start()
                process.waitFor()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }
}