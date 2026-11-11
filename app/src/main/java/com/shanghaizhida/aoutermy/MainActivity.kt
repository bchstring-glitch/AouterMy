package com.shanghaizhida.aoutermy

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.shanghaizhida.router.AppRouter
import com.shanghaizhida.router.Route

@Route(path = "/app/main")
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppRouter.init()

        val textView = TextView(this).apply {
            text = "AppRouter Initialized Successfully!\nRoute /app/main registered."
            textSize = 18f
            setPadding(40, 100, 40, 40)
        }
        setContentView(textView)
    }
}
