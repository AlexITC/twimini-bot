package net.wiringbits.callerBot.io

import net.wiringbits.callerBot.audio.AudioFormat

import javax.sound.sampled.{AudioSystem, DataLine, SourceDataLine}

object SpeakerSink {

  def open(audioFormat: AudioFormat): SourceDataLine = {
    val speakerDataLineInfo =
      new DataLine.Info(classOf[SourceDataLine], audioFormat.underlying)

    if (!AudioSystem.isLineSupported(speakerDataLineInfo)) {
      throw new RuntimeException("Speaker line not supported")
    }

    val speakerDataLine = AudioSystem
      .getLine(speakerDataLineInfo)
      .asInstanceOf[SourceDataLine]
    speakerDataLine.open(audioFormat.underlying)
    speakerDataLine.start()
    speakerDataLine
  }
}
