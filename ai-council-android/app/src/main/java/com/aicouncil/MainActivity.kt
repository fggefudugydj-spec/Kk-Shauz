package com.aicouncil

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Member(
    val name: String,
    val provider: String, // local | claude | openai | gemini
    val uri: Uri? = null,
    val apiKey: String = "",
    val model: String = ""
)

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
        val PROVIDERS = listOf("Claude" to "claude", "ChatGPT" to "openai", "Gemini" to "gemini")
        val DEFAULT_MODEL = mapOf("claude" to "claude-haiku-5-5", "openai" to "", "gemini" to "")
    }

    private val localMembers = mutableListOf<Member>()
    private val cloudMembers = mutableListOf<Member>()
    private lateinit var modelsLabel: TextView
    private lateinit var problemInput: EditText
    private lateinit var startButton: Button
    private lateinit var statusView: TextView
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadCloud()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val title = TextView(this).apply {
            text = "مجلس النماذج"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
        }
        modelsLabel = TextView(this)
        val cloudButton = Button(this).apply {
            text = "إضافة Claude / ChatGPT / Gemini"
            setOnClickListener { addCloudDialog() }
        }
        val pickButton = Button(this).apply {
            text = "إضافة نموذج من ملف على الهاتف"
            setOnClickListener { pickModels() }
        }
        val clearButton = Button(this).apply {
            text = "مسح القائمة"
            setOnClickListener { clearMembers() }
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
        root.addView(cloudButton)
        root.addView(pickButton)
        root.addView(clearButton)
        root.addView(problemInput)
        root.addView(startButton)
        root.addView(statusView)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        refreshLabel()
    }

    // ---------- الأعضاء ----------

    private fun prefs() = getSharedPreferences("council", Context.MODE_PRIVATE)

    private fun cloudMember(provider: String, key: String, model: String): Member {
        val label = PROVIDERS.first { it.second == provider }.first
        return Member("$label ($model)", provider, null, key, model)
    }

    private fun loadCloud() {
        cloudMembers.clear()
        try {
            val arr = JSONArray(prefs().getString("cloud", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                cloudMembers.add(cloudMember(o.getString("provider"), o.getString("key"), o.getString("model")))
            }
        } catch (e: Exception) {
            cloudMembers.clear()
        }
    }

    private fun saveCloud() {
        val arr = JSONArray()
        for (m in cloudMembers) {
            arr.put(JSONObject().put("provider", m.provider).put("key", m.apiKey).put("model", m.model))
        }
        prefs().edit().putString("cloud", arr.toString()).apply()
    }

    private fun refreshLabel() {
        val all = cloudMembers + localMembers
        modelsLabel.text = if (all.isEmpty()) {
            "لم تضف أي نموذج بعد"
        } else {
            "الأعضاء: " + all.joinToString("، ") { it.name }
        }
    }

    private fun clearMembers() {
        cloudMembers.clear()
        localMembers.clear()
        saveCloud()
        refreshLabel()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---------- إضافة نموذج سحابي ----------

    private fun addCloudDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, PROVIDERS.map { it.first }
        )
        val keyInput = EditText(this).apply {
            hint = "مفتاح API"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val modelInput = EditText(this).apply {
            hint = "اسم النموذج (اضغط جلب القائمة)"
            setText(DEFAULT_MODEL["claude"])
        }
        val fetchBtn = Button(this).apply { text = "جلب قائمة النماذج" }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                modelInput.setText(DEFAULT_MODEL[PROVIDERS[pos].second] ?: "")
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        fetchBtn.setOnClickListener {
            val provider = PROVIDERS[spinner.selectedItemPosition].second
            val key = keyInput.text.toString().trim()
            if (key.isEmpty()) {
                toast("أدخل المفتاح أولاً")
            } else {
                fetchBtn.isEnabled = false
                Thread {
                    try {
                        val list = fetchModels(provider, key)
                        runOnUiThread {
                            fetchBtn.isEnabled = true
                            if (list.isEmpty()) {
                                toast("لم أجد نماذج لهذا المفتاح")
                            } else {
                                AlertDialog.Builder(this)
                                    .setTitle("اختر نموذجاً")
                                    .setItems(list.toTypedArray()) { _, which -> modelInput.setText(list[which]) }
                                    .show()
                            }
                        }
                    } catch (e: Throwable) {
                        runOnUiThread {
                            fetchBtn.isEnabled = true
                            toast("تعذر الجلب: ${e.message}")
                        }
                    }
                }.start()
            }
        }

        box.addView(spinner)
        box.addView(keyInput)
        box.addView(fetchBtn)
        box.addView(modelInput)

        AlertDialog.Builder(this)
            .setTitle("إضافة نموذج سحابي")
            .setView(box)
            .setPositiveButton("إضافة") { _, _ ->
                val provider = PROVIDERS[spinner.selectedItemPosition].second
                val key = keyInput.text.toString().trim()
                val model = modelInput.text.toString().trim()
                if (key.isEmpty() || model.isEmpty()) {
                    toast("المفتاح واسم النموذج مطلوبان")
                } else {
                    cloudMembers.add(cloudMember(provider, key, model))
                    saveCloud()
                    refreshLabel()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ---------- نماذج من ملفات ----------

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
                for (i in 0 until clip.itemCount) addLocal(clip.getItemAt(i).uri)
            } else {
                data.data?.let { addLocal(it) }
            }
            refreshLabel()
        }
    }

    private fun addLocal(uri: Uri) {
        localMembers.add(Member(displayName(uri), "local", uri))
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
        if (running) return
        val problem = problemInput.text.toString().trim()
        val members = cloudMembers + localMembers
        if (problem.isEmpty()) { statusView.text = "اكتب المشكلة أولاً"; return }
        if (members.isEmpty()) { statusView.text = "أضف نموذجاً واحداً على الأقل"; return }

        running = true
        startButton.isEnabled = false
        chat.removeAllViews()
        addBubble("المشكلة", problem)
        Thread { runCouncil(problem, members) }.start()
    }

    private fun runCouncil(problem: String, members: List<Member>) {
        try {
            val transcript = mutableListOf("المشكلة: $problem")
            var turn = 0
            var agreed = false

            outer@ while (turn < MAX_TURNS) {
                for (m in members) {
                    if (turn >= MAX_TURNS) break@outer
                    setStatus("يكتب ${m.name}...")
                    val reply = try {
                        ask(m, transcript)
                    } catch (e: Throwable) {
                        "[تعذر الرد: ${e.message}]"
                    }
                    transcript.add("[${m.name}]: $reply")
                    turn++
                    showBubble(m.name, reply)
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

    private fun ask(m: Member, transcript: List<String>): String {
        val history = transcript.joinToString("\n\n") + "\n\nالآن دورك (${m.name}). ما ردك؟"
        return when (m.provider) {
            "claude" -> askClaude(m, history)
            "openai" -> askOpenAI(m, history)
            "gemini" -> askGemini(m, history)
            else -> askLocal(copyToCache(m.uri!!), SYSTEM + "\n\n" + history)
        }
    }

    // ---------- الاتصال بالنماذج السحابية ----------

    private fun http(method: String, url: String, headers: Map<String, String>, body: JSONObject?): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 30000
            conn.readTimeout = 180000
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("رمز $code: " + text.take(300))
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun askClaude(m: Member, history: String): String {
        val body = JSONObject()
            .put("model", m.model)
            .put("max_tokens", 1024)
            .put("system", SYSTEM)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", history)))
        val r = http(
            "POST", "https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to m.apiKey, "anthropic-version" to "2023-06-01"), body
        )
        val content = r.getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val c = content.getJSONObject(i)
            if (c.optString("type") == "text") sb.append(c.getString("text"))
        }
        return sb.toString().trim().ifEmpty { "[رد فارغ]" }
    }

    private fun askOpenAI(m: Member, history: String): String {
        val body = JSONObject()
            .put("model", m.model)
            .put("instructions", SYSTEM)
            .put("input", history)
        val r = http(
            "POST", "https://api.openai.com/v1/responses",
            mapOf("Authorization" to "Bearer ${m.apiKey}"), body
        )
        val out = r.getJSONArray("output")
        val sb = StringBuilder()
        for (i in 0 until out.length()) {
            val item = out.getJSONObject(i)
            if (item.optString("type") == "message") {
                val content = item.getJSONArray("content")
                for (j in 0 until content.length()) {
                    val c = content.getJSONObject(j)
                    if (c.optString("type") == "output_text") sb.append(c.getString("text"))
                }
            }
        }
        return sb.toString().trim().ifEmpty { "[رد فارغ]" }
    }

    private fun askGemini(m: Member, history: String): String {
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", history)))
                )
            )
        val r = http(
            "POST", "https://generativelanguage.googleapis.com/v1beta/models/${m.model}:generateContent",
            mapOf("x-goog-api-key" to m.apiKey), body
        )
        val parts = r.getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
        return sb.toString().trim().ifEmpty { "[رد فارغ]" }
    }

    private fun idsOf(arr: JSONArray, field: String): List<String> =
        (0 until arr.length()).map { arr.getJSONObject(it).getString(field) }

    // يجلب قائمة النماذج المتاحة لهذا المفتاح من الشركة نفسها
    private fun fetchModels(provider: String, key: String): List<String> {
        val list: List<String> = when (provider) {
            "claude" -> {
                val r = http(
                    "GET", "https://api.anthropic.com/v1/models?limit=100",
                    mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"), null
                )
                idsOf(r.getJSONArray("data"), "id")
            }
            "openai" -> {
                val r = http("GET", "https://api.openai.com/v1/models", mapOf("Authorization" to "Bearer $key"), null)
                val all = idsOf(r.getJSONArray("data"), "id")
                val chat = all.filter { it.startsWith("gpt") || it.startsWith("o") }
                if (chat.isEmpty()) all else chat
            }
            else -> {
                val r = http(
                    "GET", "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200",
                    mapOf("x-goog-api-key" to key), null
                )
                val arr = r.getJSONArray("models")
                val out = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val methods = o.optJSONArray("supportedGenerationMethods")
                    var ok = false
                    if (methods != null) {
                        for (j in 0 until methods.length()) {
                            if (methods.getString(j) == "generateContent") ok = true
                        }
                    }
                    if (ok) out.add(o.getString("name").removePrefix("models/"))
                }
                out
            }
        }
        return list.sorted()
    }

    // ---------- النماذج المحلية ----------

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
            .setMaxTokens(1024)
            .build()
        val llm = LlmInference.createFromOptions(this, options)
        return try {
            llm.generateResponse(prompt).trim()
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
