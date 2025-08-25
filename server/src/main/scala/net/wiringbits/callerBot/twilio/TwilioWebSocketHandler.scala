package net.wiringbits.callerBot.twilio

import cats.effect.*
import cats.implicits.*
import com.alexitc.geminilive4s.GeminiService
import com.alexitc.geminilive4s.models.{
  GeminiFunction,
  GeminiOutputChunk,
  GeminiPromptSettings
}
import com.google.genai
import com.twilio.rest.api.v2010.account.Call
import fs2.Pipe
import fs2.concurrent.SignallingRef
import net.wiringbits.callerBot.audio.AudioConverter
import net.wiringbits.callerBot.config.Config
import org.http4s.websocket.WebSocketFrame
import upickle.default.*

import java.io.ByteArrayInputStream
import java.util.{Base64, UUID}
import scala.concurrent.duration.*

class TwilioWebSocketHandler(config: Config) {

  private val NoData = new Array[Byte](0)

  def audioPipeline(
      callId: UUID,
      twilioCallSid: String,
      promptSettings: GeminiPromptSettings
  ): Pipe[IO, WebSocketFrame, WebSocketFrame] = { incomingFrames =>
    for {
      streamMetadataRef <- fs2.Stream.eval(
        SignallingRef[IO, Option[TwilioStreamMetadata]](None)
      )
      endCall = IO
        .blocking(
          Call
            .updater(twilioCallSid)
            .setStatus(Call.UpdateStatus.COMPLETED)
            .update()
        )
        .void
      geminiService <- GeminiService.make(
        config.geminiApiKey,
        promptSettings,
        List(makeGeminiFunction(endCall))
      )

      frame <- incomingFrames
        .through(receiveFromTwilio(callId, streamMetadataRef))
        .through(transcodeTwilioToGemini)
        .through(geminiService.conversationPipe)
        .through(transcodeGeminiToTwilio)
        .through(sendToTwilio(streamMetadataRef))
    } yield frame
  }

  private def makeGeminiFunction(haltProcess: IO[Unit]): GeminiFunction = {
    GeminiFunction(
      declaration = genai.types.FunctionDeclaration
        .builder()
        .name("process_completed")
        .description("Complete the process when the user say bye or similar")
        .build(),
      executor = _ =>
        haltProcess.as(Map("response" -> "ok", "scheduling" -> "INTERRUPT"))
    )
  }

  // Decode Twilio's JSON frames into raw audio bytes
  private def receiveFromTwilio(
      callId: UUID,
      streamMetadataRef: SignallingRef[IO, Option[TwilioStreamMetadata]]
  ): Pipe[IO, WebSocketFrame, Array[Byte]] = {
    _.evalMap {
      case WebSocketFrame.Text(text, _) =>
        val json = ujson.read(text)
        json("event").str match {
          case "connected" => IO.println("Call connected").as(NoData)
          case "stop"      => IO.println("Call stopped").as(NoData)
          case "start"     =>
            val streamSid = json("start").obj("streamSid").str
            val format = read[TwilioMediaFormat](json("start")("mediaFormat"))
            val metadata = TwilioStreamMetadata(streamSid, format)
            streamMetadataRef.set(Some(metadata)) >>
              IO.println(s"Call started: $format").as(NoData)
          case "media" =>
            IO {
              Base64.getDecoder.decode(json("media")("payload").str)
            }
        }
      case WebSocketFrame.Close(_) =>
        IO.println(s"$callId: WebSocket closed by Twilio").as(NoData)
    }.filter(_.nonEmpty)
  }

  // transcode to twilio format
  private def transcodeTwilioToGemini: Pipe[IO, Array[Byte], Array[Byte]] =
    _.map { chunk =>
      AudioConverter
        .twilioToGeminiStream(new ByteArrayInputStream(chunk))
        .readAllBytes()
    }

  // transcode to twilio format
  private def transcodeGeminiToTwilio: Pipe[IO, GeminiOutputChunk, Byte] =
    _.flatMap(output => fs2.Stream(output.chunk*))
      .chunkN(960) // 20ms of audio from gemini
      .flatMap { chunk =>
        val bytes = AudioConverter
          .geminiToTwilioStream(new ByteArrayInputStream(chunk.toArray))
          .readAllBytes()
        fs2.Stream(bytes*)
      }

  // Encode the output from Gemini back into Twilio's JSON format
  private def sendToTwilio(
      streamMetadataRef: SignallingRef[IO, Option[TwilioStreamMetadata]]
  ): Pipe[IO, Byte, WebSocketFrame] =
    _.chunkN(160) // 20ms of mulaw audio for Twilio
      .meteredStartImmediately(20.milliseconds)
      .evalMap { chunk => streamMetadataRef.get.map(_ -> chunk) }
      .collect { case (Some(metadata), chunk) =>
        val twilioSid = metadata.streamSid
        val base64Payload = Base64.getEncoder.encodeToString(chunk.toArray)
        val twilioMessage =
          s"""
             |{
             |  "event": "media",
             |  "streamSid": "$twilioSid",
             |  "media": { "payload": "$base64Payload" }
             |}""".stripMargin.trim
        WebSocketFrame.Text(twilioMessage)
      }
}
