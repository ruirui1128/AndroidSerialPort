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
import com.mind.yqserialport.SerialPort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.InputStream
import java.io.OutputStream

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "WEIGHT"
        private const val POLL_INTERVAL_MS = 200L
        private const val RESPONSE_TIMEOUT_MS = 3000L
        private const val DEVICE_ADDR = 1

        private const val REG_WEIGHT = 0x0000       // PLC 1  → 读重量 / 写校准
    }
    private lateinit var binding: ActivityMainBinding

    private var serialPort: SerialPort? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var readJob: Job? = null
    private var pollJob: Job? = null

    private var pendingResponse: CompletableDeferred<ByteArray>? = null
    @Volatile
    private var writeResponsePending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initPort()
        startReadThread()
        binding.btnStartPoll.setOnClickListener { startPoll() }
        binding.btnStopPoll.setOnClickListener { stopPoll() }
        binding.btnZero.setOnClickListener { calibrateZero() }
        binding.btnCalibrate.setOnClickListener { calibrateSpan() }
    }
    // ── 串口初始化 ──────────────────────────────────────

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
            Log.d(TAG, "称重串口初始化成功")
        } catch (e: Exception) {
            Log.e(TAG, "称重串口异常: ${e.message}")
            Toast.makeText(this, "串口初始化失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ── 串口读取线程 ────────────────────────────────────

    private fun startReadThread() {
        readJob = lifecycleScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(64)
            val frameBuffer = mutableListOf<Byte>()
            while (true) {
                try {
                    val size = inputStream?.read(buffer) ?: 0
                    if (size > 0) {
                        for (i in 0 until size) {
                            frameBuffer.add(buffer[i])
                        }
                        while (tryParseFrame(frameBuffer)) {
                            // 循环解析所有完整帧
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "读取异常: ${e.message}")
                    return@launch
                }
            }
        }
    }

    private fun tryParseFrame(frameBuffer: MutableList<Byte>): Boolean {
        if (frameBuffer.size < 5) return false

        // Modbus 响应最少 5 字节（地址 + 功能码 + 数据 + CRC2字节）
        val addr = frameBuffer[0].toInt() and 0xFF
        if (addr != DEVICE_ADDR) {
            frameBuffer.removeAt(0)
            return false
        }

        val funcCode = frameBuffer[1].toInt() and 0xFF

        return when (funcCode) {
            0x03 -> tryParseReadResponse(frameBuffer)
            0x10 -> tryParseWriteResponse(frameBuffer)
            else -> {
                frameBuffer.removeAt(0)
                false
            }
        }
    }

    private fun tryParseReadResponse(frameBuffer: MutableList<Byte>): Boolean {
        if (frameBuffer.size < 3) return false
        val byteCount = frameBuffer[2].toInt() and 0xFF
        // [地址1][功能码1][字节数1][数据N][CRC2]
        val frameLength = 1 + 1 + 1 + byteCount + 2
        if (frameBuffer.size < frameLength) return false

        val frame = ByteArray(frameLength)
        for (i in 0 until frameLength) {
            frame[i] = frameBuffer[i]
        }
        repeat(frameLength) { frameBuffer.removeAt(0) }

        if (!verifyCrc(frame)) {
            Log.w(TAG, "CRC校验失败: ${frame.toHexString()}")
            return false
        }

        Log.d(TAG, "收到读响应: ${frame.toHexString()}")
        pendingResponse?.complete(frame)
        return true
    }

    private fun tryParseWriteResponse(frameBuffer: MutableList<Byte>): Boolean {
        // 写响应固定 8 字节: [地址1][0x10][地址高][地址低][数量高][数量低][CRC低][CRC高]
        val frameLength = 8
        if (frameBuffer.size < frameLength) return false

        val frame = ByteArray(frameLength)
        for (i in 0 until frameLength) {
            frame[i] = frameBuffer[i]
        }
        repeat(frameLength) { frameBuffer.removeAt(0) }

        if (!verifyCrc(frame)) {
            Log.w(TAG, "CRC校验失败: ${frame.toHexString()}")
            return false
        }

        Log.d(TAG, "${writeResponsePending}-收到写响应: ${frame.toHexString()}")
        if (writeResponsePending) {
            writeResponsePending = false
            pendingResponse?.complete(frame)
        }
        return true
    }

    // ── Modbus RTU 帧工具 ──────────────────────────────

    private fun calcCrc(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                val lsb = crc and 0x0001
                crc = crc shr 1
                if (lsb == 1) crc = crc xor 0xA001
            }
        }
        return crc
    }

    private fun addCrc(data: ByteArray): ByteArray {
        val crc = calcCrc(data)
        return data + (crc and 0xFF).toByte() + ((crc shr 8) and 0xFF).toByte()
    }

    private fun verifyCrc(frame: ByteArray): Boolean {
        if (frame.size < 3) return false
        val data = frame.copyOfRange(0, frame.size - 2)
        val receivedCrc = (frame[frame.size - 2].toInt() and 0xFF) or
                ((frame[frame.size - 1].toInt() and 0xFF) shl 8)
        return calcCrc(data) == receivedCrc
    }

    private fun buildReadCmd(regAddress: Int): ByteArray {
        val body = byteArrayOf(
            DEVICE_ADDR.toByte(),
            0x03,
            (regAddress shr 8).toByte(),
            regAddress.toByte(),
            0x00,
            0x02 // 读取 2 个寄存器（32 位）
        )
        return addCrc(body)
    }

    private fun buildWriteCmd(regAddress: Int, value: Int): ByteArray {
        val body = byteArrayOf(
            DEVICE_ADDR.toByte(),
            0x10,
            (regAddress shr 8).toByte(),
            regAddress.toByte(),
            0x00,
            0x02, // 写 2 个寄存器
            0x04, // 4 字节数据
            ((value shr 24) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )
        return addCrc(body)
    }

    private suspend fun sendAndWait(frame: ByteArray): ByteArray? {
        return withContext(Dispatchers.IO) {
            val deferred = CompletableDeferred<ByteArray>()
            pendingResponse = deferred
            try {
                Log.d(TAG, "发送: ${frame.toHexString()}")
                outputStream?.write(frame)
                outputStream?.flush()
                withTimeout(RESPONSE_TIMEOUT_MS) { deferred.await() }
            } catch (e: Exception) {
                Log.e(TAG, "通信失败: ${e.message}")
                null
            } finally {
                pendingResponse = null
            }
        }
    }

    private fun parseInt32FromReadResponse(response: ByteArray): Int? {
        // [地址][0x03][字节数=4][d0][d1][d2][d3][CRC低][CRC高]
        if (response.size < 9 || response[2].toInt() != 4) return null
        return ((response[3].toInt() and 0xFF) shl 24) or
                ((response[4].toInt() and 0xFF) shl 16) or
                ((response[5].toInt() and 0xFF) shl 8) or
                (response[6].toInt() and 0xFF)
    }

    // ── 业务功能 ────────────────────────────────────────

    private fun startPoll() {
        if (pollJob?.isActive == true) return
        pollJob = lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    pollOnce()
                } catch (e: Exception) {
                    Log.e(TAG, "轮询异常: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
        binding.tvStatus.text = "轮询已启动"
    }

    private fun stopPoll() {
        pollJob?.cancel()
        pollJob = null
        binding.tvStatus.text = "轮询已停止"
    }

    private suspend fun pollOnce() {
        val weightCmd = buildReadCmd(REG_WEIGHT)
        val weightResp = sendAndWait(weightCmd)
        val rawWeight = weightResp?.let { parseInt32FromReadResponse(it) }

        withContext(Dispatchers.Main) {
            if (rawWeight != null) {
                binding.tvWeight.text = "$rawWeight"
                binding.tvStatus.text = "正常读取中"
            } else {
                binding.tvStatus.text = "通信超时"
            }
        }
    }

    private fun calibrateZero() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val cmd = buildWriteCmd(REG_WEIGHT, 0)
                Log.d(TAG, "发送清零: ${cmd.toHexString()}")
                outputStream?.write(cmd)
                outputStream?.flush()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "清零成功", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "清零失败: ${e.message}")
            }
        }
    }

    private fun calibrateSpan() {
        val input = binding.etCalibWeight.text.toString().toIntOrNull()
        if (input == null || input <= -1) {
            Toast.makeText(this, "请输入有效砝码重量(g)", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            stopPoll()
            binding.tvStatus.text = "正在校准..."
            Log.d(TAG, "校准写入值: $input")
            writeResponsePending = true
            val cmd = buildWriteCmd(REG_WEIGHT, input)
            val resp = sendAndWait(cmd)
            writeResponsePending = false
            if (resp != null) {
                binding.tvStatus.text = "校准成功"
                Toast.makeText(this@MainActivity, "校准成功", Toast.LENGTH_SHORT).show()
            } else {
                binding.tvStatus.text = "校准失败（超时）"
                Toast.makeText(this@MainActivity, "校准失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── 工具方法 ────────────────────────────────────────

    private fun ByteArray.toHexString(): String =
        joinToString(" ") { "%02X".format(it) }

    // ── 生命周期 ────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        pollJob?.cancel()
        readJob?.cancel()
        try {
            inputStream?.close()
            outputStream?.close()
            serialPort?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}