package com.audiowaveform

import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AudioWaveformModule(context: ReactApplicationContext): ReactContextBaseJavaModule(context) {
    private var extractors = mutableMapOf<String, WaveformExtractor?>()
    private var audioPlayers = mutableMapOf<String, AudioPlayer?>()
    private var audioRecorder: AudioRecorder = AudioRecorder()
    private var recorder: MediaRecorder? = null
    private var encoder: Int = 0
    private var path: String? = null
    private var outputFormat: Int = 0
    private var sampleRate: Int = 44100
    private var bitRate: Int = 128000
    private val handler = Handler(Looper.getMainLooper())
    private var startTime: Long = 0

    companion object {
        const val NAME = "AudioWaveform"
        const val MAX_NUMBER_OF_AUDIO_PLAYER = 30
    }

    override fun getName(): String {
        return NAME
    }

    @ReactMethod
    fun markPlayerAsUnmounted() {
        audioPlayers.values.forEach { player ->
            player?.markPlayerAsUnmounted()
        }
    }


    @ReactMethod
    fun checkHasAudioRecorderPermission(promise: Promise) {
        try {
            promise.resolve(audioRecorder.checkPermission(currentActivity))
        }
        catch (err: Exception) {
            promise.reject("checkHasAudioRecorderPermission-error", err.toString())
        }
    }

    @ReactMethod
    fun getAudioRecorderPermission(promise: Promise) {
        try {
            promise.resolve(audioRecorder.getPermission(currentActivity))
        }
        catch (err: Exception) {
            promise.reject("getAudioRecorderPermission-error", err.toString())
        }
    }

    @ReactMethod
    fun initRecorder(obj: ReadableMap?, promise: Promise) {
        checkPathAndInitialiseRecorder(encoder, outputFormat, sampleRate, bitRate, promise, obj)
    }

    private fun getDecibel(): Double? {
        return audioRecorder.getDecibel(recorder)
    }

    @ReactMethod
    fun getDecibel(promise: Promise) {
        try {
            promise.resolve(audioRecorder.getDecibel(recorder))
        }
        catch (err: Exception) {
            promise.reject("getDecibel-error", err.toString())
        }
    }

    @ReactMethod
    fun startRecording(obj: ReadableMap?, promise: Promise) {
        initRecorder(obj, promise)
        val useLegacyNormalization = true
        audioRecorder.startRecorder(recorder, useLegacyNormalization, promise)
        startTime = System.currentTimeMillis() // Initialize startTime
        startEmittingRecorderValue()
    }

    @RequiresApi(Build.VERSION_CODES.N)
    @ReactMethod
    fun pauseRecording(promise: Promise){
        audioRecorder.pauseRecording(recorder, promise)
        stopEmittingRecorderValue()
    }

    @RequiresApi(Build.VERSION_CODES.N)
    @ReactMethod
    fun resumeRecording(promise: Promise){
        audioRecorder.resumeRecording(recorder, promise)
        startEmittingRecorderValue()
    }

    @ReactMethod
    fun stopRecording(promise: Promise) {
        if (recorder == null || path == null) {
            promise.reject("STOP_RECORDING_ERROR", "Recording resources not properly initialized")
            return
        }

        try {
            val currentTime = System.currentTimeMillis()
            if (currentTime - startTime < 500) {
                promise.reject("SHORT_RECORDING", "Recording is too short")
                return
            }

            stopEmittingRecorderValue()
            audioRecorder.stopRecording(recorder, path!!, promise)
            recorder = null
            path = null
        }   catch (e: Exception) {
            Log.e(Constants.LOG_TAG, "Failed to stop recording", e)
            promise.reject("Error", "Failed to stop recording: ${e.message}")
        }
    }

    @ReactMethod
    fun preparePlayer(
        obj: ReadableMap,
        promise: Promise
    ) {
        try {
            if (audioPlayers.filter { it.value?.isHoldingAudioTrack() == true }.count() >= MAX_NUMBER_OF_AUDIO_PLAYER) {
                promise.reject(Constants.LOG_TAG, "Too many players have been initialized. Please stop some players before continuing")
            }

            val path = obj.getString(Constants.path)
            val key = obj.getString(Constants.playerKey)
            val frequency = obj.getInt(Constants.updateFrequency)
            val volume = obj.getInt(Constants.volume)
            val progress = if (!obj.hasKey(Constants.progress) || obj.isNull(Constants.progress)) {
                0 // Set default progress to zero if null, undefined, or missing
            } else {
                obj.getInt(Constants.progress).toLong()
            }

            if (key != null) {
                initPlayer(key)
                val player = audioPlayers[key]
                if(player != null) {
                    player.preparePlayer(
                        path,
                        volume,
                        getUpdateFrequency(frequency),
                        progress,
                        promise
                    )
                }
                else {
                    promise.reject("preparePlayer-error", "No player in the list")
                }
            } else {
                promise.reject("preparePlayer-error", "Player key can't be null")
            }
        } catch (err: Exception) {
            promise.reject("preparePlayer-error", err.toString())
        }
    }

    @ReactMethod
    fun startPlayer(obj: ReadableMap, promise: Promise) {
        try {
            val finishMode = obj.getInt(Constants.finishMode)
            val key = obj.getString(Constants.playerKey)
            val speed = obj.getDouble(Constants.speed)
            if (key != null) {
                val player = audioPlayers[key]
                if (player != null) {
                    promise.resolve(player.start(finishMode, speed.toFloat()))
                } else {
                    promise.reject("startPlayer-error", "No player in the list")
                }
            } else {
                promise.reject("startPlayer-error", "Player key can't be null")
            }
        }
        catch (err: Exception) {
            promise.reject("startPlayer-error", err.toString())
        }
    }

    @ReactMethod
    fun stopPlayer(obj: ReadableMap, promise: Promise) {
        try {
            val key = obj.getString(Constants.playerKey)
            if (key != null) {
                audioPlayers[key]?.stop()
                audioPlayers[key] = null // Release the player after stopping it
                promise.resolve(true);
            } else {
                promise.reject("stopPlayer Error", "Player key can't be null")
            }
        } catch (err: Exception) {
          promise.reject("stopPlayer-error", err.toString())
        }
    }

    @ReactMethod
    fun pausePlayer(obj: ReadableMap, promise: Promise) {
        try {
            val key = obj.getString(Constants.playerKey)
            if (key != null) {
                val player = audioPlayers[key]
                if (player != null) {
                    promise.resolve(player.pause())
                } else {
                    promise.reject("pausePlayer-error", "No player in the list")
                }
            } else {
                promise.reject("pausePlayer-error", "Player key can't be null")
            }
        } catch (err: Exception) {
            promise.reject("pausePlayer-error", err.toString())
        }
    }

    @ReactMethod
    fun seekToPlayer(obj: ReadableMap, promise: Promise) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val progress = obj.getInt(Constants.progress)
                val key = obj.getString(Constants.playerKey)
                if (key != null) {
                    val player = audioPlayers[key]
                    var seeked = false
                    if(player != null) {
                        seeked = player.seekToPosition(progress.toLong())
                    }
                    promise.resolve(seeked);
                } else {
                    promise.reject("seekToPlayer-error", "Player key can't be null")
                }
            } else {
                Log.e(
                    Constants.LOG_TAG,
                    "Minimum android O (26) is required for seekTo function to works"
                )
                promise.resolve(false)
            }
        }
        catch (err: Exception) {
            promise.reject("seekToPlayer-error", "Unable to seek player")
        }
    }

    @ReactMethod
    fun setVolume(obj: ReadableMap, promise: Promise) {
        try {
            val volume = obj.getInt(Constants.volume)
            val key = obj.getString(Constants.playerKey)
            if (key != null) {
                val player = audioPlayers[key]
                if (player != null) {
                    promise.resolve(player.setVolume(volume.toFloat()))
                } else {
                    promise.reject("setVolume-error", "Player not in the list")
                }
            } else {
                promise.reject("setVolume-error", "Player key can't be null")
            }
        } catch (err: Exception) {
            promise.reject("setVolume-error", err.toString())
        }
    }

    @ReactMethod
    fun getDuration(obj: ReadableMap, promise: Promise) {
        try {
            val key = obj.getString(Constants.playerKey)
            val duration = obj.getInt(Constants.durationType)
            val type = if (duration == 0) DurationType.Current else DurationType.Max
            if (key != null) {
                val player = audioPlayers[key]
                if (player != null) {
                    promise.resolve(player.getDuration(type).toString())
                } else {
                    promise.reject("getDuration-error", "Player not in the list")
                }
            } else {
                promise.reject("getDuration-error", "Player key can't be null")
            }
        } catch (err: Exception) {
            promise.reject("getDuration-error", err.toString())
        }
    }

    @ReactMethod
    fun extractWaveformData(obj: ReadableMap, promise: Promise) {
        try {
            val key = obj.getString(Constants.playerKey)
            val path = obj.getString(Constants.path)
            val noOfSamples = obj.getInt(Constants.noOfSamples)
            if (key != null) {
                createOrUpdateExtractor(key, noOfSamples, path, promise)
            } else {
                promise.reject("extractWaveformData-error", "Can not get waveform data Player key is null")
            }
        }
        catch (err: Error) {
            promise.reject("extractWaveformData-error", err.toString())
        }
    }

    private fun stopAllPlayers() {
        for ((key, _) in audioPlayers) {
            audioPlayers[key]?.stop()
            audioPlayers[key] = null
        }
    }
    @ReactMethod
    fun stopAllPlayers(promise: Promise) {
        try {
            stopAllPlayers();
        }
        catch (err:Exception) {
            promise.reject("stopAllPlayers-error", "Error while stopping all players")
        }
    }

    private fun stopAllExtractors() {
        for ((key, _) in extractors) {
            extractors[key]?.stop()
            extractors[key] = null
        }
    }

    @ReactMethod
    fun stopAllWaveFormExtractors(promise: Promise) {
        try {
            stopAllExtractors()
            promise.resolve(true)
        }
        catch (err:Exception) {
            promise.reject("stopAllPlayers-error", "Error while stopping all players")
        }
    }

    @ReactMethod
    fun stopEverything(promise: Promise) {
        try {
            stopAllPlayers()
            stopAllExtractors()
            promise.resolve(true)
        }
        catch (err:Exception) {
            promise.reject("stopAllPlayers-error", "Error while stopping all players")
        }
    }

    @ReactMethod
    fun setPlaybackSpeed(obj: ReadableMap, promise: Promise) {
        try {
            // If the key doesn't exist or if the value is null or undefined, set default speed to 1.0
            val speed = if (!obj.hasKey(Constants.speed) || obj.isNull(Constants.speed)) {
                1.0f // Set default speed to 1.0 if null, undefined, or missing
            } else {
                obj.getDouble(Constants.speed).toFloat()
            }

            val key = obj.getString(Constants.playerKey)
            if (key != null) {
                val player = audioPlayers[key]
                if( player != null ) {
                    promise.resolve(player.setPlaybackSpeed(speed))
                } else {
                    promise.resolve(false)
                }
            } else {
                promise.reject("setPlaybackSpeed Error", "Player key can't be null")
            }
        } catch (err: Exception) {
            promise.reject("setPlaybackSpeed-error", err.toString())
        }
    }

    private fun initPlayer(playerKey: String) {
        if (audioPlayers[playerKey] == null) {
            val newPlayer = AudioPlayer(
                reactApplicationContext,
                playerKey = playerKey,
            )
            audioPlayers[playerKey] = newPlayer
        }
        return
    }

    private fun createOrUpdateExtractor(
        playerKey: String,
        noOfSamples: Int,
        path: String?,
        promise: Promise,
    ) {
        if (path == null) {
            promise.reject("createOrUpdateExtractor Error" , "No Path Provided")
            return
        }
        extractors[playerKey] = WaveformExtractor(
            context = reactApplicationContext,
            path = path,
            expectedPoints = noOfSamples,
            key = playerKey,
            extractorCallBack = object : ExtractorCallBack {
                override fun onProgress(value: Float) {
                     if (value == 1.0F) {
                        extractors[playerKey]?.sampleData?.let { data ->
                            val normalizedData = normalizeWaveformData(data, 0.12f)
                            val tempArrayForCommunication: MutableList<MutableList<Float>> = mutableListOf(normalizedData)
                            promise.resolve(Arguments.fromList(tempArrayForCommunication))
                        }
                    }
                }
                override fun onReject(error: String?, message: String?) {
                    promise.reject(error, message)
                }
                override fun onResolve(value: MutableList<MutableList<Float>>) {
                    promise.resolve(Arguments.fromList(value))
                }

                override fun onStop() {
                    promise.reject("EXTRACTION_STOPPED", "Waveform extraction was stopped")
                }
            }
        )
        extractors[playerKey]?.startDecode();
    }

    private fun normalizeWaveformData(data: MutableList<Float>, scale: Float = 0.25f, threshold: Float = 0.01f): MutableList<Float> {
        val filteredData = data.filter { kotlin.math.abs(it) >= threshold }
        val maxAmplitude = filteredData.maxOrNull() ?: 1.0f
        return if (maxAmplitude > 0) {
            data.map { if (kotlin.math.abs(it) < threshold) 0.0f else (it / maxAmplitude) * scale }.toMutableList()
        } else {
            data
        }
    }

    private fun getUpdateFrequency(frequency: Int?): UpdateFrequency {
        if (frequency == 2) {
            return UpdateFrequency.High
        } else if (frequency == 1) {
            return UpdateFrequency.Medium
        }
        return UpdateFrequency.Low
    }

    private fun checkPathAndInitialiseRecorder(
        encoder: Int,
        outputFormat: Int,
        sampleRate: Int,
        bitRate: Int,
        promise: Promise,
        obj: ReadableMap?
    ) {

        var sampleRateVal = sampleRate;
        var bitRateVal = bitRate;

        if(obj != null) {
            if(obj.hasKey(Constants.bitRate)){
                bitRateVal = obj.getInt(Constants.bitRate);
            }

            if(obj.hasKey(Constants.sampleRate)){
                sampleRateVal = obj.getInt(Constants.sampleRate);
            }
        }

        try {
            recorder = MediaRecorder()
        } catch (e: Exception) {
            Log.e(Constants.LOG_TAG, "Failed to initialise Recorder")
        }
        if (path == null) {
            val outputDir = currentActivity?.cacheDir
            val outputFile: File?
            val dateTimeInstance = SimpleDateFormat(Constants.fileNameFormat, Locale.US)
            val currentDate = dateTimeInstance.format(Date())
            try {
                outputFile = File.createTempFile(currentDate, ".m4a", outputDir)
                path = outputFile.path
                audioRecorder.initRecorder(
                    path!!,
                    recorder,
                    encoder,
                    outputFormat,
                    sampleRateVal,
                    bitRateVal,
                    promise,
                )
            } catch (e: IOException) {
                Log.e(Constants.LOG_TAG, "Failed to create file")
            }
        } else {
            audioRecorder.initRecorder(
                path!!,
                recorder,
                encoder,
                outputFormat,
                sampleRateVal,
                bitRateVal,
                promise,
            )
        }
    }

    private val emitLiveRecordValue = object : Runnable {
        override fun run() {
            val currentDecibel = getDecibel()
            val args: WritableMap = Arguments.createMap()
            if (currentDecibel == Double.NEGATIVE_INFINITY) {
                args.putDouble(Constants.currentDecibel, 0.0)
            } else {
                if (currentDecibel != null) {
                    args.putDouble(Constants.currentDecibel, currentDecibel/1000)
                }
            }
            handler.postDelayed(this, UpdateFrequency.Low.value)
            reactApplicationContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)?.emit(Constants.onCurrentRecordingWaveformData, args)
        }
    }

    private fun startEmittingRecorderValue() {
        handler.postDelayed(emitLiveRecordValue, UpdateFrequency.Low.value)
    }

    private fun stopEmittingRecorderValue() {
        handler.removeCallbacks(emitLiveRecordValue)
    }

}