package net.wiringbits.callerBot.twilio

import upickle.default.*

case class TwilioMediaFormat(encoding: String, sampleRate: Int, channels: Int)
    derives ReadWriter
