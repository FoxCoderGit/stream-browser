"""Local page with two clips in one player for Android smoke tests."""
import io
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PAGE = b"""<!doctype html><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Stream Browser playback test</title>
<h1>Playback test</h1>
<video controls width="280" height="100"></video>
<p><button id="first">Play first clip</button></p>
<p><button id="second">Play second clip</button></p>
<p><button id="switch">Play first then switch</button></p>
<script>
const player = document.querySelector('video');
function play(id) {
  player.src = '/' + id + '.wav';
  player.play();
}
for (const id of ['first','second']) {
  document.getElementById(id).onclick = () => play(id);
}
document.getElementById('switch').onclick = () => {
  play('first');
  setTimeout(() => play('second'), 10000);
};
</script>"""


def silence():
    buffer = io.BytesIO()
    with wave.open(buffer, 'wb') as clip:
        clip.setparams((1, 1, 8000, 0, 'NONE', 'not compressed'))
        clip.writeframes(bytes([128]) * 80000)
    return buffer.getvalue()


class PlaybackHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == '/':
            content_type, body = 'text/html; charset=utf-8', PAGE
        elif self.path in ('/first.wav', '/second.wav'):
            content_type, body = 'audio/wav', silence()
        else:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == '__main__':
    ThreadingHTTPServer(('127.0.0.1', 8765), PlaybackHandler).serve_forever()
