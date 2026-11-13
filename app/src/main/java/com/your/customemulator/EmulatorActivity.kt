package com.your.customemulator

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val imagefsDir = File(filesDir, "imagefs").absolutePath
                
                // Wine තියෙන ඇත්ත තැන
                val winePath = File(imagefsDir, "rootfs/opt/wine/bin/wine").absolutePath
                
                // Box64 එක තියෙන තැන
                val box64Path = File(imagefsDir, "rootfs/usr/bin/box64").absolutePath
                
                val winePrefix = File(filesDir, "wineprefix").absolutePath
                val prootPath = File(imagefsDir, "rootfs/usr/bin/proot").absolutePath
    
                val script = StringBuilder().apply {
                    append("export PROOT_NO_SECURE=1\n")
                    append("export WINEPREFIX=$winePrefix\n")
                    append("export WINEDEBUG=-all\n")
                    append("export WINEARCH=win64\n")
                    append("export DISPLAY=:0\n")
                    append("$prootPath -r $imagefsDir/rootfs $box64Path $winePath \"$exePath\"\n")
                }
    
                val process = ProcessBuilder("sh", "-c", script.toString())
                    .redirectErrorStream(true)
                    .start()
                process.waitFor()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
}
