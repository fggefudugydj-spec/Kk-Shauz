#!/usr/bin/env python3
"""مجلس النماذج: تطبيق ويب محلي يجمع نماذج Ollama المثبتة في نقاش واحد.

لا يحتاج أي مكتبة خارجية. شغّله ثم يفتح المتصفح تلقائياً.
"""
import json
import os
import threading
import urllib.parse
import urllib.request
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

OLLAMA_BASE = os.environ.get("OLLAMA_BASE", "http://localhost:11434")
PORT = int(os.environ.get("PORT", "8765"))
MAX_TURNS = 12  # الحد الأقصى لعدد الرسائل في النقاش
DONE_MARK = "[تم الحل]"  # يكتبه النموذج عند الاتفاق على الحل
REQUEST_TIMEOUT = 600  # ثوانٍ لكل طلب إلى Ollama

INSTRUCTIONS = (
    "أنت عضو في فريق من نماذج ذكاء اصطناعي يحل مشكلة معاً. "
    "اقرأ النقاش السابق، وأضف ما يفيد: تصحيح خطأ، أو اقتراح، أو تأييد مع سبب. "
    "لا تكرر ما قاله الآخرون. أجب بالعربية وباختصار. "
    f"إذا رأيت أن الحل النهائي مكتمل ومتفق عليه، اكتب {DONE_MARK} في آخر ردك."
)


def http_json(url, payload=None):
    data = None
    headers = {}
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers)
    with urllib.request.urlopen(req, timeout=REQUEST_TIMEOUT) as r:
        return json.loads(r.read().decode("utf-8"))


def list_models():
    """أسماء النماذج المثبتة في Ollama."""
    return [m["model"] for m in http_json(f"{OLLAMA_BASE}/api/tags")["models"]]


def ask(model, messages):
    """يرسل المحادثة المشتركة إلى نموذج ويرجع رده."""
    try:
        out = http_json(
            f"{OLLAMA_BASE}/api/chat",
            {"model": model, "messages": messages, "stream": False},
        )
        return out["message"]["content"].strip()
    except Exception as e:
        return f"[تعذر الرد: {e}]"


def council(problem, models, emit):
    """يدير النقاش بين النماذج المختارة، ويرسل كل حدث عبر emit."""
    transcript = [f"المشكلة: {problem}"]
    turn = 0
    while turn < MAX_TURNS:
        for model in models:
            if turn >= MAX_TURNS:
                break
            emit({"type": "thinking", "model": model})
            messages = [
                {"role": "system", "content": INSTRUCTIONS},
                {
                    "role": "user",
                    "content": "\n\n".join(transcript) + f"\n\nالآن دور {model}. ما ردك؟",
                },
            ]
            reply = ask(model, messages)
            transcript.append(f"[{model}]: {reply}")
            turn += 1
            emit({"type": "reply", "model": model, "text": reply, "turn": turn})
            if DONE_MARK in reply:
                emit({"type": "done", "reason": "agreed"})
                return
    emit({"type": "done", "reason": "limit"})


PAGE = """<!doctype html>
<html lang="ar" dir="rtl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>مجلس النماذج</title>
<style>
  :root { color-scheme: light dark; }
  body { font-family: system-ui, "Segoe UI", Tahoma, sans-serif; margin: 0; background: #f5f5f7; color: #1d1d1f; }
  .wrap { max-width: 860px; margin: 0 auto; padding: 24px 16px 60px; }
  h1 { font-size: 22px; margin: 0 0 16px; }
  .card { background: #fff; border-radius: 14px; padding: 16px; box-shadow: 0 1px 3px rgba(0,0,0,.06); margin-bottom: 16px; }
  .models { display: flex; flex-wrap: wrap; gap: 8px 16px; }
  .models label { display: flex; align-items: center; gap: 6px; background: #f0f0f3; padding: 6px 10px; border-radius: 8px; font-size: 14px; direction: ltr; }
  textarea { width: 100%; min-height: 90px; box-sizing: border-box; border: 1px solid #d2d2d7; border-radius: 10px; padding: 10px; font: inherit; font-size: 15px; resize: vertical; }
  button { background: #0071e3; color: #fff; border: 0; border-radius: 10px; padding: 10px 22px; font-size: 15px; cursor: pointer; margin-top: 10px; }
  button:disabled { background: #8e8e93; cursor: default; }
  .status { font-size: 13px; color: #6e6e73; margin-top: 8px; min-height: 18px; }
  .msg { border-radius: 12px; padding: 12px 14px; margin: 10px 0; white-space: pre-wrap; line-height: 1.7; font-size: 15px; background: #f0f0f3; }
  .msg .who { font-weight: 600; font-size: 13px; color: #0071e3; margin-bottom: 4px; direction: ltr; text-align: right; }
  .msg.final { background: #e8f5e9; }
  .msg.error { background: #fdecea; }
  .hidden { display: none; }
  @media (prefers-color-scheme: dark) {
    body { background: #1c1c1e; color: #f5f5f7; }
    .card { background: #2c2c2e; }
    .models label { background: #3a3a3c; }
    textarea { background: #1c1c1e; color: #f5f5f7; border-color: #48484a; }
    .msg { background: #3a3a3c; }
    .msg.final { background: #1e3a24; }
    .msg.error { background: #4a2020; }
  }
</style>
</head>
<body>
<div class="wrap">
  <h1>مجلس النماذج</h1>
  <div class="card">
    <div id="models" class="models">جارٍ تحميل النماذج...</div>
  </div>
  <div class="card">
    <textarea id="problem" placeholder="اكتب المشكلة التي تريد من النماذج حلها..."></textarea>
    <button id="go">ابدأ النقاش</button>
    <div id="status" class="status"></div>
  </div>
  <div id="log"></div>
</div>
<script>
const $ = (id) => document.getElementById(id);

async function loadModels() {
  try {
    const r = await fetch("/api/models");
    const data = await r.json();
    if (!r.ok) throw new Error(data.error || "خطأ");
    const box = $("models");
    box.textContent = "";
    if (!data.models.length) {
      box.textContent = "لا توجد نماذج مثبتة. حمّل نموذجاً بأمر: ollama pull llama3.2";
      return;
    }
    for (const m of data.models) {
      const label = document.createElement("label");
      const cb = document.createElement("input");
      cb.type = "checkbox";
      cb.value = m;
      cb.checked = true;
      label.append(cb, document.createTextNode(m));
      box.append(label);
    }
  } catch (e) {
    $("models").textContent = "تعذر الاتصال بـ Ollama. تأكد أنه يعمل. (" + e.message + ")";
  }
}

function addMessage(cls, who, text) {
  const div = document.createElement("div");
  div.className = "msg " + cls;
  if (who) {
    const w = document.createElement("div");
    w.className = "who";
    w.textContent = who;
    div.append(w);
  }
  div.append(document.createTextNode(text));
  $("log").append(div);
  div.scrollIntoView({ behavior: "smooth", block: "end" });
}

function handleEvent(ev) {
  if (ev.type === "thinking") {
    $("status").textContent = "⏳ " + ev.model + " يكتب...";
  } else if (ev.type === "reply") {
    addMessage("", ev.model, ev.text);
  } else if (ev.type === "done") {
    $("status").textContent = ev.reason === "agreed" ? "✅ توصل الفريق إلى اتفاق." : "⏹ انتهى الحد الأقصى للجولات.";
  } else if (ev.type === "error") {
    addMessage("error", "خطأ", ev.text);
  }
}

$("go").addEventListener("click", async () => {
  const problem = $("problem").value.trim();
  const models = [...document.querySelectorAll("#models input:checked")].map((c) => c.value);
  if (!problem) { $("status").textContent = "اكتب المشكلة أولاً."; return; }
  if (!models.length) { $("status").textContent = "اختر نموذجاً واحداً على الأقل."; return; }

  $("log").textContent = "";
  addMessage("final", "المشكلة", problem);
  $("go").disabled = true;
  $("status").textContent = "بدأ النقاش...";

  try {
    const url = "/api/run?problem=" + encodeURIComponent(problem) + "&models=" + encodeURIComponent(models.join(","));
    const res = await fetch(url);
    const reader = res.body.getReader();
    const dec = new TextDecoder();
    let buf = "";
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buf += dec.decode(value, { stream: true });
      let i;
      while ((i = buf.indexOf("\\n\\n")) >= 0) {
        const chunk = buf.slice(0, i);
        buf = buf.slice(i + 2);
        if (chunk.startsWith("data: ")) handleEvent(JSON.parse(chunk.slice(6)));
      }
    }
  } catch (e) {
    addMessage("error", "خطأ", e.message);
  } finally {
    $("go").disabled = false;
  }
});

loadModels();
</script>
</body>
</html>
"""


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass  # إخفاء سجل الطلبات الافتراضي

    def _send(self, code, ctype, body):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)

        if u.path == "/":
            self._send(200, "text/html; charset=utf-8", PAGE.encode("utf-8"))

        elif u.path == "/api/models":
            try:
                body = json.dumps({"models": list_models()}, ensure_ascii=False).encode("utf-8")
                self._send(200, "application/json; charset=utf-8", body)
            except Exception as e:
                body = json.dumps({"error": str(e)}, ensure_ascii=False).encode("utf-8")
                self._send(503, "application/json; charset=utf-8", body)

        elif u.path == "/api/run":
            q = urllib.parse.parse_qs(u.query)
            problem = q.get("problem", [""])[0].strip()
            models = [m for m in q.get("models", [""])[0].split(",") if m]
            if not problem or not models:
                self._send(400, "text/plain; charset=utf-8", "بيانات ناقصة".encode("utf-8"))
                return

            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream; charset=utf-8")
            self.send_header("Cache-Control", "no-cache")
            self.end_headers()

            def emit(event):
                line = f"data: {json.dumps(event, ensure_ascii=False)}\n\n"
                self.wfile.write(line.encode("utf-8"))
                self.wfile.flush()

            try:
                council(problem, models, emit)
            except (BrokenPipeError, ConnectionResetError):
                pass  # أغلق المتصفح الاتصال
            except Exception as e:
                try:
                    emit({"type": "error", "text": str(e)})
                except Exception:
                    pass

        else:
            self._send(404, "text/plain; charset=utf-8", "غير موجود".encode("utf-8"))


def main():
    try:
        count = len(list_models())
        print(f"✅ تم الاتصال بـ Ollama ({count} نموذج مثبت).")
    except Exception:
        print("⚠️ تعذر الاتصال بـ Ollama. تأكد أنه يعمل ثم أعد تحميل الصفحة.")

    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    url = f"http://127.0.0.1:{PORT}"
    print(f"مجلس النماذج يعمل على: {url}")
    print("اضغط Ctrl+C للإيقاف.")

    if os.environ.get("NO_BROWSER") != "1":
        threading.Timer(1.0, lambda: webbrowser.open(url)).start()

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nتم الإيقاف.")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
