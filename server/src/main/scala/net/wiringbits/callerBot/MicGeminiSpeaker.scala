package net.wiringbits.callerBot

import cats.effect.IO
import cats.effect.std.Dispatcher
import fs2.Pipe
import fs2.concurrent.SignallingRef
import net.wiringbits.callerBot.audio.AudioFormat
import net.wiringbits.callerBot.config.GeminiPromptSettings
import net.wiringbits.callerBot.gemini.GeminiService
import net.wiringbits.callerBot.io.{MicSourceProcess, SpeakerSink}
import net.wiringbits.callerBot.vad.SileroVadDetector

class MicGeminiSpeaker(gemini: GeminiService) {
  def run(promptSettings: GeminiPromptSettings): IO[Unit] = {
    val inputFormat = AudioFormat.GeminiInput // compatible with VAD
    val micStream = MicSourceProcess.stream(inputFormat)

    val speaker = SpeakerSink.open(AudioFormat.GeminiOutput)
    Dispatcher.sequential[IO].use { dispatcher =>
      SignallingRef[IO, Boolean](false).flatMap { haltSignal =>
        val end = IO.println("End call detected") >> haltSignal.set(true)
        micStream
          .through(cleanSilence(inputFormat))
          .through(gemini.conversationPipe(dispatcher, promptSettings, end))
          .interruptWhen(haltSignal)
          .foreach { chunk =>
            IO.blocking(speaker.write(chunk.chunk, 0, chunk.chunk.length)).void
          }
          .compile
          .drain
      }
    }
  }

  private def cleanSilence(
      format: AudioFormat
  ): Pipe[IO, Array[Byte], Array[Byte]] = {
    val vad = SileroVadDetector(0.5f, format)
    _.evalMapAccumulate(false) { (wasSpeaking, chunk) =>
      val isSpeaking = SileroVadDetector.isSpeechChunkFull(chunk, vad)

      val logEffect = (wasSpeaking, isSpeaking) match {
        case (false, true) => IO.println("[VAD]: 🎤 Silence -> Voice")
        case (true, false) => IO.println("[VAD]: 🤫 Voice -> Silence")
        case _             => IO.unit // No change, do nothing.
      }

      val outputChunk = if (isSpeaking) chunk else new Array[Byte](chunk.length)
      logEffect.as((isSpeaking, outputChunk))
    }.map(_._2)
  }
}
