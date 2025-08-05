package net.wiringbits.callerBot.http

import cats.effect.IO
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.http4s.{HttpRoutes, MediaType}
import scalatags.Text.TypedTag
import scalatags.Text.all.*

class WebRoutes {

  def routes: HttpRoutes[IO] = HttpRoutes.of[IO] { case GET -> Root =>
    Ok(view.render, `Content-Type`(MediaType.text.html))
  }

  // The view is defined using scalatags
  private def view: TypedTag[String] = {
    html(
      lang := "en",
      head(
        meta(charset := "utf-8"),
        meta(
          name := "viewport",
          content := "width=device-width, initial-scale=1"
        ),
        title := "Caller Bot",
        // Link to Bootstrap CDN for styling
        link(
          href := "https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css",
          rel := "stylesheet"
        ),
        // Minimal custom styles
        style := """
            |body { background-color: #f8f9fa; }
            |.container { max-width: 600px; }
            |.card { border: none; box-shadow: 0 4px 6px rgba(0,0,0,.1); }
            |#response-message { margin-top: 1.5rem; }
            |""".stripMargin
      ),
      body(
        cls := "d-flex align-items-center py-4 bg-body-tertiary",
        div(
          cls := "container mt-5",
          div(
            cls := "card p-4 p-md-5",
            h1(cls := "text-center mb-4", "📞 Start a Call"),
            form(
              id := "call-form",
              div(
                cls := "form-floating mb-3",
                input(
                  `type` := "tel",
                  cls := "form-control",
                  id := "phoneNumber",
                  placeholder := "+15551234567",
                  required
                ),
                label(`for` := "phoneNumber", "Phone Number")
              ),
              div(
                cls := "row g-2",
                div(
                  cls := "col-md",
                  div(
                    cls := "form-floating mb-3",
                    input(
                      `type` := "number",
                      cls := "form-control",
                      id := "ringingTimeout",
                      placeholder := "30"
                    ),
                    label(`for` := "ringingTimeout", "Ringing Timeout (s)")
                  )
                ),
                div(
                  cls := "col-md",
                  div(
                    cls := "form-floating mb-3",
                    input(
                      `type` := "number",
                      cls := "form-control",
                      id := "callTimeLimit",
                      placeholder := "90"
                    ),
                    label(`for` := "callTimeLimit", "Call Time Limit (s)")
                  )
                )
              ),
              button(
                `type` := "submit",
                cls := "w-100 btn btn-lg btn-primary",
                "Call"
              )
            ),
            // This div will display the response from the API
            div(id := "response-message")
          )
        ),
        // The client-side JavaScript to handle the form submission
        script(raw(js))
      )
    )
  }

  // JavaScript without .stripMargin, requiring all lines to be flush left
  private val js: String = """
  const form = document.getElementById('call-form');
  const responseMessage = document.getElementById('response-message');

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    responseMessage.innerHTML = '<div class="alert alert-info">Placing call...</div>';

    const phoneNumber = document.getElementById('phoneNumber').value;
    const ringingTimeout = document.getElementById('ringingTimeout').value;
    const callTimeLimit = document.getElementById('callTimeLimit').value;

    const body = { phoneNumber };
    if (ringingTimeout) body.ringingTimeoutSeconds = parseInt(ringingTimeout, 10);
    if (callTimeLimit) body.callTimeLimitSeconds = parseInt(callTimeLimit, 10);

    try {
      const response = await fetch('/api/calls', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      });

      if (response.ok) {
        const data = await response.json();
        responseMessage.innerHTML = `<div class="alert alert-success">✅ Call started successfully! Call ID: <strong>${data.callId}</strong></div>`;
      } else {
        const errorText = await response.text();
        responseMessage.innerHTML = `<div class="alert alert-danger">❌ Error: ${errorText || 'Unknown error'}</div>`;
      }
    } catch (error) {
      responseMessage.innerHTML = `<div class="alert alert-danger">❌ Network Error: ${error.message}</div>`;
    }
  });
  """
}
