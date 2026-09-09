package io.github.faraway96.scrcpylite

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)

        val etAddress = findViewById<EditText>(R.id.et_address)
        val etMaxSize = findViewById<EditText>(R.id.et_max_size)
        val etBitrate = findViewById<EditText>(R.id.et_bitrate)
        val etFps = findViewById<EditText>(R.id.et_fps)
        status = findViewById(R.id.tv_status)

        etAddress.setText(prefs.getString("address", ""))
        etMaxSize.setText(prefs.getString("maxSize", "1600"))
        etBitrate.setText(prefs.getString("bitrate", "4"))
        etFps.setText(prefs.getString("fps", "30"))

        findViewById<Button>(R.id.btn_connect).setOnClickListener {
            val address = etAddress.text.toString().trim()
            if (address.isEmpty()) {
                Toast.makeText(this, "请输入被控设备地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val parts = address.split(":")
            val host = parts[0]
            val port = parts.getOrNull(1)?.toIntOrNull() ?: 5555

            prefs.edit()
                .putString("address", address)
                .putString("maxSize", etMaxSize.text.toString())
                .putString("bitrate", etBitrate.text.toString())
                .putString("fps", etFps.text.toString())
                .apply()

            val it2 = Intent(this, MirrorActivity::class.java)
                .putExtra("host", host)
                .putExtra("port", port)
                .putExtra("maxSize", etMaxSize.text.toString().toIntOrNull() ?: 1600)
                .putExtra("bitrate", (etBitrate.text.toString().toIntOrNull() ?: 4) * 1_000_000)
                .putExtra("fps", etFps.text.toString().toIntOrNull() ?: 30)
            startActivity(it2)
        }
    }

    override fun onResume() {
        super.onResume()
        status.text = prefs.getString("lastStatus", getString(R.string.status_idle))
    }

    override fun onPause() {
        super.onPause()
        prefs.edit().putString("lastStatus", status.text.toString()).apply()
    }
}
