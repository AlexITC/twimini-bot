package net.wiringbits.callerBot

import cats.effect.IO
import com.alexitc.geminilive4s.GeminiService
import com.alexitc.geminilive4s.demo.{MicSource, SpeakerSink}
import com.alexitc.geminilive4s.models.{
  AudioStreamFormat,
  GeminiFunction,
  GeminiPromptSettings
}
import com.google.genai
import fs2.Pipe
import fs2.concurrent.SignallingRef
import net.wiringbits.callerBot.audio.AudioFormat
import net.wiringbits.callerBot.config.Config
import net.wiringbits.callerBot.vad.SileroVadDetector

class MicGeminiSpeaker(config: Config) {
  def run(promptSettings: GeminiPromptSettings): IO[Unit] = {
    val audioFormat = AudioStreamFormat.GeminiOutput

    SignallingRef[IO, Boolean](false).flatMap { haltSignal =>
      val end = IO.println("End call detected") >> haltSignal.set(true)
      val functionDef = makeGeminiFunction(end)
      val pipeline = for {
        gemini <- GeminiService.make(
          apiKey = config.geminiApiKey,
          promptSettings = promptSettings,
          functions = List(functionDef)
        )
        micStream = MicSource.stream(audioFormat)
        speaker = SpeakerSink.open(audioFormat)

        _ <- micStream
          .through(gemini.conversationPipe) // mic to gemini
          .interruptWhen(haltSignal)
          .foreach { chunk =>
            // gemini to speaker
            IO.blocking(speaker.write(chunk.chunk, 0, chunk.chunk.length)).void
          }
      } yield ()

      pipeline.compile.drain
    }
  }

  def makeGeminiFunction(haltProcess: IO[Unit]): GeminiFunction = {
    GeminiFunction(
      declaration = genai.types.FunctionDeclaration
        .builder()
        .name("process_completed")
        .description(
          "Complete the process when the user say bye or similar"
        )
        .build(),
      executor = _ =>
        haltProcess.as(Map("response" -> "ok", "scheduling" -> "INTERRUPT"))
    )
  }

  @scala.annotation.nowarn
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
