package com.example.lpdemo

import android.os.Bundle
import android.util.Base64
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import com.example.lpdemo.databinding.ActivityDataBinding

class DataActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDataBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDataBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val pdfBytes = intent.getByteArrayExtra("pdfBytes")
        if (pdfBytes != null) {
            val base64 = Base64.encodeToString(pdfBytes, Base64.DEFAULT)
            binding.documentPdf.settings.javaScriptEnabled = true
            binding.documentPdf.settings.allowFileAccess = true
            binding.documentPdf.loadData(
                "<html><body style='margin:0;padding:0;'>" +
                "<embed width='100%' height='100%' src='data:application/pdf;base64,$base64' type='application/pdf'/>" +
                "</body></html>",
                "text/html", "UTF-8"
            )
        }
    }
}