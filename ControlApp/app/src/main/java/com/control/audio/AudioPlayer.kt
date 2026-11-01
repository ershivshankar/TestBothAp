package com.control.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

class AudioPlayer {

    private val TAG = "AudioPlayer"

    private var micTrack: AudioTrack? = null
    private var deviceAudioTrack: AudioTrack? = null

    fun startMic() {
        stopMic()
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
        val bufSize = maxOf(minBuf, 4096)
        micTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(encoding)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        micTrack?.play()
        Log.d(TAG, "Mic AudioTrack started, bufSize=$bufSize")
    }

    fun stopMic() {
        try {
            micTrack?.stop()
            micTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "stopMic", e)
        }
        micTrack = null
    }

    fun writeMicData(pcm: ByteArray) {
        val track = micTrack ?: return
        try {
            track.write(pcm, 0, pcm.size)
        } catch (e: Exception) {
            Log.e(TAG, "writeMicData", e)
        }
    }

    fun startDeviceAudio() {
        stopDeviceAudio()
        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
        val bufSize = maxOf(minBuf, 8192)
        deviceAudioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(encoding)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        deviceAudioTrack?.play()
        Log.d(TAG, "Device AudioTrack started, bufSize=$bufSize")
    }

    fun stopDeviceAudio() {
        try {
            deviceAudioTrack?.stop()
            deviceAudioTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "stopDeviceAudio", e)
        }
        deviceAudioTrack = null
    }

    fun writeDeviceAudioData(pcm: ByteArray) {
        val track = deviceAudioTrack ?: return
        try {
            track.write(pcm, 0, pcm.size)
        } catch (e: Exception) {
            Log.e(TAG, "writeDeviceAudioData", e)
        }
    }

    fun release() {
        stopMic()
        stopDeviceAudio()
    }
}
