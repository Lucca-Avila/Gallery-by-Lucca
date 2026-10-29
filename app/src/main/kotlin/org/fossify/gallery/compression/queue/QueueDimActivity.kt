package org.fossify.gallery.compression.queue

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import org.fossify.gallery.R
import org.fossify.gallery.databinding.ActivityQueueDimBinding
import kotlin.random.Random

/**
 * Tela escura de baixo consumo para deixar a fila rodando no OLED: brilho mínimo, fundo preto, texto cinza escuro que muda
 * de lugar de tempos em tempos. Só mostra o estado; o serviço em primeiro plano não depende desta tela. Um toque volta ao normal.
 */
@OptIn(UnstableApi::class)
class QueueDimActivity : AppCompatActivity() {
    private lateinit var binding: ActivityQueueDimBinding
    private val handler = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { reload() }
    private val shift = object : Runnable {
        override fun run() {
            moveContent()
            handler.postDelayed(this, SHIFT_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQueueDimBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.also { it.screenBrightness = MIN_BRIGHTNESS }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        binding.dimRoot.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        QueueRepository.addListener(listener)
        reload()
        handler.post(shift)
    }

    override fun onPause() {
        super.onPause()
        QueueRepository.removeListener(listener)
        handler.removeCallbacks(shift)
    }

    private fun reload() {
        QueueRepository.io(this, { dao -> dao.getAll() }) { all ->
            if (isDestroyed || isFinishing) return@io
            if (!QueueRunner.running) {
                finish()
                return@io
            }
            val running = all.firstOrNull { it.state == QueueItem.STATE_RUNNING }
            binding.dimTitle.text = when {
                running == null -> getString(R.string.cmp_q_preparing)
                running.phase == QueueItem.PHASE_COMPRESSING -> getString(R.string.cmp_q_state_compressing, running.progress)
                else -> getString(R.string.cmp_q_dim_saving)
            }
            binding.dimName.text = running?.name.orEmpty()
            binding.dimEta.text = QueueEta.format(this, QueueEta.remaining(this, all)).orEmpty()
        }
    }

    /** Põe o bloco de texto num ponto aleatório da tela, para nenhum pixel ficar aceso sempre no mesmo lugar. */
    private fun moveContent() {
        val root = binding.dimRoot
        val content = binding.dimContent
        root.post {
            val maxX = (root.width - content.width).coerceAtLeast(0)
            val maxY = (root.height - content.height).coerceAtLeast(0)
            content.translationX = if (maxX > 0) Random.nextInt(maxX).toFloat() else 0f
            content.translationY = if (maxY > 0) Random.nextInt(maxY).toFloat() else 0f
        }
    }

    private companion object {
        /** Quase o mínimo, mas não 0: em alguns aparelhos 0 apaga a luz de fundo por completo. */
        const val MIN_BRIGHTNESS = 0.01f
        const val SHIFT_INTERVAL_MS = 60_000L
    }
}
