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
  :root {
    --bg: #0b0d12;
    --panel: rgba(255,255,255,.04);
    --line: rgba(255,255,255,.09);
    --text: #e9ecf3;
    --muted: #8a93a6;
    --accent: #7c9cff;
    --accent2: #b28cff;
    --ok: #4ade80;
    --bad: #f87171;
  }
  * { box-sizing: border-box; }
  body {
    margin: 0; min-height: 100vh; color: var(--text);
    font-family: "Segoe UI", Tahoma, system-ui, sans-serif;
    background:
      radial-gradient(900px 500px at 85% -10%, rgba(124,156,255,.18), transparent 60%),
      radial-gradient(700px 500px at -10% 110%, rgba(178,140,255,.14), transparent 60%),
      var(--bg);
  }
  .wrap { max-width: 900px; margin: 0 auto; padding: 36px 18px 80px; }
  header { display: flex; align-items: center; gap: 14px; margin-bottom: 26px; }
  .logo {
    width: 46px; height: 46px; border-radius: 14px; display: grid; place-items: center;
    background: linear-gradient(135deg, var(--accent), var(--accent2));
    box-shadow: 0 8px 30px rgba(124,156,255,.35); font-size: 22px;
  }
  h1 { font-size: 24px; margin: 0; letter-spacing: .2px; }
  .sub { color: var(--muted); font-size: 14px; margin-top: 3px; }
  .panel {
    background: var(--panel); border: 1px solid var(--line); border-radius: 18px;
    padding: 18px; backdrop-filter: blur(10px); margin-bottom: 16px;
  }
  .label { font-size: 13px; color: var(--muted); margin-bottom: 10px; }
  .models { display: flex; flex-wrap: wrap; gap: 10px; }
  .chip {
    display: inline-flex; align-items: center; gap: 8px; cursor: pointer; user-select: none;
    padding: 8px 14px; border-radius: 999px; border: 1px solid var(--line);
    background: rgba(255,255,255,.03); font-size: 14px; direction: ltr; transition: .2s;
  }
  .chip input { display: none; }
  .chip .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--muted); transition: .2s; }
  .chip.on { border-color: rgba(124,156,255,.6); background: rgba(124,156,255,.12); }
  .chip.on .dot { background: var(--accent); box-shadow: 0 0 10px var(--accent); }
  textarea {
    width: 100%; min-height: 96px; resize: vertical; border-radius: 14px; padding: 14px;
    background: rgba(0,0,0,.25); color: var(--text); border: 1px solid var(--line);
    font: inherit; font-size: 15px; line-height: 1.7; outline: none; transition: .2s;
  }
  textarea:focus { border-color: rgba(124,156,255,.6); box-shadow: 0 0 0 4px rgba(124,156,255,.12); }
  .row { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 12px; flex-wrap: wrap; }
  button#go {
    border: 0; cursor: pointer; color: #fff; font: inherit; font-size: 15px; font-weight: 600;
    padding: 12px 26px; border-radius: 14px;
    background: linear-gradient(135deg, var(--accent), var(--accent2));
    box-shadow: 0 8px 24px rgba(124,156,255,.3); transition: transform .15s, opacity .2s;
  }
  button#go:hover { transform: translateY(-1px); }
  button#go:disabled { opacity: .5; cursor: default; transform: none; }
  .status { font-size: 14px; color: var(--muted); }
  .status.ok { color: var(--ok); }
  .status.bad { color: var(--bad); }
  #log { display: flex; flex-direction: column; gap: 12px; }
  .bubble {
    display: flex; gap: 12px; align-items: flex-start; animation: rise .35s ease both;
  }
  .avatar {
    flex: 0 0 38px; height: 38px; border-radius: 12px; display: grid; place-items: center;
    font-weight: 700; font-size: 14px; color: #fff; direction: ltr;
  }
  .card {
    flex: 1; background: var(--panel); border: 1px solid var(--line); border-radius: 16px;
    padding: 14px 16px; line-height: 1.8; font-size: 15px; white-space: pre-wrap; min-width: 0;
  }
  .card .who { font-size: 12px; color: var(--muted); margin-bottom: 6px; direction: ltr; text-align: right; }
  .bubble.user .card { background: rgba(124,156,255,.1); border-color: rgba(124,156,255,.25); }
  .bubble.err .card { background: rgba(248,113,113,.08); border-color: rgba(248,113,113,.3); }
  .bubble.final .card { background: rgba(74,222,128,.08); border-color: rgba(74,222,128,.3); }
  .typing { display: inline-flex; gap: 5px; padding: 6px 2px; }
  .typing i { width: 7px; height: 7px; border-radius: 50%; background: var(--muted); animation: blink 1.2s infinite; }
  .typing i:nth-child(2) { animation-delay: .2s; }
  .typing i:nth-child(3) { animation-delay: .4s; }
  @keyframes blink { 0%,80%,100% { opacity: .2; } 40% { opacity: 1; } }
  @keyframes rise { from { opacity: 0; transform: translateY(8px); } to { opacity: 1; transform: none; } }
  .empty { color: var(--muted); font-size: 14px; }
</style>
</head>
<body>
<div class="wrap">
  <header>
    <div class="logo">✦</div>
    <div>
      <h1>مجلس النماذج</h1>
      <div class="sub">نماذج الذكاء الاصطناعي على جهازك، تتناقش معاً حتى تتفق على الحل</div>
    </div>
  </header>

  <div class="panel">
    <div class="label">النماذج المتاحة على جهازك</div>
    <div id="models" class="models"><span class="empty">جارٍ التحميل...</span></div>
  </div>

  <div class="panel">
    <textarea id="problem" placeholder="اكتب المشكلة التي تريد من النماذج حلها..."></textarea>
    <div class="row">
      <div id="status" class="status"></div>
      <button id="go">ابدأ النقاش</button>
    </div>
  </div>

  <div id="log"></div>
</div>

<script>
const $ = (id) => document.getElementById(id);
const COLORS = ["#7c9cff", "#b28cff", "#4ade80", "#fbbf24", "#f472b6", "#38bdf8", "#fb923c"];
const colorFor = (name) => {
  let h = 0; for (const ch of name) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
  return COLORS[h % COLORS.length];
};
const initials = (name) => name.split(":")[0].slice(0, 2).toUpperCase();

async function loadModels() {
  const box = $("models");
  try {
    const r = await fetch("/api/models");
    const data = await r.json();
    if (!r.ok) throw new Error(data.error || "خطأ");
    box.textContent = "";
    if (!data.models.length) {
      box.innerHTML = '<span class="empty">لا توجد نماذج مثبتة. حمّل نموذجاً بأمر: ollama pull llama3.2</span>';
      return;
    }
    for (const m of data.models) {
      const label = document.createElement("label");
      label.className = "chip on";
      const cb = document.createElement("input");
      cb.type = "checkbox"; cb.value = m; cb.checked = true;
      cb.addEventListener("change", () => label.classList.toggle("on", cb.checked));
      const dot = document.createElement("span"); dot.className = "dot";
      label.append(cb, dot, document.createTextNode(m));
      box.append(label);
    }
  } catch (e) {
    box.innerHTML = '<span class="empty">تعذر الاتصال بـ Ollama. تأكد أنه يعمل ثم حدّث الصفحة.</span>';
  }
}

function addBubble(kind, name, text) {
  const wrap = document.createElement("div");
  wrap.className = "bubble " + kind;
  const av = document.createElement("div");
  av.className = "avatar";
  if (kind === "user") { av.textContent = "أنت"; av.style.background = "#4b5563"; }
  else if (kind === "err") { av.textContent = "!"; av.style.background = "#dc2626"; }
  else if (kind === "final") { av.textContent = "✓"; av.style.background = "#16a34a"; }
  else { av.textContent = initials(name); av.style.background = colorFor(name); }
  const card = document.createElement("div");
  card.className = "card";
  if (name && kind !== "user") {
    const w = document.createElement("div"); w.className = "who"; w.textContent = name;
    card.append(w);
  }
  card.append(document.createTextNode(text));
  wrap.append(av, card);
  $("log").append(wrap);
  wrap.scrollIntoView({ behavior: "smooth", block: "end" });
  return wrap;
}

let typingEl = null;
function setTyping(name) {
  if (typingEl) typingEl.remove();
  typingEl = addBubble("", name, "");
  const card = typingEl.querySelector(".card");
  card.textContent = "";
  const w = document.createElement("div"); w.className = "who"; w.textContent = name;
  const dots = document.createElement("div"); dots.className = "typing";
  dots.innerHTML = "<i></i><i></i><i></i>";
  card.append(w, dots);
}
function clearTyping() { if (typingEl) { typingEl.remove(); typingEl = null; } }

function handleEvent(ev) {
  if (ev.type === "thinking") {
    setTyping(ev.model);
    $("status").textContent = "يكتب " + ev.model + "...";
    $("status").className = "status";
  } else if (ev.type === "reply") {
    clearTyping();
    addBubble("", ev.model, ev.text);
  } else if (ev.type === "done") {
    clearTyping();
    const ok = ev.reason === "agreed";
    $("status").textContent = ok ? "✅ توصل الفريق إلى اتفاق" : "⏹ انتهى الحد الأقصى للجولات";
    $("status").className = "status " + (ok ? "ok" : "");
    if (ok) addBubble("final", "الاتفاق", "تم الوصول إلى حل متفق عليه. راجع الردود أعلاه.");
  } else if (ev.type === "error") {
    clearTyping();
    addBubble("err", "خطأ", ev.text);
  }
}

$("go").addEventListener("click", async () => {
  const problem = $("problem").value.trim();
  const models = [...document.querySelectorAll("#models input:checked")].map((c) => c.value);
  if (!problem) { $("status").textContent = "اكتب المشكلة أولاً"; $("status").className = "status bad"; return; }
  if (!models.length) { $("status").textContent = "اختر نموذجاً واحداً على الأقل"; $("status").className = "status bad"; return; }

  $("log").textContent = "";
  addBubble("user", "", problem);
  $("go").disabled = true;
  $("status").textContent = "بدأ النقاش...";
  $("status").className = "status";

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
      while ((i = buf.indexOf("\n\n")) >= 0) {
        const chunk = buf.slice(0, i);
        buf = buf.slice(i + 2);
        if (chunk.startsWith("data: ")) handleEvent(JSON.parse(chunk.slice(6)));
      }
    }
  } catch (e) {
    clearTyping();
    addBubble("err", "خطأ", e.message);
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
