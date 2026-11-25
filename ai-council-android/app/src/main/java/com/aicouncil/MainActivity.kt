package com.aicouncil

import android.app.Activity
import android.app.AlertDialog
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
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Member(val name: String, val path: String? = null, val uri: Uri? = null)

data class CatalogItem(val title: String, val shortName: String, val file: String, val url: String)

class MainActivity : Activity() {

    companion object {
        const val MAX_TURNS = 6
        const val HISTORY_CHARS = 1500
        const val REPLY_CHARS = 600
        const val DONE_MARK = "[تم الحل]"
        const val REQ_PICK = 1
        const val NANO_PATH = "gemini-nano"
        const val NANO_UNKNOWN = -99
        const val NANO_ERROR = -98
        const val SYSTEM =
            "أنت عضو في فريق نماذج ذكاء اصطناعي يحل مشكلة معاً. " +
            "اقرأ النقاش، وأضف ما يفيد: تصحيحاً أو اقتراحاً أو تأييداً مع سبب. " +
            "أجب بالعربية في جملتين إلى أربع جمل. " +
            "إذا رأيت أن الحل اكتمل واتفق عليه الجميع، اكتب $DONE_MARK في آخر ردك."

        private const val HF = "https://huggingface.co/litert-community"
        val CATALOG = listOf(
            CatalogItem(
                "Qwen2.5 0.5B (خفيف، حوالي 550 ميغابايت)", "Qwen2.5 0.5B",
                "Qwen2.5-0.5B-Instruct_q8.task",
                "$HF/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
            ),
            CatalogItem(
                "Qwen2.5 1.5B (أقوى، حوالي 1.6 غيغابايت)", "Qwen2.5 1.5B",
                "Qwen2.5-1.5B-Instruct_q8.task",
                "$HF/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
            )
        )
    }

    private val pickedMembers = mutableListOf<Member>()
    private lateinit var modelsLabel: TextView
    private lateinit var problemInput: EditText
    private lateinit var startButton: Button
    private lateinit var statusView: TextView
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView

    @Volatile private var running = false
    @Volatile private var stopRequested = false
    @Volatile private var downloading = false
    @Volatile private var nanoStatus = NANO_UNKNOWN
    private val nano by lazy { Generation.getClient() }

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
        val subtitle = TextView(this).apply {
            text = "نماذج تعمل على هاتفك فقط، بدون مفاتيح. التنزيل يحتاج إنترنت لمرة واحدة."
            textSize = 13f
        }
        modelsLabel = TextView(this)
        val nanoButton = Button(this).apply {
            text = "Gemini Nano (مدمج في الهاتف)"
            setOnClickListener { nanoAction() }
        }
        val downloadButton = Button(this).apply {
            text = "تنزيل نموذج مجاني"
            setOnClickListener { downloadDialog() }
        }
        val pickButton = Button(this).apply {
            text = "إضافة نموذج من ملف على الهاتف"
            setOnClickListener { pickModels() }
        }
        val clearButton = Button(this).apply {
            text = "حذف النماذج"
            setOnClickListener { confirmClear() }
        }
        problemInput = EditText(this).apply {
            hint = "اكتب المشكلة التي تريد حلها..."
            minLines = 3
        }
        startButton = Button(this).apply {
            text = "ابدأ النقاش"
            setOnClickListener {
                if (running) {
                    stopRequested = true
                    setStatus("جارٍ الإيقاف بعد الرد الحالي...")
                } else {
                    startCouncil()
                }
            }
        }
        statusView = TextView(this)
        chat = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(chat) }

        root.addView(title)
        root.addView(subtitle)
        root.addView(modelsLabel)
        root.addView(nanoButton)
        root.addView(downloadButton)
        root.addView(pickButton)
        root.addView(clearButton)
        root.addView(problemInput)
        root.addView(startButton)
        root.addView(statusView)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        refreshLabel()
        checkNano()
    }

    // ---------- الأعضاء ----------

    private fun modelsDir() = File(filesDir, "models").apply { mkdirs() }

    private fun downloadedMembers(): List<Member> =
        CATALOG.filter { File(modelsDir(), it.file).exists() }
            .map { Member(it.shortName, File(modelsDir(), it.file).absolutePath) }

    private fun allMembers(): List<Member> {
        val nanoList = if (nanoStatus == FeatureStatus.AVAILABLE) listOf(Member("Gemini Nano", NANO_PATH)) else emptyList()
        return nanoList + downloadedMembers() + pickedMembers
    }

    private fun refreshLabel() = runOnUiThread {
        val all = allMembers()
        val nanoLine = when (nanoStatus) {
            FeatureStatus.AVAILABLE -> "Gemini Nano: جاهز ✓"
            FeatureStatus.DOWNLOADABLE -> "Gemini Nano: مدعوم، اضغط الزر لتنزيله"
            FeatureStatus.DOWNLOADING -> "Gemini Nano: قيد التنزيل"
            FeatureStatus.UNAVAILABLE -> "Gemini Nano: غير مدعوم في هذا الهاتف"
            NANO_UNKNOWN -> "Gemini Nano: جارٍ الفحص..."
            else -> "Gemini Nano: تعذر الفحص"
        }
        val members = if (all.isEmpty()) {
            "لا توجد نماذج بعد. اضغط تنزيل نموذج مجاني."
        } else {
            "الأعضاء: " + all.joinToString("، ") { it.name }
        }
        modelsLabel.text = nanoLine + "\n" + members
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("حذف النماذج")
            .setMessage("سيُحذف كل ما نزّلته ولن تتمكن من استخدامه إلا بتنزيله مجدداً. متأكد؟")
            .setPositiveButton("حذف") { _, _ ->
                modelsDir().listFiles()?.forEach { it.delete() }
                cacheDir.listFiles()?.filter { it.name.startsWith("model_") }?.forEach { it.delete() }
                pickedMembers.clear()
                refreshLabel()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ---------- Gemini Nano (مدمج في بعض الهواتف) ----------

    private fun checkNano() {
        Thread {
            try {
                nanoStatus = runBlocking { nano.checkStatus() }
            } catch (e: Throwable) {
                nanoStatus = NANO_ERROR
            }
            refreshLabel()
        }.start()
    }

    private fun nanoAction() {
        when (nanoStatus) {
            FeatureStatus.AVAILABLE -> setStatus("Gemini Nano جاهز، وسيشارك في النقاش تلقائياً")
            FeatureStatus.UNAVAILABLE -> setStatus("هاتفك لا يدعم Gemini Nano. استخدم النماذج المنزّلة بدلاً منه.")
            FeatureStatus.DOWNLOADABLE -> downloadNano()
            FeatureStatus.DOWNLOADING -> setStatus("Gemini Nano قيد التنزيل من نظام الهاتف، انتظر قليلاً")
            else -> { setStatus("جارٍ إعادة الفحص..."); checkNano() }
        }
    }

    private fun downloadNano() {
        if (downloading) { setStatus("يوجد تنزيل جارٍ، انتظر حتى ينتهي"); return }
        downloading = true
        Thread {
            try {
                runBlocking {
                    nano.download().collect { st ->
                        when (st) {
                            is DownloadStatus.DownloadProgress ->
                                setStatus("تنزيل Gemini Nano: ${st.totalBytesDownloaded / 1048576} ميغابايت")
                            is DownloadStatus.DownloadFailed ->
                                throw IllegalStateException("فشل تنزيل Gemini Nano")
                            else -> {}
                        }
                    }
                }
                setStatus("✅ تم تجهيز Gemini Nano")
            } catch (e: Throwable) {
                setStatus("⚠️ ${e.message ?: e.toString()}")
            } finally {
                downloading = false
                checkNano()
            }
        }.start()
    }

    private fun askNano(prompt: String): String {
        val text = runBlocking { nano.generateContent(prompt).candidates[0].text }
        return text.trim().ifEmpty { "[رد فارغ]" }
    }

    // ---------- تنزيل نموذج مجاني ----------

    private fun downloadDialog() {
        if (downloading) { setStatus("يوجد تنزيل جارٍ، انتظر حتى ينتهي"); return }
        val items = CATALOG.map {
            it.title + if (File(modelsDir(), it.file).exists()) "  ✓ منزّل" else ""
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("اختر نموذجاً للتنزيل")
            .setItems(items) { _, which ->
                val item = CATALOG[which]
                if (File(modelsDir(), item.file).exists()) {
                    setStatus("هذا النموذج منزّل بالفعل")
                } else {
                    startDownload(item)
                }
            }
            .show()
    }

    private fun startDownload(item: CatalogItem) {
        downloading = true
        Thread {
            val dir = modelsDir()
            val target = File(dir, item.file)
            val part = File(dir, item.file + ".part")
            try {
                val conn = URL(item.url).openConnection() as HttpURLConnection
                conn.connectTimeout = 30000
                conn.readTimeout = 60000
                conn.setRequestProperty("User-Agent", "AICouncil/1.0")
                val code = conn.responseCode
                if (code == 401 || code == 403) {
                    throw IllegalStateException(
                        "الموقع رفض التنزيل (رمز $code). قد يتطلب النموذج تسجيل دخول أو موافقة على الترخيص."
                    )
                }
                if (code !in 200..299) throw IllegalStateException("فشل التنزيل، رمز $code")
                val total = conn.contentLengthLong
                var done = 0L
                var last = 0L
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - last > 700) {
                                last = now
                                val mb = done / 1048576
                                val totalText = if (total > 0) " من ${total / 1048576}" else ""
                                setStatus("جارٍ تنزيل ${item.shortName}: $mb$totalText ميغابايت (أبقِ التطبيق مفتوحاً)")
                            }
                        }
                    }
                }
                if (total > 0 && done < total) throw IllegalStateException("انقطع التنزيل قبل أن يكتمل")
                if (!part.renameTo(target)) throw IllegalStateException("تعذر حفظ الملف")
                setStatus("✅ تم تنزيل ${item.shortName}")
            } catch (e: Throwable) {
                part.delete()
                setStatus("⚠️ ${e.message ?: e.toString()}")
            } finally {
                downloading = false
                refreshLabel()
            }
        }.start()
    }

    // ---------- نموذج من ملف ----------

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
            val clip = data.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) addPicked(clip.getItemAt(i).uri)
            } else {
                data.data?.let { addPicked(it) }
            }
            refreshLabel()
        }
    }

    private fun addPicked(uri: Uri) {
        pickedMembers.add(Member(displayName(uri), null, uri))
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) return c.getString(idx)
        }
        return uri.lastPathSegment ?: "نموذج"
    }

    // ---------- النقاش ----------

    private fun startCouncil() {
        val problem = problemInput.text.toString().trim()
        val members = allMembers()
        if (downloading) { setStatus("انتظر حتى ينتهي التنزيل"); return }
        if (problem.isEmpty()) { setStatus("اكتب المشكلة أولاً"); return }
        if (members.isEmpty()) { setStatus("نزّل نموذجاً واحداً على الأقل"); return }

        running = true
        stopRequested = false
        startButton.text = "إيقاف"
        chat.removeAllViews()
        addBubble("المشكلة", problem)
        Thread { runCouncil(problem, members) }.start()
    }

    private fun runCouncil(problem: String, members: List<Member>) {
        try {
            val transcript = mutableListOf("المشكلة: " + problem.take(600))
            val limit = if (members.size == 1) 1 else MAX_TURNS
            var turn = 0
            var agreed = false

            outer@ while (turn < limit) {
                for (m in members) {
                    if (turn >= limit || stopRequested) break@outer
                    setStatus("يكتب ${m.name}... (قد يستغرق دقيقة)")
                    val reply = try {
                        ask(m, transcript)
                    } catch (e: Throwable) {
                        "[تعذر الرد: ${e.message ?: e.toString()}]"
                    }
                    transcript.add("[${m.name}]: " + reply.take(REPLY_CHARS))
                    turn++
                    showBubble(m.name, reply)
                    if (reply.contains(DONE_MARK)) {
                        agreed = true
                        break@outer
                    }
                }
            }
            setStatus(
                when {
                    stopRequested -> "⏹ تم الإيقاف"
                    agreed -> "✅ توصل الفريق إلى اتفاق"
                    else -> "⏹ انتهى النقاش"
                }
            )
        } catch (e: Throwable) {
            showBubble("خطأ", e.message ?: e.toString())
        } finally {
            running = false
            runOnUiThread { startButton.text = "ابدأ النقاش" }
        }
    }

    // يبقي المشكلة وآخر الردود فقط، لأن ذاكرة النموذج الصغير محدودة
    private fun trimHistory(t: List<String>): String {
        val head = t.first()
        var used = head.length
        val keep = ArrayDeque<String>()
        for (i in t.indices.reversed()) {
            if (i == 0) break
            if (used + t[i].length > HISTORY_CHARS) break
            keep.addFirst(t[i])
            used += t[i].length
        }
        return (listOf(head) + keep).joinToString("\n\n")
    }

    private fun ask(m: Member, transcript: List<String>): String {
        val prompt = SYSTEM + "\n\n" + trimHistory(transcript) + "\n\nالآن دورك (${m.name}). ما ردك؟"
        if (m.path == NANO_PATH) return askNano(prompt)
        val path = m.path ?: copyToCache(m.uri!!)
        return askLocal(path, prompt)
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
    private fun askLocal(modelPath: String, prompt: String): String {
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(1280)
            .build()
        val llm = LlmInference.createFromOptions(this, options)
        return try {
            llm.generateResponse(prompt).trim().ifEmpty { "[رد فارغ]" }
        } finally {
            llm.close()
        }
    }

    // ---------- الواجهة ----------

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
