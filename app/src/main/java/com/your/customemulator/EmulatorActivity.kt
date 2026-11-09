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
        // මේක UI Thread එකේ කරනකොට App එක hang වෙන නිසා Background එකේ run කරමු
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. අපි Extract කරපු Native files (rootfs) තියෙන තැන හොයාගන්නවා
                val imagefsDir = File(filesDir, "imagefs").absolutePath
                val rootfsDir = File(imagefsDir, "rootfs").absolutePath
                
                // 2. Wine සහ Box64 තියෙන තැන (rootfs එක ඇතුළේ usr/bin එකේ)
                val box64Path = File(rootfsDir, "usr/bin/box64").absolutePath
                val winePath = File(rootfsDir, "usr/bin/wine64").absolutePath
                
                // 3. Wine Prefix එක (මේක මුලින්ම හැදෙන්න ඕනේ)
                val winePrefix = File(filesDir, "wineprefix").absolutePath

                // අපිට මේක run කරන්න proot එකක් ඕනේ (Winlator පාවිච්චි කරන විදිහට)
                val prootPath = File(rootfsDir, "usr/bin/proot").absolutePath 

                // 4. Run කරන්න ඕන Shell Script එක හදනවා
                val script = StringBuilder().apply {
                    // proot එකෙන් rootfs එකට යනවා, ඊට පස්සේ box64 හරහා wine run කරනවා
                    append("export PROOT_NO_SECURE=1\n")
                    append("export WINEPREFIX=$winePrefix\n")
                    append("export WINEDEBUG=-all\n")
                    append("export WINEARCH=win64\n")
                    append("export DISPLAY=:0\n") // Graphics සඳහා (පසුව හදමු)
                    
                    // ඇත්තම run command එක
                    // $prootPath -r $rootfsDir $box64Path $winePath "$exePath"
                    append("$prootPath -r $rootfsDir $box64Path $winePath \"$exePath\"\n")
                }

                val process = ProcessBuilder("sh", "-c", script.toString())
                    .redirectErrorStream(true)
                    .start()
                
                // (මෙතනදී අපිට process එකේ output එක කියවන්න පුළුවන්, ඒත් දැනට ඒක අවශ්‍ය නෑ)
                process.waitFor()

            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this@EmulatorActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
