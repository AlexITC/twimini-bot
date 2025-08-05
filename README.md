# 🤖 Twimini-bot

A real-time, conversational AI voice bot built with Twilio, and the Google Gemini Live API.

This application provides a web interface to initiate a phone call that connects to a live, interactive AI.

---

## 🚀 How to Run

Dependencies:

- Install JDK 21 and [sbt](https://www.scala-sbt.org) (check [sdkman](https://sdkman.io))
- Use [Twilio](https://www.twilio.com) to get a phone number.
- [direnv](https://direnv.net) is suggested to load the config variables.
- Run `ngrok http 8080` to expose the app to Twilio ([Ngrok](https://ngrok.com)).


Configuration (create `.envrc` if you use `direnv`, otherwise, load these variables into your shell):

```shell
# Twilio credentials (found in your Twilio Console)
export TWILIO_ACCOUNT_SID="REPLACE_ME"
export TWILIO_AUTH_TOKEN="REPLACE_ME"
export TWILIO_PHONE_NUMBER="REPLACE_ME"

# Get it from Gemini
export GEMINI_API_KEY='REPLACE_ME'

# Get it from ngrok
export BASE_URL="https://REPLACE_ME"
```

Run with:

- `MODE=LOCAL sbt server/run`: To test the integration from your computer's microphone and speakers.
- `MODE=SERVER sbt server/run`: To execute the app with Twilio, then, navigate to `localhost:8080`

