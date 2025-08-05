package net.wiringbits.callerBot.audio

import javax.sound.sampled.AudioFormat as JAudioFormat

sealed abstract class AudioFormat(val underlying: JAudioFormat)
    extends Product
    with Serializable

object AudioFormat {
  // Twilio sends (uLaw, 8kHz)
  // ffplay -f mulaw -ar 8000 -ac 1 audio.raw
  case object Twilio
      extends AudioFormat(
        new JAudioFormat(
          JAudioFormat.Encoding.ULAW,
          8000, // sampleRate
          8, // sampleSizeInBits
          1, // channels (mono)
          1, // frameSize (bytes per frame: 1 byte for 8-bit mono uLaw)
          8000, // frameRate (frames per second, same as sampleRate for mono)
          false // bigEndian (uLaw is typically not big-endian)
        )
      )

  // This is the target for the FIRST conversion step: uLaw -> PCM 8kHz
  case object IntermediatePcm8kHz
      extends AudioFormat(
        new JAudioFormat(
          JAudioFormat.Encoding.PCM_SIGNED, // Signed PCM
          8000, // Same sample rate as source
          16, // 16-bit depth (common for PCM)
          1, // Mono
          2, // Frame size: 2 bytes (16 bits / 8 bits/byte)
          8000, // Frame rate: 8000
          false // Little-endian is common for PCM (verify Gemini spec)
        )
      )

  // Silero VAD expects 16-bit PCM, 8kHz/16kHz, mono
  // Gemini accepts this, we can reuse it to avoid converting to 2 different formats
  case object GeminiInput
      extends AudioFormat(
        new JAudioFormat(
          JAudioFormat.Encoding.PCM_SIGNED, // Signed PCM
          16000, // target sampleRate (e.g., 16kHz)
          16, // target sampleSizeInBits (e.g., 16-bit)
          1, // target channels (mono)
          2, // frameSize (bytes per frame: 2 bytes for 16-bit mono)
          16000, // frameRate (frames per second, same as sampleRate for mono)
          false // Little-endian is common for PCM
        )
      )

  // Gemini produces 16-bit PCM, 24kHz mono
  // ffplay -f s16le -ar 24000 -ac 1 response.raw
  // "-f s16le": 16-bit signed little-endian PCM
  // "-ar 24000": sample rate 24000 Hz
  // "-ac 1": mono
  case object GeminiOutput
      extends AudioFormat(
        new JAudioFormat(
          JAudioFormat.Encoding.PCM_SIGNED, // Signed PCM
          24000, // target sampleRate (e.g., 24kHz)
          16, // target sampleSizeInBits (e.g., 16-bit)
          1, // target channels (mono)
          2, // frameSize (bytes per frame: 2 bytes for 16-bit mono)
          24000, // frameRate (frames per second, same as sampleRate for mono)
          false // bigEndian (depends on your Gemini spec, usually false for little-endian)
        )
      )
}
