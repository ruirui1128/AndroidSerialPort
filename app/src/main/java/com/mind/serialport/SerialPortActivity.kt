package com.mind.serialport

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.mind.serialport.databinding.ActivityMainBinding
import com.mind.serialport.databinding.ActivitySerialPortBinding
import com.mind.yqserialport.SerialPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream

class SerialPortActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SerialPortActivity"
    }

    private lateinit var binding: ActivitySerialPortBinding

    private var serialPort: SerialPort? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var readJob: Job? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySerialPortBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initPort()
        startReadThread()

    }

    private fun initPort() {
        try {
            serialPort = SerialPort
                .newBuilder("/dev/ttyS3", 9600)
                .parity(0)
                .dataBits(8)
                .stopBits(1)
                .build()
            inputStream = serialPort?.inputStream
            outputStream = serialPort?.outputStream
            Log.d(TAG, "串口初始化成功")
        } catch (e: Exception) {
            Log.e(TAG, "串口异常: ${e.message}")
            Toast.makeText(this, "串口初始化失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startReadThread() {
        readJob = lifecycleScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(128)
            while (true) {
                try {
                    val size = inputStream?.read(buffer) ?: 0
                    if (size > 0) {
                        // 打印结果
                        Log.d(TAG, "接收: ${buffer.sliceArray(0 until size).toHexString()}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "读取异常: ${e.message}")
                    return@launch
                }
            }
        }
    }
}