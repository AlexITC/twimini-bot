package net.wiringbits.callerBot.gemini

import cats.effect.*
import cats.effect.std.{Dispatcher, Queue}
import cats.implicits.*
import com.google.genai
import fs2.Pipe
import net.wiringbits.callerBot.config.{Config, GeminiPromptSettings}

import java.util.concurrent.CompletableFuture
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

case class TaggedMessage(turnId: Long, message: genai.types.LiveServerMessage)
case class Transcription(input: String, output: String)
case class GeminiOutputChunk(transcription: Transcription, chunk: Array[Byte])

/** A service that provides a clean, stream-based interface to the Gemini Live
  * API. It follows the "pipeline" model, where the entire bidirectional
  * conversation is represented as a single, composable `fs2.Pipe`.
  */
class GeminiService(config: Config) {

  /** Represents the entire bidirectional conversation with Gemini as a single
    * pipe. It takes a stream of audio bytes from a client (e.g., Twilio) and
    * returns a stream of audio bytes from Gemini.
    *
    * @param promptSettings
    *   The settings defining the bot's persona and language.
    * @param endCall
    *   A callback to be invoked when Gemini's function calling requests to end
    *   the call.
    */
  def conversationPipe(
      dispatcher: Dispatcher[IO],
      promptSettings: GeminiPromptSettings,
      endCall: IO[Unit]
  ): Pipe[IO, Array[Byte], GeminiOutputChunk] = { in =>
    for {
      session <- fs2.Stream.resource(geminiSession(promptSettings))
      currentTurnIdRef <- fs2.Stream.eval(Ref.of[IO, Long](0L))
      fromGeminiStream = fs2.Stream
        .eval(Queue.unbounded[IO, TaggedMessage])
        .flatMap { queue =>
          val subscribe = IO
            .fromCompletableFuture(
              receiveMessages(
                dispatcher,
                session,
                queue,
                currentTurnIdRef,
                endCall
              )
            )
            .void
          // The main stream logic runs concurrently with the subscription.
          // When the main stream ends, the queue is terminated, closing the subscription.
          fs2.Stream
            .fromQueueUnterminated(queue)
            .concurrently(fs2.Stream.exec(subscribe))
        }
      processedOutput: fs2.Stream[IO, GeminiOutputChunk] = fromGeminiStream
        .through(handleInterruptionsPipe(currentTurnIdRef))
        .through(extractAudioPipe)
      toGeminiSink: fs2.Stream[IO, Unit] = in
        .evalMap { chunk =>
          // Send the processed chunk to Gemini
          IO.fromCompletableFuture(sendAudio(session, chunk)).void
        }
      // The output of this `conversationPipe` is the
      //    processed audio from Gemini. The input stream `in` is concurrently
      //    drained into the `toGeminiSink`.
      out <- processedOutput.concurrently(toGeminiSink)
    } yield out
  }

  /** A private resource that safely acquires and releases a Gemini API session.
    */
  private def geminiSession(
      promptSettings: GeminiPromptSettings
  ): Resource[IO, genai.AsyncSession] = {
    val acquire = for {
      client <- IO(genai.Client.builder().apiKey(config.geminiApiKey).build())
      config = GeminiController.makeGeminiConfig(
        prompt = promptSettings.prompt,
        voiceLanguage = promptSettings.language,
        voiceName = promptSettings.voiceName
      )
      session <- IO.fromCompletableFuture(
        IO.blocking(
          client.async.live.connect(promptSettings.model, config)
        )
      )
      _ <- IO.println("✅ Connected to Gemini Live API")
    } yield session

    val release = (session: genai.AsyncSession) =>
      IO.fromCompletableFuture(IO.blocking(session.close())) >>
        IO.println("✅ Gemini session closed")

    Resource.make(acquire)(release)
  }

  /** A pipe that listens for interruption events from Gemini to filter out old
    * messages
    */
  private def handleInterruptionsPipe(currentTurnIdRef: Ref[IO, Long]): Pipe[
    IO,
    TaggedMessage,
    genai.types.LiveServerMessage
  ] = _.evalFilter { taggedMessage =>
    for {
      currentTurn <- currentTurnIdRef.get
    } yield taggedMessage.turnId == currentTurn
  }.map(_.message)

  /** A pipe that extracts raw audio byte chunks from Gemini server messages.
    */
  private val extractAudioPipe
      : Pipe[IO, genai.types.LiveServerMessage, GeminiOutputChunk] = {
    _.flatMap(msg => fs2.Stream.fromOption(msg.serverContent().toScala))
      .flatMap { content =>
        val output = transcriptionToText(content.outputTranscription)
        val input = transcriptionToText(content.inputTranscription)
        val transcription = Transcription(input = input, output = output)
        val data = content.modelTurn.toScala
          .flatMap(_.parts().toScala)
          .map(_.asScala.toSeq)
          .getOrElse(Seq.empty)
          .flatMap(_.inlineData().toScala)
          .flatMap(_.data().toScala)
          .flatten
          .toArray
        val opt =
          Option.when(data.nonEmpty)(GeminiOutputChunk(transcription, data))
        fs2.Stream.fromOption(opt)
      }
  }

  private def transcriptionToText(
      transcription: java.util.Optional[genai.types.Transcription]
  ): String = transcription.toScala.flatMap(_.text().toScala).getOrElse("")

  private def sendAudio(session: genai.AsyncSession, chunk: Array[Byte]) = IO {
    session.sendRealtimeInput(
      genai.types.LiveSendRealtimeInputParameters
        .builder()
        .media(
          genai.types.Blob
            .builder()
            .mimeType("audio/pcm")
            .data(chunk)
        )
        .build
    )
  }

  private def receiveMessages(
      dispatcher: Dispatcher[IO],
      session: genai.AsyncSession,
      queue: Queue[IO, TaggedMessage],
      currentTurnIdRef: Ref[IO, Long],
      endCall: IO[Unit]
  ): IO[CompletableFuture[Void]] = IO {
    session.receive { message =>
      val content = message.serverContent().toScala
      val isInterrupted = content
        .flatMap(_.interrupted().toScala)
        .exists(identity)

      val toolCalls = message
        .toolCall()
        .toScala
        .flatMap(_.functionCalls().toScala)
        .map(_.asScala.toList)
        .getOrElse(List.empty)

      val execCalls = toolCalls.map { call =>
        val nameOpt = call.name().toScala
        println(s"Function call detected: $nameOpt")
        nameOpt match {
          case Some("end_call") =>
            println("Ending call")
            endCall
          case _ => IO.unit
        }
      }.sequence_

      val action = if (isInterrupted) {
        // Increment turn ID
        IO.println(
          "Gemini detected an interruption. Resetting stream state."
        ) >> currentTurnIdRef.updateAndGet(_ + 1)
      } else currentTurnIdRef.get

      // Get the current turn ID and offer the tagged message to the queue
      val run = execCalls >> action.flatMap { id =>
        queue.offer(TaggedMessage(id, message))
      }
      dispatcher.unsafeRunAndForget(run)
    }
  }
}

object GeminiController {
  def makeGeminiConfig(
      prompt: String,
      voiceLanguage: String,
      voiceName: String
  ): genai.types.LiveConnectConfig = {
    val endCallFunctionDefinition = genai.types.FunctionDeclaration
      .builder()
      .name("end_call")
      .description("End call when the user say bye or similar")
      .build()

    val tool = genai.types.Tool
      .builder()
      .functionDeclarations(endCallFunctionDefinition)
      .build()

    genai.types.LiveConnectConfig
      .builder()
      .inputAudioTranscription(
        genai.types.AudioTranscriptionConfig.builder().build()
      )
      .outputAudioTranscription(
        genai.types.AudioTranscriptionConfig.builder().build()
      )
      .responseModalities(genai.types.Modality.Known.AUDIO)
      .systemInstruction(
        genai.types.Content
          .builder()
          .parts(genai.types.Part.builder().text(prompt))
          .build()
      )
      .speechConfig(
        genai.types.SpeechConfig
          .builder()
          .voiceConfig(
            genai.types.VoiceConfig
              .builder()
              .prebuiltVoiceConfig(
                genai.types.PrebuiltVoiceConfig.builder().voiceName(voiceName)
              )
          )
          .languageCode(voiceLanguage)
      )
      .tools(tool)
      .temperature(0.7f)
      //      .enableAffectiveDialog(true) // not supported by a all models
      //      .proactivity(
      //        genai.types.ProactivityConfig.builder().proactiveAudio(true).build()
      //      )
      .build()
  }

}
