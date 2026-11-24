package com.aicouncil

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File

class MainActivity : Activity() {

    companion object {
        const val MAX_TURNS = 12
        const val DONE_MARK = "[تم الحل]"
        const val REQ_PICK = 1
        const val SYSTEM =
            "أنت عضو في فريق من نماذج ذكاء اصطناعي يحل مشكلة معاً. " +
            "اقرأ النقاش السابق، وأضف ما يفيد: تصحيح خطأ، أو اقتراح، أو تأييد مع سبب. " +
            "لا تكرر ما قاله الآخرون. أجب بالعربية وباختصار. " +
            "إذا رأيت أن الحل النهائي مكتمل ومتفق عليه، اكتب $DONE_MARK في آخر ردك."
    }

    private val pickedUris = mutableListOf<Uri>()
    private val pickedNames = mutableListOf<String>()
    private lateinit var modelsLabel: TextView
    private lateinit var problemInput: EditText
    private lateinit var startButton: Button
    private lateinit var statusView: TextView
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val title = TextView(this).apply {
            text = "مجلس النماذج"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
        }
        modelsLabel = TextView(this).apply { text = "لم تختر أي نموذج بعد" }
        val pickButton = Button(this).apply {
            text = "اختر ملفات النماذج"
            setOnClickListener { pickModels() }
        }
        problemInput = EditText(this).apply {
            hint = "اكتب المشكلة التي تريد حلها..."
            minLines = 3
        }
        startButton = Button(this).apply {
            text = "ابدأ النقاش"
            setOnClickListener { startCouncil() }
        }
        statusView = TextView(this)
        chat = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(chat) }

        root.addView(title)
        root.addView(modelsLabel)
        root.addView(pickButton)
        root.addView(problemInput)
        root.addView(startButton)
        root.addView(statusView)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun pickModels() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, REQ_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null) {
            pickedUris.clear()
            pickedNames.clear()
            val clip = data.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) addModel(clip.getItemAt(i).uri)
            } else {
                data.data?.let { addModel(it) }
            }
            modelsLabel.text = "النماذج المختارة: " + pickedNames.joinToString("، ")
        }
    }

    private fun addModel(uri: Uri) {
        pickedUris.add(uri)
        pickedNames.add(displayName(uri))
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) return c.getString(idx)
        }
        return uri.lastPathSegment ?: "نموذج"
    }

    private fun startCouncil() {
        if (running) return
        val problem = problemInput.text.toString().trim()
        if (problem.isEmpty()) { statusView.text = "اكتب المشكلة أولاً"; return }
        if (pickedUris.isEmpty()) { statusView.text = "اختر نموذجاً واحداً على الأقل"; return }

        running = true
        startButton.isEnabled = false
        chat.removeAllViews()
        addBubble("المشكلة", problem)

        val uris = pickedUris.toList()
        val names = pickedNames.toList()
        Thread { runCouncil(problem, uris, names) }.start()
    }

    private fun runCouncil(problem: String, uris: List<Uri>, names: List<String>) {
        try {
            val paths = uris.map { copyToCache(it) }
            val transcript = mutableListOf("المشكلة: $problem")
            var turn = 0
            var agreed = false

            outer@ while (turn < MAX_TURNS) {
                for (i in paths.indices) {
                    if (turn >= MAX_TURNS) break@outer
                    setStatus("يكتب ${names[i]}...")
                    val prompt = SYSTEM + "\n\n" + transcript.joinToString("\n\n") +
                        "\n\nالآن دور ${names[i]}. ما ردك؟"
                    val reply = generate(paths[i], prompt)
                    transcript.add("[${names[i]}]: $reply")
                    turn++
                    showBubble(names[i], reply)
                    if (reply.contains(DONE_MARK)) {
                        agreed = true
                        break@outer
                    }
                }
            }
            setStatus(if (agreed) "✅ توصل الفريق إلى اتفاق" else "⏹ انتهى الحد الأقصى للجولات")
        } catch (e: Throwable) {
            showBubble("خطأ", e.message ?: e.toString())
        } finally {
            running = false
            runOnUiThread { startButton.isEnabled = true }
        }
    }

    // ينسخ الملف إلى مساحة التطبيق لأن MediaPipe يحتاج مساراً حقيقياً
    private fun copyToCache(uri: Uri): String {
        val file = File(cacheDir, "model_${uri.toString().hashCode()}.task")
        if (!file.exists() || file.length() == 0L) {
            contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { input.copyTo(it) }
            } ?: throw IllegalStateException("تعذر فتح الملف")
        }
        return file.absolutePath
    }

    // يحمّل النموذج، يرد، ثم يفرغ الذاكرة قبل النموذج التالي
    private fun generate(modelPath: String, prompt: String): String {
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(1024)
            .build()
        val llm = LlmInference.createFromOptions(this, options)
        return try {
            llm.generateResponse(prompt).trim()
        } catch (e: Exception) {
            "[تعذر الرد: ${e.message}]"
        } finally {
            llm.close()
        }
    }

    private fun setStatus(text: String) = runOnUiThread { statusView.text = text }

    private fun showBubble(who: String, body: String) = runOnUiThread { addBubble(who, body) }

    private fun addBubble(who: String, body: String) {
        val bubble = TextView(this).apply {
            text = "$who\n$body"
            textSize = 15f
            setPadding(24, 20, 24, 20)
            setBackgroundColor(0xFFEFEFF4.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
        }
        chat.addView(bubble)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
