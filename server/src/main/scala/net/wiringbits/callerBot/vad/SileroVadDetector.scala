package net.wiringbits.callerBot.vad

import ai.onnxruntime.OrtException
import net.wiringbits.callerBot.audio.AudioFormat

import scala.annotation.tailrec

case class SileroVadDetector(
    model: SileroVadOnnxModel,
    threshold: Float,
    windowSizeSample: Int,
    samplingRate: Int
)

object SileroVadDetector {

  private val samplingRate8k: Int = 8000
  private val samplingRate16k: Int = 16000

  def apply(threshold: Float, format: AudioFormat): SileroVadDetector = {
    val onnxModelPath = "src/main/resources/silero_vad.onnx"

    val samplingRate = format.underlying.getSampleRate.toInt
    if (samplingRate != samplingRate8k && samplingRate != samplingRate16k) {
      throw new IllegalArgumentException(
        "Sampling rate not support, only available for [8000, 16000]"
      )
    }

    val model = SileroVadOnnxModel(onnxModelPath)

    val windowSizeSample: Int =
      if (samplingRate == samplingRate16k)
        512
      else
        256

    reset(model)

    SileroVadDetector(
      model = model,
      threshold = threshold,
      windowSizeSample = windowSizeSample,
      samplingRate = samplingRate
    )

  }

  private def reset(model: SileroVadOnnxModel): Unit = {
    model.resetStates()
  }

  @tailrec
  private def bytesToFloats(
      data: List[Byte],
      acc: List[Float]
  ): Array[Float] = {
    data match {
      case a :: b :: t =>
        val lo: Int = a & 0xff
        val hi: Int = b << 8
        val v: Short = (lo | hi).toShort
        val value = v / 32767.0f
        bytesToFloats(data = t, acc = value :: acc)
      case _ => acc.reverse.toArray
    }
  }

  @throws[OrtException]
  private def isSpeechChunk(
      chunkBytes: Array[Byte],
      sileroVadDetector: SileroVadDetector
  ): Boolean = {
    val pcm = bytesToFloats(data = chunkBytes.toList, acc = Nil)
    val prob = sileroVadDetector.model.call(
      Array[Array[Float]](pcm),
      sileroVadDetector.samplingRate
    )(0)
    prob >= sileroVadDetector.threshold
  }

  /** Determines whether any chunk within the given byte array contains speech.
    *
    * This method splits the input byte array into chunks based on the
    * detector's configured window size, padding the last chunk with zeros if it
    * is shorter than the expected size. It then evaluates each chunk using the
    * Silero VAD model and returns true if any chunk is classified as speech.
    *
    * @param chunkBytes
    *   The raw audio data as an array of bytes, PCM-encoded.
    * @param sileroVadDetector
    *   The instance of SileroVadDetector containing the VAD model and
    *   configuration.
    * @return
    *   True if any chunk contains detected speech above the configured
    *   threshold; false otherwise.
    * @throws OrtException
    *   If there is an error during ONNX model inference.
    */
  @throws[OrtException]
  def isSpeechChunkFull(
      chunkBytes: Array[Byte],
      sileroVadDetector: SileroVadDetector
  ): Boolean = {
    val bytesPerSample = 2
    val targetBytes = sileroVadDetector.windowSizeSample * bytesPerSample
    val chunks = chunkBytes.grouped(targetBytes).toList

    chunks.exists { chunk =>
      val completeChunk =
        if (chunk.length == targetBytes) chunk
        else chunk ++ Array.fill[Byte](targetBytes - chunk.length)(0)
      isSpeechChunk(
        chunkBytes = completeChunk,
        sileroVadDetector = sileroVadDetector
      )
    }
  }

}
