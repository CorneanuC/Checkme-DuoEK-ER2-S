package com.example.lpdemo

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.lpdemo.databinding.ActivityEr2Binding
import com.example.lpdemo.utils.*
import com.lepu.blepro.utils.DecompressUtil
import com.example.lpdemo.views.EcgBkg
import com.example.lpdemo.views.EcgView
import com.jeremyliao.liveeventbus.LiveEventBus
import com.lepu.blepro.ext.BleServiceHelper
import com.lepu.blepro.constants.Ble
import com.lepu.blepro.event.InterfaceEvent
import com.lepu.blepro.ext.er2.*
import com.lepu.blepro.objs.Bluetooth
import com.lepu.blepro.observer.BIOL
import com.lepu.blepro.observer.BleChangeObserver
import com.lepu.blepro.utils.DateUtil
import com.lepu.blepro.utils.FilterUtil
import kotlin.collections.ArrayList
import kotlin.math.floor

class Er2SActivity : AppCompatActivity(), BleChangeObserver {

    private val TAG = "Er2SActivity"
    private var model = Bluetooth.MODEL_DUOEK
    private lateinit var binding: ActivityEr2Binding
    private lateinit var pdfGenerator: DuoEkPdfGenerator

    private var config = Er2Config()

    private var fileNames = arrayListOf<String>()
    private lateinit var ecgAdapter: EcgAdapter
    var ecgList: ArrayList<EcgData> = arrayListOf()

    private lateinit var ecgBkg: EcgBkg
    private lateinit var ecgView: EcgView
    /**
     * rt wave
     */
    private val waveHandler = Handler(Looper.getMainLooper())
    private val ecgWaveTask = EcgWaveTask()

    inner class EcgWaveTask : Runnable {
        override fun run() {
            val interval: Int = when {
                DataController.dataRec.size > 250*2 -> {
                    18
                }
                DataController.dataRec.size > 150*2 -> {
                    19
                }
                DataController.dataRec.size > 75*2 -> {
                    20
                }
                else -> {
                    21
                }
            }

            waveHandler.postDelayed(this, interval.toLong())

            val temp = DataController.draw(10)
            Log.d(TAG, "EcgWaveTask: bufferSize=${DataController.dataRec.size}, interval=${interval}ms, drew=${temp?.size ?: "null (not enough data)"}, maxIndex=${DataController.maxIndex}, nWave=${DataController.nWave}")
            dataEcgSrc.value = DataController.feed(dataEcgSrc.value, temp)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEr2Binding.inflate(layoutInflater)
        setContentView(binding.root)
        model = intent.getIntExtra("model", model)
        lifecycle.addObserver(BIOL(this, intArrayOf(model)))
        pdfGenerator = DuoEkPdfGenerator(this)
        DataController.nWave = 4
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            applyFullScreen()
        }
        Log.d(TAG, "onCreate: model=$model, nWave=${DataController.nWave}, maxIndex=${DataController.maxIndex}")
        initView()
        initEventBus()
    }

    private fun initView() {
        binding.bleName.text = deviceName
        LinearLayoutManager(this).apply {
            this.orientation = LinearLayoutManager.VERTICAL
            binding.ecgFileRcv.layoutManager = this
        }
        ecgAdapter = EcgAdapter(R.layout.device_item, null).apply {
            binding.ecgFileRcv.adapter = this
        }
        ecgAdapter.setOnItemClickListener { adapter, view, position ->
            if (adapter.data.size > 0) {
                (adapter.getItem(position) as EcgData).let {
                    val intent = Intent(this@Er2SActivity, WaveEcgActivity::class.java)
                    intent.putExtra("model", model)
                    ecgData.startTime = it.startTime
                    ecgData.shortData = it.shortData
                    startActivity(intent)
                }
            }
        }
        binding.ecgBkg.post {
            initEcgView()
        }
        binding.getInfo.setOnClickListener {
            BleServiceHelper.BleServiceHelper.er2GetInfo(model)
        }
        binding.factoryReset.setOnClickListener {
            BleServiceHelper.BleServiceHelper.er2FactoryReset(model)
        }
        binding.getConfig.setOnClickListener {
            BleServiceHelper.BleServiceHelper.er2GetConfig(model)
        }
        binding.setConfig.setOnClickListener {
            config.isSoundOn = !config.isSoundOn
            BleServiceHelper.BleServiceHelper.er2SetConfig(model, config)
        }
        binding.startRtTask.setOnClickListener {
            Log.d(TAG, "startRtTask clicked: model=$model, maxIndex=${DataController.maxIndex}, mm2px=${DataController.mm2px}, nWave=${DataController.nWave}, ecgViewInit=${this::ecgView.isInitialized}")
            waveHandler.removeCallbacks(ecgWaveTask)
            waveHandler.postDelayed(ecgWaveTask, 1000)
            BleServiceHelper.BleServiceHelper.setRTDelayTime(model, 200)
            BleServiceHelper.BleServiceHelper.startRtTask(model)
        }
        binding.stopRtTask.setOnClickListener {
            waveHandler.removeCallbacks(ecgWaveTask)
            BleServiceHelper.BleServiceHelper.stopRtTask(model)
        }
        binding.getFileList.setOnClickListener {
            fileNames.clear()
            ecgList.clear()
            ecgAdapter.setNewInstance(ecgList)
            ecgAdapter.notifyDataSetChanged()
            BleServiceHelper.BleServiceHelper.er2GetFileList(model)
        }
        binding.readFile.setOnClickListener {
            if (fileNames.isEmpty()) {
                Toast.makeText(this, "No files found. Tap 'Get File List' first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            waveHandler.removeCallbacks(ecgWaveTask)
            BleServiceHelper.BleServiceHelper.stopRtTask(model)
            readFile()
        }
        binding.savePdf.setOnClickListener {
            val file = pdfGenerator.generatePdf()
            if (file != null) {
                Toast.makeText(this, "PDF saved: ${file.absolutePath}", Toast.LENGTH_LONG).show()
                pdfGenerator.generateAndSharePdf()
            } else {
                Toast.makeText(this, "No data to generate PDF. Read files first.", Toast.LENGTH_SHORT).show()
            }
        }
        bleState.observe(this) {
            if (it) {
                binding.bleState.setImageResource(R.mipmap.bluetooth_ok)
            } else {
                waveHandler.removeCallbacks(ecgWaveTask)
                BleServiceHelper.BleServiceHelper.stopRtTask(model)
                binding.bleState.setImageResource(R.mipmap.bluetooth_error)
            }
        }
        dataEcgSrc.observe(this) {
            Log.d(TAG, "dataEcgSrc observer fired: src.size=${it?.size ?: "null"}, ecgViewInit=${this::ecgView.isInitialized}")
            if (this::ecgView.isInitialized) {
                ecgView.setDataSrc(it)
                ecgView.invalidate()
            } else {
                Log.w(TAG, "dataEcgSrc: ecgView NOT initialized yet, skipping draw")
            }
        }
        ArrayAdapter(this,
            android.R.layout.simple_list_item_1,
            arrayListOf("5mm/mV", "10mm/mV", "20mm/mV")
        ).apply {
            binding.gain.adapter = this
        }
        binding.gain.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                DataController.ampKey = position
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        }
        ArrayAdapter(this,
            android.R.layout.simple_list_item_1,
            arrayListOf("25mm/s", "12.5mm/s", "6.25mm/s")
        ).apply {
            binding.speed.adapter = this
        }
        binding.speed.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                when (position) {
                    0 -> DataController.speed = 1
                    1 -> DataController.speed = 2
                    2 -> DataController.speed = 4
                }
                val dm = resources.displayMetrics
                val index = floor((binding.ecgBkg.width / dm.xdpi * 25.4 / 25 * 500) * DataController.speed).toInt()
                DataController.maxIndex = index
                dataEcgSrc.value = null
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        }
    }

    private fun initEcgView() {
        // cal screen
        val dm = resources.displayMetrics
        val index = floor((binding.ecgBkg.width / dm.xdpi * 25.4 / 25 * 500) * DataController.speed).toInt()
        DataController.maxIndex = index

        val mm2px = 25.4f / dm.xdpi
        DataController.mm2px = mm2px
        Log.d(TAG, "initEcgView: ecgBkg.width=${binding.ecgBkg.width}px, xdpi=${dm.xdpi}, maxIndex=$index, mm2px=$mm2px")

        binding.ecgBkg.measure(0, 0)
        ecgBkg = EcgBkg(this)
        binding.ecgBkg.addView(ecgBkg)

        binding.ecgView.measure(0, 0)
        ecgView = EcgView(this)
        binding.ecgView.addView(ecgView)
        Log.d(TAG, "initEcgView: ecgView initialized, ecgView.width=${binding.ecgView.width}, ecgView.height=${binding.ecgView.height}")
    }

    private fun initEventBus() {
        LiveEventBus.get<InterfaceEvent>(InterfaceEvent.ER2.EventEr2Info)
            .observe(this) {
                val data = it.data as DeviceInfo
                pdfGenerator.setDeviceInfo(data)
                binding.dataLog.text = "$data"
                Toast.makeText(this, "Device info received", Toast.LENGTH_SHORT).show()
            }
        LiveEventBus.get<InterfaceEvent>(InterfaceEvent.ER2.EventEr2FactoryReset)
            .observe(this) {
                val data = it.data as Boolean
                binding.dataLog.text = "EventEr2FactoryReset $data"
            }
        LiveEventBus.get<InterfaceEvent>(InterfaceEvent.ER2.EventEr2GetConfig)
            .observe(this) {
                config = it.data as Er2Config
                binding.dataLog.text = "$config"
                Toast.makeText(this, "Config received", Toast.LENGTH_SHORT).show()
            }
        LiveEventBus.get<InterfaceEvent>(InterfaceEvent.ER2.EventEr2SetConfig)
            .observe(this) {
                val data = it.data as Boolean
                binding.dataLog.text = "EventEr2SetConfig $data"
            }
        LiveEventBus.get<InterfaceEvent>(InterfaceEvent.ER2.EventEr2RtData)
            .observe(this) {
                val data = it.data as RtData
                Log.d(TAG, "EventEr2RtData: model=${it.model}, hr=${data.param.hr}, status=${data.param.curStatus}")
                Log.d(TAG, "  ecgFloats.size=${data.wave.ecgFloats?.size ?: "null"}, ecgFloatsFilter.size=${data.wave.ecgFloatsFilter?.size ?: "null"}")
                Log.d(TAG, "  ecgShorts.size=${data.wave.ecgShorts?.size ?: "null"}, dataRec.size BEFORE=${DataController.dataRec.size}")
                // Use ecgFloatsFilter if non-empty, otherwise fall back to ecgFloats
                val filterNotEmpty = data.wave.ecgFloatsFilter != null && data.wave.ecgFloatsFilter.isNotEmpty()
                val ecgData = if (filterNotEmpty) data.wave.ecgFloatsFilter else data.wave.ecgFloats
                Log.d(TAG, "  using ecgData source: ${if (filterNotEmpty) "ecgFloatsFilter" else "ecgFloats (fallback)"}")
                DataController.receive(ecgData)
                Log.d(TAG, "  dataRec.size AFTER receive=${DataController.dataRec.size}")
                binding.hr.text = "${data.param.hr}"
                binding.dataLog.text = "${data.param}"
                pdfGenerator.setRealTimeParams(
                    hr = data.param.hr,
                    battery = data.param.battery,
                    batteryState = data.param.batteryState,
                    recordTime = data.param.recordTime,
                    status = data.param.curStatus
                )
                // sampling rate：500HZ
                // mV = n * 0.002467（data.wave.ecgFloats = data.wave.ecgShorts * 0.002467）
                // data.param.batteryState：0（no charge），1（charging），2（charging complete），3（low battery）
                // data.param.battery：0-100
                // data.param.recordTime：unit（s）
                // data.param.curStatus：0（idle），1（preparing），2（measuring），3（saving file），4（saving succeed），
                //                       5（less than 30s, file not saved），6（6 retests），7（lead off）
            }
        LiveEventBus.get<InterfaceEvent>(InterfaceEvent.ER2.EventEr2FileList)
            .observe(this) {
                fileNames = it.data as ArrayList<String>
                binding.dataLog.text = "$fileNames"
                Toast.makeText(this, "${fileNames.size} file(s) found on device", Toast.LENGTH_SHORT).show()
            }
        LiveEventBus.get<InterfaceEvent>(InterfaceEvent.ER2.EventEr2ReadFileError)
            .observe(this) {
                val data = it.data as Boolean
                binding.dataLog.text = "EventEr1ReadFileError $data"
            }
        LiveEventBus.get<InterfaceEvent>(InterfaceEvent.ER2.EventEr2ReadingFileProgress)
            .observe(this) {
                val data = it.data as Int  // 0-100
                binding.dataLog.text = "${fileNames[0]} $data %"
            }
        LiveEventBus.get<InterfaceEvent>(InterfaceEvent.ER2.EventEr2ReadFileComplete)
            .observe(this) {
                val data = it.data as Er2File
                if (data.fileName.contains("a")) {
                    val file = Er2AnalysisFile(data.content)
                    pdfGenerator.addAnalysisFromFile(file)
                } else if (data.fileName.contains("R")) {
                    val file = Er2EcgFile(data.content)
                    val ecgData = EcgData()
                    val startTime = DateUtil.getSecondTimestamp(data.fileName.replace("R", ""))
                    ecgData.fileName = data.fileName
                    ecgData.duration = file.recordingTime
                    ecgData.shortData = FilterUtil.getEcgFileFilterData(it.model, data.content)
                    ecgData.startTime = startTime
                    pdfGenerator.setEcgWaveData(ecgData.shortData, file.recordingTime, data.fileName, startTime)
                    ecgList.add(ecgData)
                    ecgAdapter.setNewInstance(ecgList)
                    ecgAdapter.notifyDataSetChanged()
                }
                fileNames.removeAt(0)
                readFile()
                if (fileNames.isEmpty()) {
                    Toast.makeText(this, "All files read. Ready to Save PDF.", Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun readFile() {
        if (fileNames.size == 0) return
        BleServiceHelper.BleServiceHelper.er2ReadFile(model, fileNames[0])
    }

    @Suppress("DEPRECATION")
    private fun applyFullScreen() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            applyFullScreen()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            applyFullScreen()
        }
        // Rebuild ECG views to fit the new screen dimensions
        binding.ecgBkg.removeAllViews()
        binding.ecgView.removeAllViews()
        binding.ecgBkg.post { initEcgView() }
    }

    override fun onBleStateChanged(model: Int, state: Int) {
        // 蓝牙状态 Ble.State
        Log.d(TAG, "model $model, state: $state")

        _bleState.value = state == Ble.State.CONNECTED
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        waveHandler.removeCallbacks(ecgWaveTask)
        BleServiceHelper.BleServiceHelper.stopRtTask(model)
        DataController.clear()
        dataEcgSrc.value = null
        BleServiceHelper.BleServiceHelper.disconnect(false)
        super.onDestroy()
    }

}