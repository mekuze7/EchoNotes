package com.example.data.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.sqrt

class LiveAudioCapture {
    data class TranscriptionChunk(
        val file: File,
        val startMs: Long,
        val endMs: Long
    )

    companion object {
        private const val TAG = "LiveAudioCapture"
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL_COUNT = 1
        private const val BYTES_PER_SAMPLE = 2
        private const val CHUNK_DURATION_SECONDS = 2
        private const val WAV_HEADER_SIZE = 44
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _amplitudeFlow = MutableStateFlow(0f)
    val amplitudeFlow: StateFlow<Float> = _amplitudeFlow.asStateFlow()

    @Volatile private var isRecording = false
    @Volatile private var isPaused = false
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var outputFile: File? = null
    private var chunkDirectory: File? = null
    private var onChunkReady: ((TranscriptionChunk) -> Unit)? = null
    private var onPcmAudio: ((ByteArray) -> Unit)? = null
    private var emitTranscriptionChunks = true
    private var nextChunkNumber = 0

    fun startRecording(
        outputFile: File,
        chunkDirectory: File,
        onChunkReady: ((TranscriptionChunk) -> Unit)? = null,
        onPcmAudio: ((ByteArray) -> Unit)? = null,
        emitTranscriptionChunks: Boolean = true
    ): Boolean {
        stopRecordingNow()

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize <= 0) {
            Log.e(TAG, "Unsupported microphone recording format: $minBufferSize")
            return false
        }

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBufferSize, SAMPLE_RATE * BYTES_PER_SAMPLE)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not initialize microphone capture", e)
            return false
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            Log.e(TAG, "Microphone capture did not initialize")
            return false
        }

        return try {
            outputFile.parentFile?.mkdirs()
            chunkDirectory.mkdirs()
            RandomAccessFile(outputFile, "rw").use { file ->
                file.setLength(0)
                writeWavHeader(file, 0)
            }
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                recorder.release()
                return false
            }

            this.audioRecord = recorder
            this.outputFile = outputFile
            this.chunkDirectory = chunkDirectory
            this.onChunkReady = onChunkReady
            this.onPcmAudio = onPcmAudio
            this.emitTranscriptionChunks = emitTranscriptionChunks
            nextChunkNumber = 0
            isPaused = false
            isRecording = true
            captureJob = scope.launch { captureAudio(recorder, outputFile, chunkDirectory) }
            true
        } catch (e: Exception) {
            recorder.release()
            Log.e(TAG, "Could not start microphone capture", e)
            false
        }
    }

    fun pauseRecording() {
        if (!isRecording || isPaused) return
        isPaused = true
        try {
            audioRecord?.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Could not pause microphone capture", e)
        }
        _amplitudeFlow.value = 0f
    }

    fun resumeRecording() {
        if (!isRecording || !isPaused) return
        try {
            audioRecord?.startRecording()
            isPaused = false
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Could not resume microphone capture", e)
        }
    }

    suspend fun stopRecording() {
        isRecording = false
        isPaused = false
        try {
            audioRecord?.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Could not stop microphone capture", e)
        }
        captureJob?.join()
        audioRecord?.release()
        audioRecord = null
        captureJob = null
        onChunkReady = null
        onPcmAudio = null
        _amplitudeFlow.value = 0f
    }

    fun stopRecordingNow() {
        isRecording = false
        isPaused = false
        try {
            audioRecord?.stop()
        } catch (_: IllegalStateException) {
        }
        audioRecord?.release()
        audioRecord = null
        captureJob?.cancel()
        captureJob = null
        onChunkReady = null
        onPcmAudio = null
        _amplitudeFlow.value = 0f
    }

    private suspend fun captureAudio(recorder: AudioRecord, file: File, chunkDir: File) {
        val readBuffer = ByteArray(4_096)
        val chunkBuffer = ByteArrayOutputStream()
        val chunkTargetBytes = SAMPLE_RATE * BYTES_PER_SAMPLE * CHUNK_DURATION_SECONDS
        var totalBytes = 0
        var amplitudeSamples = 0
        var amplitudeSum = 0.0
        var amplitudeCount = 0

        try {
            RandomAccessFile(file, "rw").use { output ->
                output.seek(WAV_HEADER_SIZE.toLong())

                while (isRecording) {
                    if (isPaused) {
                        delay(50)
                        continue
                    }

                    val bytesRead = recorder.read(readBuffer, 0, readBuffer.size)
                    if (bytesRead > 0) {
                        output.write(readBuffer, 0, bytesRead)
                        totalBytes += bytesRead
                        if (emitTranscriptionChunks) {
                            chunkBuffer.write(readBuffer, 0, bytesRead)
                        }
                        onPcmAudio?.invoke(readBuffer.copyOf(bytesRead))

                        var index = 0
                        while (index + 1 < bytesRead) {
                            val sample = ((readBuffer[index + 1].toInt() shl 8) or
                                (readBuffer[index].toInt() and 0xff)).toShort().toInt()
                            amplitudeSum += sample.toDouble() * sample
                            amplitudeCount++
                            index += BYTES_PER_SAMPLE
                        }
                        amplitudeSamples += bytesRead
                        if (amplitudeSamples >= SAMPLE_RATE / 5 * BYTES_PER_SAMPLE) {
                            _amplitudeFlow.value = if (amplitudeCount > 0) {
                                (sqrt(amplitudeSum / amplitudeCount) / Short.MAX_VALUE).toFloat()
                                    .coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                            amplitudeSamples = 0
                            amplitudeSum = 0.0
                            amplitudeCount = 0
                        }

                        if (emitTranscriptionChunks && chunkBuffer.size() >= chunkTargetBytes) {
                            emitChunk(
                                chunkDir,
                                chunkBuffer.toByteArray(),
                                (totalBytes - chunkBuffer.size()).toLong(),
                                totalBytes.toLong()
                            )
                            chunkBuffer.reset()
                        }
                    } else if (bytesRead < 0 && !isPaused && isRecording) {
                        Log.e(TAG, "AudioRecord read failed: $bytesRead")
                        break
                    }
                }

                val finalChunk = if (emitTranscriptionChunks) chunkBuffer.toByteArray() else byteArrayOf()
                if (finalChunk.isNotEmpty()) {
                    emitChunk(
                        chunkDir,
                        finalChunk,
                        (totalBytes - finalChunk.size).toLong(),
                        totalBytes.toLong()
                    )
                }
                output.seek(0)
                writeWavHeader(output, totalBytes)
            }
        } catch (e: Exception) {
            if (isRecording) Log.e(TAG, "Live audio capture failed", e)
        } finally {
            _amplitudeFlow.value = 0f
        }
    }

    private suspend fun emitChunk(directory: File, pcmData: ByteArray, startByte: Long, endByte: Long) {
        val chunkFile = File(directory, "live_caption_${nextChunkNumber++}.wav")
        try {
            withContext(Dispatchers.IO) {
                RandomAccessFile(chunkFile, "rw").use { file ->
                    writeWavHeader(file, pcmData.size)
                    file.write(pcmData)
                }
            }
            val bytesPerSecond = SAMPLE_RATE * BYTES_PER_SAMPLE
            val startMs = startByte * 1_000L / bytesPerSecond
            val endMs = (endByte * 1_000L / bytesPerSecond).coerceAtLeast(startMs + 1L)
            onChunkReady?.invoke(
                TranscriptionChunk(
                    file = chunkFile,
                    startMs = startMs,
                    endMs = endMs
                )
            ) ?: chunkFile.delete()
        } catch (e: Exception) {
            chunkFile.delete()
            Log.e(TAG, "Could not write live transcription chunk", e)
        }
    }

    private fun writeWavHeader(file: RandomAccessFile, dataSize: Int) {
        file.writeBytes("RIFF")
        file.writeInt(Integer.reverseBytes(dataSize + WAV_HEADER_SIZE - 8))
        file.writeBytes("WAVE")
        file.writeBytes("fmt ")
        file.writeInt(Integer.reverseBytes(16))
        file.writeShort(java.lang.Short.reverseBytes(1).toInt())
        file.writeShort(java.lang.Short.reverseBytes(CHANNEL_COUNT.toShort()).toInt())
        file.writeInt(Integer.reverseBytes(SAMPLE_RATE))
        file.writeInt(Integer.reverseBytes(SAMPLE_RATE * CHANNEL_COUNT * BYTES_PER_SAMPLE))
        file.writeShort(java.lang.Short.reverseBytes((CHANNEL_COUNT * BYTES_PER_SAMPLE).toShort()).toInt())
        file.writeShort(java.lang.Short.reverseBytes((BYTES_PER_SAMPLE * 8).toShort()).toInt())
        file.writeBytes("data")
        file.writeInt(Integer.reverseBytes(dataSize))
    }
}
