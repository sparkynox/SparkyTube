package dev.sparkynox.sparkytube.fx

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

// plugs the native engine into ExoPlayer's audio path (16-bit PCM, mono or stereo)
@UnstableApi
class LunoAudioProcessor : BaseAudioProcessor() {

    private var handle = 0L
    private var channels = 2
    private var scratch: ByteBuffer = ByteBuffer.allocateDirect(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (!NativeFx.loaded ||
            inputAudioFormat.encoding != C.ENCODING_PCM_16BIT ||
            inputAudioFormat.channelCount !in 1..2
        ) return AudioProcessor.AudioFormat.NOT_SET

        if (handle == 0L) handle = NativeFx.create()
        if (handle == 0L) return AudioProcessor.AudioFormat.NOT_SET
        channels = inputAudioFormat.channelCount
        NativeFx.configure(handle, inputAudioFormat.sampleRate, channels)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)

        val src: ByteBuffer
        val srcOff: Int
        if (inputBuffer.isDirect) {
            src = inputBuffer
            srcOff = inputBuffer.position()
        } else {
            if (scratch.capacity() < size) {
                scratch = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
            }
            scratch.clear()
            scratch.put(inputBuffer.duplicate())
            src = scratch
            srcOff = 0
        }

        val done = NativeFx.process(handle, src, srcOff, out, out.position(), size, channels)
        if (done != size) {
            // engine refused the buffer, pass the audio through untouched
            out.put(inputBuffer.duplicate())
        } else {
            out.position(out.position() + size)
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    override fun onFlush() {
        NativeFx.reset(handle)
    }

    override fun onReset() {
        NativeFx.destroy(handle)
        handle = 0L
        scratch = ByteBuffer.allocateDirect(0)
    }
}
