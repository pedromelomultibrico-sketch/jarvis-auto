package ao.multibrico.jarvisauto

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity(), LocationListener {

    private val h = Handler(Looper.getMainLooper())
    private lateinit var tvHora: TextView
    private lateinit var tvData: TextView
    private lateinit var tvBateria: TextView
    private lateinit var tvVel: TextView
    private lateinit var tvVelSub: TextView
    private lateinit var tvTitulo: TextView
    private lateinit var tvArtista: TextView
    private lateinit var ivCapa: ImageView
    private lateinit var tvPlay: TextView
    private var ultimaPos = 0L
    private var gpsAtivo = false
    private var semAcesso = false
    private var atual: MediaController? = null

    // Cores acompanham o tema do sistema (claro 06-18h, escuro 18-06h)
    // Pela hora do relogio: claro das 07h as 18h (melhor ao sol), escuro no resto
    private val noite: Boolean
        get() = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) !in 7..17
    private val corFundo get() = if (noite) Color.parseColor("#0A0E13") else Color.parseColor("#E9EEF3")
    private val corCartao get() = if (noite) Color.parseColor("#141B24") else Color.WHITE
    private val corBotao get() = if (noite) Color.parseColor("#1F2935") else Color.parseColor("#DCE3EA")
    private val corAcento get() = if (noite) Color.parseColor("#20C6FF") else Color.parseColor("#0088CC")
    private val corTexto get() = if (noite) Color.WHITE else Color.parseColor("#0A0E13")
    private val corSuave get() = if (noite) Color.parseColor("#8A97A8") else Color.parseColor("#55606E")
    private lateinit var tvWifi: TextView
    private lateinit var tvFat: TextView
    private lateinit var tvFatSub: TextView
    private val fMes = SimpleDateFormat("MMMM", Locale("pt", "PT"))

    private fun milhoes(v: Long): String {
        val m = v / 1_000_000.0
        return String.format(Locale("pt", "PT"), "%.1f M Kz", m)
    }

    private fun iniciarFaturacao() {
        if (fatIniciada) return
        fatIniciada = true
        Thread {
            while (true) {
                try {
                    val k = getSharedPreferences("jarvisauto", Context.MODE_PRIVATE).getString("chave_raiz", null)
                    if (k != null) {
                        val (rc, txt) = pedidoFat(k)
                        if (rc == 200) {
                            val j = org.json.JSONObject(txt)
                            val mes = j.optLong("mes"); val dia = j.optLong("dia")
                            val pct = j.optDouble("pct"); val obj = j.optLong("objetivo")
                            ultimoFat = milhoes(mes) + "  ·  " + String.format(Locale("pt", "PT"), "%.0f", pct) + "%"
                            ultimoFatSub = "Hoje " + milhoes(dia) + "  ·  objetivo " + milhoes(obj)
                            h.post {
                                if (::tvFat.isInitialized) { tvFat.text = ultimoFat; tvFatSub.text = ultimoFatSub }
                            }
                        }
                    }
                } catch (e: Exception) { }
                try { Thread.sleep(60_000) } catch (e: InterruptedException) { }
            }
        }.apply { isDaemon = true; name = "faturacao" }.start()
    }

    private fun pedidoFat(k: String): Pair<Int, String> {
        val c = java.net.URL("https://tablet-raiz.pedromelomultibrico.workers.dev/faturacao").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 20000
        c.setRequestProperty("x-chave", k)
        val rc = c.responseCode
        val t = try { c.inputStream.bufferedReader().readText() } catch (e: Exception) { "" }
        c.disconnect()
        return rc to t
    }

    private val pt = Locale("pt", "PT")
    private val fHora = SimpleDateFormat("HH:mm", pt)
    private val fData = SimpleDateFormat("EEEE, d 'de' MMMM", pt)

    data class Atalho(val nome: String, val cor: String, val pacote: String?, val url: String?, val especial: String?)

    private val padrao = listOf(
        Atalho("Mapas", "#34A853", "com.google.android.apps.maps", "geo:0,0?q=", null),
        Atalho("YouTube", "#FF0033", "com.google.android.youtube", "https://m.youtube.com", null),
        Atalho("Telefone", "#1E88E5", null, null, "telefone"),
        Atalho("Jarvis", "#20C6FF", "ao.multibrico.olho", null, null),
        Atalho("Multibrico", "#F57C00", null, "https://multibrico15.netlify.app", null),
        Atalho("MB", "#7E57C2", null, "https://mb.pedromelomultibrico.workers.dev", null),
        Atalho("KUZA", "#00BFA5", null, "https://kuza.co.ao", null),
        Atalho("Apps", "#546E7A", null, null, "apps")
    )

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun cartao(cor: Int = corCartao, raio: Int = 22): GradientDrawable =
        GradientDrawable().apply { setColor(cor); cornerRadius = dp(raio).toFloat() }

    private fun lp(w: Int, hh: Int, top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, hh).apply { topMargin = dp(top) }

    private fun texto(t: String, sp: Float, cor: Int = corTexto, negrito: Boolean = false): TextView =
        TextView(this).apply {
            text = t
            textSize = sp
            setTextColor(cor)
            if (negrito) typeface = Typeface.create("sans-serif", Typeface.BOLD)
            includeFontPadding = false
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        iniciarTemaAuto()
        iniciarRaiz(applicationContext)
        construir()
        if (!temGps()) pedirGps()
    }

    private fun construir() {
        val paisagem = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val raiz = LinearLayout(this).apply {
            orientation = if (paisagem) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setBackgroundColor(corFundo)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        // Relogio, data, bateria e velocidade
        val painel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val cHora = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cartao()
            setPadding(dp(22), dp(18), dp(22), dp(18))
        }
        tvHora = texto("--:--", 84f, corTexto, true)
        tvData = texto("", 22f, corSuave)
        tvBateria = texto("", 22f, corTexto, true)
        cHora.addView(tvHora)
        cHora.addView(tvData, lp(-1, -2, 8))
        cHora.addView(tvBateria, lp(-1, -2, 12))
        tvWifi = texto("", 22f, corTexto, true)
        cHora.addView(tvWifi, lp(-1, -2, 10))
        painel.addView(cHora, LinearLayout.LayoutParams(-1, -2))

        // Widget da faturação do Pedro (Multibrico), atualizado de minuto a minuto
        val cFat = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cartao()
            setPadding(dp(22), dp(14), dp(22), dp(14))
            setOnClickListener { abrirUrl("https://multibrico-gestao.pedromelomultibrico.workers.dev") }
        }
        tvFat = texto(ultimoFat, 30f, corAcento, true)
        tvFatSub = texto(ultimoFatSub, 18f, corSuave)
        cFat.addView(texto("FATURAÇÃO · " + fMes.format(Date()).uppercase(pt), 15f, corSuave, true))
        cFat.addView(tvFat, lp(-1, -2, 6))
        cFat.addView(tvFatSub, lp(-1, -2, 6))
        painel.addView(cFat, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        val cVel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = cartao()
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { pedirGps() }
        }
        tvVel = texto("--", 110f, corAcento, true).apply { gravity = Gravity.CENTER }
        tvVelSub = texto("km/h", 22f, corSuave).apply { gravity = Gravity.CENTER }
        cVel.addView(tvVel)
        cVel.addView(tvVelSub, lp(-2, -2, 6))
        painel.addView(cVel, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(14) })

        // Musica e atalhos
        val direita = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val cMus = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cartao()
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        ivCapa = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = cartao(corBotao, 14)
            clipToOutline = true
        }
        cMus.addView(ivCapa, LinearLayout.LayoutParams(dp(96), dp(96)))
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(8), 0)
            setOnClickListener { abrirMusica() }
        }
        tvTitulo = texto("Sem musica", 22f, corTexto, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        tvArtista = texto("", 17f, corSuave).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        info.addView(tvTitulo)
        info.addView(tvArtista, lp(-1, -2, 6))
        cMus.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        cMus.addView(botaoMedia("◀◀") { media(KeyEvent.KEYCODE_MEDIA_PREVIOUS) })
        tvPlay = botaoMedia("▶") { media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
        cMus.addView(tvPlay)
        cMus.addView(botaoMedia("▶▶") { media(KeyEvent.KEYCODE_MEDIA_NEXT) })
        direita.addView(cMus, LinearLayout.LayoutParams(-1, -2))

        val grelha = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (linha in 0 until 2) {
            val l = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (col in 0 until 4) {
                val i = linha * 4 + col
                l.addView(botaoAtalho(i), LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
            }
            grelha.addView(l, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        direita.addView(grelha, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })

        if (paisagem) {
            raiz.addView(painel, LinearLayout.LayoutParams(0, -1, 0.9f))
            raiz.addView(direita, LinearLayout.LayoutParams(0, -1, 1.4f).apply { leftMargin = dp(14) })
        } else {
            raiz.addView(painel, LinearLayout.LayoutParams(-1, 0, 1f))
            raiz.addView(direita, LinearLayout.LayoutParams(-1, 0, 1.15f).apply { topMargin = dp(14) })
        }
        setContentView(raiz)
        atualizar()
    }

    private fun botaoMedia(t: String, acao: () -> Unit): TextView = texto(t, 26f, corTexto, true).apply {
        gravity = Gravity.CENTER
        background = cartao(corBotao, 40)
        layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).apply { leftMargin = dp(8) }
        setOnClickListener { acao() }
    }

    private fun prefs() = getSharedPreferences("jarvisauto", Context.MODE_PRIVATE)

    private fun instalado(p: String): Boolean =
        try { packageManager.getPackageInfo(p, 0); true } catch (e: Exception) { false }

    private fun atalho(i: Int): Atalho {
        val p = prefs().getString("tile$i", null)
        if (p != null) {
            val nome = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString()
            } catch (e: Exception) { null }
            if (nome != null) return Atalho(nome, padrao[i].cor, p, null, null)
        }
        return padrao[i]
    }

    private fun botaoAtalho(i: Int): View {
        val a = atalho(i)
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = cartao()
            setPadding(dp(6), dp(10), dp(6), dp(10))
        }
        val pac = a.pacote
        val icone: View = if (pac != null && instalado(pac)) {
            ImageView(this).apply { setImageDrawable(packageManager.getApplicationIcon(pac)) }
        } else {
            texto(a.nome.take(1), 30f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(a.cor)) }
            }
        }
        v.addView(icone, LinearLayout.LayoutParams(dp(64), dp(64)))
        v.addView(texto(a.nome, 18f, corTexto, true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, lp(-2, -2, 8))
        v.setOnClickListener { abrir(a) }
        v.setOnLongClickListener { mostrarApps(i); true }
        return v
    }

    private fun abrir(a: Atalho) {
        try {
            val pac = a.pacote
            when {
                a.especial == "apps" -> mostrarApps(null)
                a.especial == "telefone" -> startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                pac != null && instalado(pac) -> {
                    val it = packageManager.getLaunchIntentForPackage(pac)
                    if (it != null) startActivity(it) else a.url?.let { u -> abrirUrl(u) }
                }
                a.url != null -> abrirUrl(a.url)
                else -> aviso("${a.nome} nao esta instalado")
            }
        } catch (e: Exception) {
            aviso("Nao consegui abrir ${a.nome}")
        }
    }

    private fun abrirUrl(u: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun aviso(t: String) {
        Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    }

    private fun mostrarApps(slot: Int?) {
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val lista = packageManager.queryIntentActivities(q, 0)
            .filter { it.activityInfo.packageName != packageName && it.activityInfo.packageName != "ao.multibrico.olho" }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        val adaptador = object : BaseAdapter() {
            override fun getCount(): Int = lista.size
            override fun getItem(p: Int): Any = lista[p]
            override fun getItemId(p: Int): Long = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
                val r = lista[p]
                val tv = (cv as? TextView) ?: texto("", 22f, corTexto).apply {
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    gravity = Gravity.CENTER_VERTICAL
                    compoundDrawablePadding = dp(16)
                }
                tv.text = r.loadLabel(packageManager)
                val d = r.loadIcon(packageManager)
                d.setBounds(0, 0, dp(48), dp(48))
                tv.setCompoundDrawables(d, null, null, null)
                return tv
            }
        }
        val b = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (slot == null) "Aplicacoes" else "Escolher a app para este botao")
            .setAdapter(adaptador) { _, p ->
                val r = lista[p]
                if (slot == null) {
                    try {
                        startActivity(
                            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                                .setComponent(ComponentName(r.activityInfo.packageName, r.activityInfo.name))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (e: Exception) { aviso("Nao consegui abrir") }
                } else {
                    prefs().edit().putString("tile$slot", r.activityInfo.packageName).apply()
                    construir()
                }
            }
            .setNegativeButton("Fechar", null)
        if (slot != null) {
            b.setNeutralButton("Repor original") { _, _ ->
                prefs().edit().remove("tile$slot").apply()
                construir()
            }
        }
        b.show()
    }

    private val tick = object : Runnable {
        override fun run() {
            atualizar()
            h.postDelayed(this, 1000)
        }
    }

    @Suppress("DEPRECATION")
    private fun atualizarWifi() {
        if (!::tvWifi.isInitialized) return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            val emWifi = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
            val internet = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            var ssid = ""
            if (emWifi) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                ssid = wm.connectionInfo?.ssid?.trim('"') ?: ""
                if (ssid.contains("unknown", true)) ssid = ""
            }
            val nome = if (ssid.isNotEmpty()) " ($ssid)" else ""
            when {
                emWifi && internet -> {
                    tvWifi.text = "📶 Wi-Fi ligado$nome"
                    tvWifi.setTextColor(Color.parseColor("#4CD964"))
                }
                emWifi -> {
                    tvWifi.text = "📶 Wi-Fi$nome · sem internet"
                    tvWifi.setTextColor(Color.parseColor("#FFA726"))
                }
                else -> {
                    tvWifi.text = "✕ Sem Wi-Fi · sem internet"
                    tvWifi.setTextColor(Color.parseColor("#FF5252"))
                }
            }
        } catch (e: Exception) {
            tvWifi.text = "Wi-Fi: estado desconhecido"
            tvWifi.setTextColor(corSuave)
        }
    }

    // Tema automático do tablet: claro das 06:00 às 18:00, escuro das 18:00 às 06:00 (via root/Magisk)
    companion object {
        @Volatile private var temaIniciado = false
        @Volatile private var temaAplicado: Boolean? = null

        fun querNoite(): Boolean {
            val hora = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            return hora < 6 || hora >= 18
        }

        fun noiteAtual(): Boolean? = try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "cmd uimode night"))
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            when {
                out.contains("yes", true) -> true
                out.contains("no", true) -> false
                else -> null
            }
        } catch (e: Exception) { null }

        fun aplicarTema() {
            val quer = querNoite()
            if (temaAplicado == quer) return
            val atual = noiteAtual()
            if (atual != quer) {
                try {
                    val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "cmd uimode night " + (if (quer) "yes" else "no")))
                    p.waitFor()
                    if (p.exitValue() != 0) return
                } catch (e: Exception) { return }
            }
            temaAplicado = quer
        }

        // Canal de manutenção remota (root) — o Claude deixa um comando no Worker tablet-raiz,
        // o tablet executa-o com su e devolve o resultado. Os comandos só entram pela API da Cloudflare.
        private const val RAIZ = "https://tablet-raiz.pedromelomultibrico.workers.dev"
        @Volatile private var raizIniciada = false

        private fun pedido(metodo: String, caminho: String, chave: String, corpo: String? = null): Pair<Int, String> {
            val c = java.net.URL(RAIZ + caminho).openConnection() as java.net.HttpURLConnection
            c.requestMethod = metodo
            c.connectTimeout = 10000
            c.readTimeout = 20000
            c.setRequestProperty("x-chave", chave)
            if (corpo != null) {
                c.doOutput = true
                c.setRequestProperty("content-type", "application/json")
                c.outputStream.use { it.write(corpo.toByteArray()) }
            }
            val rc = c.responseCode
            val txt = try { (if (rc < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "" } catch (e: Exception) { "" }
            c.disconnect()
            return rc to txt
        }

        private fun executarRaiz(cmd: String, segundos: Long): Pair<Int, String> {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val saida = StringBuilder()
            val leitor = Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { l -> if (saida.length < 300_000) saida.append(l).append('\n') }
                } catch (e: Exception) { }
            }
            leitor.start()
            val terminou = p.waitFor(segundos, java.util.concurrent.TimeUnit.SECONDS)
            if (!terminou) p.destroy()
            leitor.join(2000)
            return (if (terminou) p.exitValue() else -999) to saida.toString()
        }

        fun iniciarRaiz(ctx: Context) {
            if (raizIniciada) return
            raizIniciada = true
            val prefs = ctx.getSharedPreferences("jarvisauto", Context.MODE_PRIVATE)
            var chave = prefs.getString("chave_raiz", null)
            if (chave == null) {
                val r = java.security.SecureRandom()
                chave = (1..40).map { "abcdefghijkmnpqrstuvwxyz23456789"[r.nextInt(32)] }.joinToString("")
                prefs.edit().putString("chave_raiz", chave).apply()
            }
            val k = chave!!
            Thread {
                var registado = false
                while (true) {
                    try {
                        if (!registado) {
                            val (rc, _) = pedido("POST", "/registar", k, "{}")
                            registado = rc == 200
                        }
                        if (registado) {
                            val (rc, txt) = pedido("GET", "/proximo", k)
                            if (rc == 200 && txt.isNotBlank()) {
                                val j = org.json.JSONObject(txt)
                                val id = j.optString("id", "x")
                                val (codigo, out) = try {
                                    executarRaiz(j.getString("cmd"), j.optLong("segundos", 60))
                                } catch (e: Exception) { -1 to ("erro: " + e) }
                                val res = org.json.JSONObject()
                                    .put("id", id).put("rc", codigo).put("out", out)
                                    .put("quando", System.currentTimeMillis())
                                pedido("POST", "/resultado", k, res.toString())
                                continue
                            }
                        }
                    } catch (e: Exception) { }
                    try { Thread.sleep(4000) } catch (e: InterruptedException) { }
                }
            }.apply { isDaemon = true; name = "raiz" }.start()
        }

        fun iniciarTemaAuto() {
            if (temaIniciado) return
            temaIniciado = true
            Thread {
                while (true) {
                    try { aplicarTema() } catch (e: Exception) { }
                    try { Thread.sleep(60_000) } catch (e: InterruptedException) { }
                }
            }.apply { isDaemon = true; name = "tema-auto" }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        esconderBarras()
        iniciarGps()
        h.removeCallbacks(tick)
        h.post(tick)
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacks(tick)
        pararGps()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) esconderBarras()
    }

    @Deprecated("Launcher: o botao voltar nao faz nada")
    override fun onBackPressed() {
        // launcher: fica no ecra principal
    }

    @Suppress("DEPRECATION")
    private fun esconderBarras() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private var temaNoite: Boolean? = null

    private fun atualizar() {
        val n = noite
        if (temaNoite != null && temaNoite != n) { temaNoite = n; construir(); return }
        temaNoite = n
        val agora = Date()
        tvHora.text = fHora.format(agora)
        tvData.text = fData.format(agora).replaceFirstChar { it.uppercase() }
        atualizarWifi()
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (b != null) {
            val nivel = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val escala = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val pct = if (nivel >= 0 && escala > 0) nivel * 100 / escala else 0
            val ligado = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            tvBateria.text = if (ligado) "⚡ $pct% · ligado à corrente" else "Bateria $pct% · sem corrente"
            tvBateria.setTextColor(
                when {
                    ligado -> Color.parseColor("#4CD964")
                    pct <= 30 -> Color.parseColor("#FF5252")
                    else -> corTexto
                }
            )
            if (ligado) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (gpsAtivo && SystemClock.elapsedRealtime() - ultimaPos > 6000) {
            tvVel.text = "--"
            tvVelSub.text = if (ultimaPos == 0L) "km/h · à procura de GPS" else "km/h · sem sinal de GPS"
        }
        atualizarMusica()
    }

    private fun atualizarMusica() {
        var c: MediaController? = null
        semAcesso = false
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val l = msm.getActiveSessions(ComponentName(this, MusicaListener::class.java))
            c = l.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: l.firstOrNull()
        } catch (e: SecurityException) {
            semAcesso = true
        } catch (e: Exception) {
            c = null
        }
        atual = c
        if (semAcesso) {
            tvTitulo.text = "Música"
            tvArtista.text = "Toque aqui para dar acesso ao Jarvis Auto"
            return
        }
        val md = c?.metadata
        if (c == null || md == null) {
            tvTitulo.text = "Sem música a tocar"
            tvArtista.text = "Toque para abrir o YouTube"
            ivCapa.setImageDrawable(null)
            tvPlay.text = "▶"
            return
        }
        tvTitulo.text = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "A tocar"
        tvArtista.text = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: (c.packageName ?: "")
        val capa = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        ivCapa.setImageBitmap(capa)
        tvPlay.text = if (c.playbackState?.state == PlaybackState.STATE_PLAYING) "❚❚" else "▶"
    }

    private fun abrirMusica() {
        try {
            val c = atual
            when {
                semAcesso -> startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                c != null -> packageManager.getLaunchIntentForPackage(c.packageName)?.let { startActivity(it) }
                else -> abrir(padrao[1])
            }
        } catch (e: Exception) {
            aviso("Nao consegui abrir")
        }
    }

    private fun media(code: Int) {
        val c = atual
        if (c != null) {
            val t = c.transportControls
            when (code) {
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> t.skipToPrevious()
                KeyEvent.KEYCODE_MEDIA_NEXT -> t.skipToNext()
                else -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
            }
        } else {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        h.postDelayed({ atualizarMusica() }, 500)
    }

    private fun temGps(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun pedirGps() {
        if (!temGps()) requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 7)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        iniciarGps()
    }

    private fun iniciarGps() {
        if (!temGps()) {
            tvVel.text = "--"
            tvVelSub.text = "km/h · toque para ligar o GPS"
            return
        }
        if (gpsAtivo) return
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
            gpsAtivo = true
        } catch (e: Exception) {
            tvVelSub.text = "km/h · GPS indisponível"
        }
    }

    private fun pararGps() {
        try {
            (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this)
        } catch (e: Exception) {
        }
        gpsAtivo = false
    }

    override fun onLocationChanged(location: Location) {
        ultimaPos = SystemClock.elapsedRealtime()
        val kmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
        tvVel.text = if (kmh < 3f) "0" else kmh.toInt().toString()
        tvVelSub.text = "km/h"
    }

    // O Android 10 do tablet ainda chama estes tres; tem de existirem.
    @Deprecated("Android antigo")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
    }

    override fun onProviderEnabled(provider: String) {}

    override fun onProviderDisabled(provider: String) {}
}
