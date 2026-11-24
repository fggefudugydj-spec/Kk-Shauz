import requests

OLLAMA_BASE = "http://localhost:11434"
MAX_TURNS = 12  # الحد الأقصى لعدد الرسائل في النقاش
DONE_MARK = "[تم الحل]"  # يضعه النموذج عندما يرى أن الحل اكتمل


def get_installed_models():
    """يقرأ أسماء كل النماذج المثبتة على جهازك من Ollama."""
    r = requests.get(f"{OLLAMA_BASE}/api/tags", timeout=10)
    r.raise_for_status()
    return [m["model"] for m in r.json()["models"]]


def ask(model, messages):
    """يرسل المحادثة المشتركة لنموذج ويرجع رده."""
    try:
        r = requests.post(
            f"{OLLAMA_BASE}/api/chat",
            json={"model": model, "messages": messages, "stream": False},
            timeout=600,
        )
        r.raise_for_status()
        return r.json()["message"]["content"].strip()
    except Exception as e:
        return f"[تعذر الرد: {e}]"


def run_council(problem, models):
    transcript = [f"المشكلة: {problem}"]

    instructions = (
        "أنت عضو في فريق من نماذج ذكاء اصطناعي يحل مشكلة معاً. "
        "اقرأ النقاش السابق، وأضف ما يفيد: تصحيح خطأ، أو اقتراح، أو تأييد مع سبب. "
        "لا تكرر ما قاله الآخرون. أجب بالعربية وباختصار. "
        f"إذا رأيت أن الحل النهائي مكتمل ومتفق عليه، اكتب {DONE_MARK} في آخر ردك."
    )

    turn = 0
    while turn < MAX_TURNS:
        for model in models:
            if turn >= MAX_TURNS:
                break
            history = "\n\n".join(transcript)
            messages = [
                {"role": "system", "content": instructions},
                {"role": "user", "content": history + f"\n\nالآن دور {model}. ما ردك؟"},
            ]
            print(f"⏳ {model} يكتب...")
            reply = ask(model, messages)
            transcript.append(f"[{model}]: {reply}")
            print(f"\n[{model}]:\n{reply}\n")
            turn += 1

            if DONE_MARK in reply:
                print("✅ توصل الفريق إلى اتفاق.")
                return transcript

    print("⏹ انتهى الحد الأقصى للجولات.")
    return transcript


if __name__ == "__main__":
    models = get_installed_models()
    if not models:
        print("لا توجد نماذج مثبتة. حمّل نموذجاً بأمر: ollama pull llama3.2")
        raise SystemExit

    print("النماذج المكتشفة:", ", ".join(models))
    problem = input("\nاكتب المشكلة: ").strip()
    run_council(problem, models)
