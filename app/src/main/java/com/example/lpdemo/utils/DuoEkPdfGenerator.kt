package com.example.lpdemo.utils

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import com.lepu.blepro.ext.er2.DeviceInfo
import com.lepu.blepro.ext.er2.Er2AnalysisFile
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Generates a PDF report from DuoEK (Bluetooth.MODEL_DUOEK) ECG results.
 *
 * The PDF includes:
 * - Device information (model, hardware/firmware version, serial number)
 * - Real-time parameters (HR, battery, recording status)
 * - ECG diagnosis analysis results
 * - ECG waveform rendering
 */
class DuoEkPdfGenerator(private val context: Context) {

    companion object {
        private const val PAGE_WIDTH = 595   // A4 width in points (72 dpi)
        private const val PAGE_HEIGHT = 842  // A4 height in points
        private const val MARGIN = 40f
        private const val LINE_HEIGHT = 18f
        private const val SECTION_GAP = 12f
    }

    // Collected data holders
    private var deviceInfo: DeviceInfo? = null
    private var heartRate: Int = 0
    private var batteryLevel: Int = 0
    private var batteryState: Int = 0
    private var recordingTime: Int = 0
    private var currentStatus: Int = 0
    private var analysisFiles: MutableList<AnalysisResult> = mutableListOf()
    private var ecgWaveShorts: ShortArray? = null
    private var ecgDurationSec: Int = 0
    private var ecgFileName: String = ""
    private var ecgStartTime: Long = 0

    data class AnalysisResult(
        val recordingTime: Int,
        val isRegular: Boolean,
        val isPoorSignal: Boolean,
        val isLessThan30s: Boolean,
        val isMoving: Boolean,
        val isFastHr: Boolean,
        val isSlowHr: Boolean,
        val isIrregular: Boolean,
        val isPvcs: Boolean,
        val isHeartPause: Boolean,
        val isFibrillation: Boolean,
        val isWideQrs: Boolean,
        val isProlongedQtc: Boolean,
        val isShortQtc: Boolean,
        val isStElevation: Boolean,
        val isStDepression: Boolean
    )

    fun setDeviceInfo(info: DeviceInfo) {
        this.deviceInfo = info
    }

    fun setRealTimeParams(hr: Int, battery: Int, batteryState: Int, recordTime: Int, status: Int) {
        this.heartRate = hr
        this.batteryLevel = battery
        this.batteryState = batteryState
        this.recordingTime = recordTime
        this.currentStatus = status
    }

    fun addAnalysisFromFile(analysisFile: Er2AnalysisFile) {
        for (result in analysisFile.resultList) {
            val d = result.diagnosis
            analysisFiles.add(
                AnalysisResult(
                    recordingTime = analysisFile.recordingTime,
                    isRegular = d.isRegular,
                    isPoorSignal = d.isPoorSignal,
                    isLessThan30s = d.isLessThan30s,
                    isMoving = d.isMoving,
                    isFastHr = d.isFastHr,
                    isSlowHr = d.isSlowHr,
                    isIrregular = d.isIrregular,
                    isPvcs = d.isPvcs,
                    isHeartPause = d.isHeartPause,
                    isFibrillation = d.isFibrillation,
                    isWideQrs = d.isWideQrs,
                    isProlongedQtc = d.isProlongedQtc,
                    isShortQtc = d.isShortQtc,
                    isStElevation = d.isStElevation,
                    isStDepression = d.isStDepression
                )
            )
        }
    }

    fun setEcgWaveData(shorts: ShortArray?, durationSec: Int, fileName: String, startTime: Long) {
        this.ecgWaveShorts = shorts
        this.ecgDurationSec = durationSec
        this.ecgFileName = fileName
        this.ecgStartTime = startTime
    }

    /**
     * Generate the PDF and return the file path, or null on failure.
     */
    fun generatePdf(): File? {
        val document = PdfDocument()
        try {
            val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
            var page = document.startPage(pageInfo)
            var canvas = page.canvas
            var yPos = MARGIN

            // ═══════════════════════ TITLE ═══════════════════════
            val titlePaint = Paint().apply {
                color = Color.rgb(0, 102, 153)
                textSize = 22f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isAntiAlias = true
            }
            canvas.drawText("DuoEK ECG Report", MARGIN, yPos + 22f, titlePaint)
            yPos += 36f

            // Date/time
            val subtitlePaint = Paint().apply {
                color = Color.DKGRAY
                textSize = 11f
                isAntiAlias = true
            }
            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            canvas.drawText("Generated: $dateStr", MARGIN, yPos, subtitlePaint)
            yPos += LINE_HEIGHT + SECTION_GAP

            // Horizontal rule
            yPos = drawHorizontalRule(canvas, yPos)

            // ═══════════════════════ DEVICE INFO ═══════════════════════
            val headerPaint = Paint().apply {
                color = Color.rgb(0, 102, 153)
                textSize = 14f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isAntiAlias = true
            }
            val labelPaint = Paint().apply {
                color = Color.DKGRAY
                textSize = 12f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isAntiAlias = true
            }
            val valuePaint = Paint().apply {
                color = Color.BLACK
                textSize = 12f
                isAntiAlias = true
            }

            canvas.drawText("Device Information", MARGIN, yPos, headerPaint)
            yPos += LINE_HEIGHT + 4f

            deviceInfo?.let { info ->
                yPos = drawKeyValue(canvas, "Model:", "$info", yPos, labelPaint, valuePaint)
            } ?: run {
                yPos = drawKeyValue(canvas, "Model:", "DuoEK (not yet retrieved)", yPos, labelPaint, valuePaint)
            }
            yPos += SECTION_GAP

            // ═══════════════════════ REAL-TIME PARAMS ═══════════════════════
            yPos = drawHorizontalRule(canvas, yPos)
            canvas.drawText("Real-Time Parameters", MARGIN, yPos, headerPaint)
            yPos += LINE_HEIGHT + 4f

            yPos = drawKeyValue(canvas, "Heart Rate:", "$heartRate bpm", yPos, labelPaint, valuePaint)
            yPos = drawKeyValue(canvas, "Battery:", "$batteryLevel%", yPos, labelPaint, valuePaint)
            yPos = drawKeyValue(canvas, "Battery State:", batteryStateText(batteryState), yPos, labelPaint, valuePaint)
            yPos = drawKeyValue(canvas, "Recording Time:", "${recordingTime}s", yPos, labelPaint, valuePaint)
            yPos = drawKeyValue(canvas, "Current Status:", statusText(currentStatus), yPos, labelPaint, valuePaint)
            yPos += SECTION_GAP

            // ═══════════════════════ ECG DIAGNOSIS ═══════════════════════
            if (analysisFiles.isNotEmpty()) {
                yPos = drawHorizontalRule(canvas, yPos)
                canvas.drawText("ECG Analysis / Diagnosis", MARGIN, yPos, headerPaint)
                yPos += LINE_HEIGHT + 4f

                for ((index, result) in analysisFiles.withIndex()) {
                    if (yPos > PAGE_HEIGHT - 100) {
                        document.finishPage(page)
                        val newPageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, document.pages.size + 1).create()
                        page = document.startPage(newPageInfo)
                        canvas = page.canvas
                        yPos = MARGIN
                    }

                    canvas.drawText("Analysis #${index + 1}  (Recording: ${result.recordingTime}s)", MARGIN, yPos, labelPaint)
                    yPos += LINE_HEIGHT

                    val findings = buildDiagnosisFindings(result)
                    if (findings.isEmpty()) {
                        yPos = drawKeyValue(canvas, "  Result:", "Normal / No findings", yPos, labelPaint, valuePaint)
                    } else {
                        for (finding in findings) {
                            if (yPos > PAGE_HEIGHT - 60) {
                                document.finishPage(page)
                                val newPageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, document.pages.size + 1).create()
                                page = document.startPage(newPageInfo)
                                canvas = page.canvas
                                yPos = MARGIN
                            }
                            canvas.drawText("  • $finding", MARGIN + 10f, yPos, valuePaint)
                            yPos += LINE_HEIGHT
                        }
                    }
                    yPos += 6f
                }
                yPos += SECTION_GAP
            }

            // ═══════════════════════ ECG WAVEFORM ═══════════════════════
            ecgWaveShorts?.let { shorts ->
                if (shorts.isNotEmpty()) {
                    if (yPos > PAGE_HEIGHT - 220) {
                        document.finishPage(page)
                        val newPageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, document.pages.size + 1).create()
                        page = document.startPage(newPageInfo)
                        canvas = page.canvas
                        yPos = MARGIN
                    }

                    yPos = drawHorizontalRule(canvas, yPos)
                    canvas.drawText("ECG Waveform", MARGIN, yPos, headerPaint)
                    yPos += 6f

                    if (ecgFileName.isNotEmpty()) {
                        canvas.drawText("File: $ecgFileName  |  Duration: ${ecgDurationSec}s", MARGIN, yPos + LINE_HEIGHT, subtitlePaint)
                        yPos += LINE_HEIGHT + 4f
                    }
                    if (ecgStartTime > 0) {
                        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                            .format(Date(ecgStartTime * 1000))
                        canvas.drawText("Start: $timeStr", MARGIN, yPos + LINE_HEIGHT, subtitlePaint)
                        yPos += LINE_HEIGHT + 4f
                    }

                    yPos += 8f
                    val waveHeight = 160f
                    val waveWidth = PAGE_WIDTH - 2 * MARGIN
                    yPos = drawEcgWaveform(canvas, shorts, MARGIN, yPos, waveWidth, waveHeight)
                    yPos += SECTION_GAP
                }
            }

            // ═══════════════════════ FOOTER ═══════════════════════
            if (yPos > PAGE_HEIGHT - 40) {
                document.finishPage(page)
                val newPageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, document.pages.size + 1).create()
                page = document.startPage(newPageInfo)
                canvas = page.canvas
                yPos = MARGIN
            }
            yPos = drawHorizontalRule(canvas, yPos)
            val footerPaint = Paint().apply {
                color = Color.GRAY
                textSize = 9f
                isAntiAlias = true
            }
            canvas.drawText("CardioCubz DuoEK Report — For informational purposes only. Not a medical diagnosis.", MARGIN, yPos, footerPaint)

            document.finishPage(page)

            // Save
            val file = getPdfFile()
            FileOutputStream(file).use { out ->
                document.writeTo(out)
            }
            return file

        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            document.close()
        }
    }

    /**
     * Generate PDF and share it via Android share intent.
     */
    fun generateAndSharePdf() {
        val file = generatePdf()
        if (file == null) {
            Toast.makeText(context, "Failed to generate PDF", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(context, "PDF saved: ${file.name}", Toast.LENGTH_LONG).show()

        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share DuoEK Report"))
        } catch (e: Exception) {
            // If FileProvider is not configured, just inform user of file location
            Toast.makeText(context, "PDF saved to: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Drawing helpers
    // ════════════════════════════════════════════════════════════════

    private fun drawHorizontalRule(canvas: Canvas, y: Float): Float {
        val paint = Paint().apply {
            color = Color.LTGRAY
            strokeWidth = 1f
        }
        canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, paint)
        return y + LINE_HEIGHT
    }

    private fun drawKeyValue(canvas: Canvas, label: String, value: String, y: Float, lp: Paint, vp: Paint): Float {
        canvas.drawText(label, MARGIN, y, lp)
        val labelWidth = lp.measureText(label) + 6f
        // Truncate value if too long for the line
        val maxValueWidth = PAGE_WIDTH - 2 * MARGIN - labelWidth
        val displayValue = if (vp.measureText(value) > maxValueWidth) {
            // Truncate
            var truncated = value
            while (vp.measureText("$truncated...") > maxValueWidth && truncated.length > 5) {
                truncated = truncated.dropLast(1)
            }
            "$truncated..."
        } else {
            value
        }
        canvas.drawText(displayValue, MARGIN + labelWidth, y, vp)
        return y + LINE_HEIGHT
    }

    private fun drawEcgWaveform(canvas: Canvas, shorts: ShortArray, x: Float, y: Float, width: Float, height: Float): Float {
        // Draw background grid
        val gridPaint = Paint().apply {
            color = Color.rgb(230, 240, 230)
            strokeWidth = 0.5f
            style = Paint.Style.STROKE
        }
        // Light grid lines every 5mm equivalent
        val gridSpacing = width / 50f
        var gx = x
        while (gx <= x + width) {
            canvas.drawLine(gx, y, gx, y + height, gridPaint)
            gx += gridSpacing
        }
        var gy = y
        while (gy <= y + height) {
            canvas.drawLine(x, gy, x + width, gy, gridPaint)
            gy += gridSpacing
        }

        // Border
        val borderPaint = Paint().apply {
            color = Color.rgb(180, 200, 180)
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }
        canvas.drawRect(x, y, x + width, y + height, borderPaint)

        // Draw waveform
        if (shorts.isEmpty()) return y + height + 8f

        val wavePaint = Paint().apply {
            color = Color.rgb(0, 120, 60)
            strokeWidth = 1.2f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        val centerY = y + height / 2f
        // Calculate min/max for scaling
        var min = Short.MAX_VALUE
        var max = Short.MIN_VALUE
        for (s in shorts) {
            if (s < min) min = s
            if (s > max) max = s
        }
        val range = (max - min).toFloat().coerceAtLeast(1f)
        val scale = (height * 0.8f) / range

        // Downsample if needed to fit width
        val step = (shorts.size.toFloat() / width).coerceAtLeast(1f)
        val path = Path()
        var started = false
        var px = x
        var i = 0f
        while (i < shorts.size && px <= x + width) {
            val idx = i.toInt().coerceIn(0, shorts.size - 1)
            val py = centerY - (shorts[idx] - (min + max) / 2f) * scale
            if (!started) {
                path.moveTo(px, py)
                started = true
            } else {
                path.lineTo(px, py)
            }
            px += 1f
            i += step
        }
        canvas.drawPath(path, wavePaint)

        // baseline
        val basePaint = Paint().apply {
            color = Color.rgb(200, 200, 200)
            strokeWidth = 0.5f
            pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
        }
        canvas.drawLine(x, centerY, x + width, centerY, basePaint)

        return y + height + 8f
    }

    private fun batteryStateText(state: Int): String = when (state) {
        0 -> "No charge"
        1 -> "Charging"
        2 -> "Charging complete"
        3 -> "Low battery"
        else -> "Unknown ($state)"
    }

    private fun statusText(status: Int): String = when (status) {
        0 -> "Idle"
        1 -> "Preparing"
        2 -> "Measuring"
        3 -> "Saving file"
        4 -> "Saving succeed"
        5 -> "Less than 30s, file not saved"
        6 -> "6 retests"
        7 -> "Lead off"
        else -> "Unknown ($status)"
    }

    private fun buildDiagnosisFindings(result: AnalysisResult): List<String> {
        val findings = mutableListOf<String>()
        if (result.isRegular) findings.add("Regular ECG Rhythm")
        if (result.isPoorSignal) findings.add("Unable to analyze (poor signal)")
        if (result.isLessThan30s) findings.add("Less than 30s — no analysis performed")
        if (result.isMoving) findings.add("Action detected — not analyzed")
        if (result.isFastHr) findings.add("Fast Heart Rate")
        if (result.isSlowHr) findings.add("Slow Heart Rate")
        if (result.isIrregular) findings.add("Irregular ECG Rhythm")
        if (result.isPvcs) findings.add("Possible ventricular premature beats (PVCs)")
        if (result.isHeartPause) findings.add("Possible heart pause")
        if (result.isFibrillation) findings.add("Possible Atrial Fibrillation")
        if (result.isWideQrs) findings.add("Wide QRS duration")
        if (result.isProlongedQtc) findings.add("Prolonged QTc interval")
        if (result.isShortQtc) findings.add("Short QTc interval")
        if (result.isStElevation) findings.add("ST segment elevation")
        if (result.isStDepression) findings.add("ST segment depression")
        return findings
    }

    private fun getPdfFile(): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "DuoEK_Reports")
        if (!dir.exists()) dir.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return File(dir, "DuoEK_Report_$timestamp.pdf")
    }
}
