package io.github.audiz

import platform.AVFoundation.AVPlayerItem
import io.github.audiz.dsp.EqualizerEngine

interface IosAudioTapAttacher {
    fun attachTap(playerItem: AVPlayerItem)
}

object IosAudioTapRegistry {
    var attacher: IosAudioTapAttacher? = null

    fun register(attacher: IosAudioTapAttacher) {
        this.attacher = attacher
    }
}

interface IosEqualizerListener {
    fun onEqualizerStateChanged(
        isEnabled: Boolean,
        hpfHz: Float,
        lpfHz: Float,
        gain0: Float,
        gain1: Float,
        gain2: Float,
        gain3: Float,
        gain4: Float,
        gain5: Float,
        gain6: Float,
        gain7: Float,
        gain8: Float,
        gain9: Float
    )
}

object IosEqualizerBridge {
    private var listener: IosEqualizerListener? = null

    init {
        EqualizerEngine.onStateChanged = { state ->
            notifyListener(state)
        }
    }

    fun register(listener: IosEqualizerListener) {
        this.listener = listener
        notifyListener(EqualizerEngine.state.value)
    }

    private fun notifyListener(state: EqualizerEngine.State) {
        val g = state.gains
        val g0 = if (g.isNotEmpty()) g[0] else 0f
        val g1 = if (g.size > 1) g[1] else 0f
        val g2 = if (g.size > 2) g[2] else 0f
        val g3 = if (g.size > 3) g[3] else 0f
        val g4 = if (g.size > 4) g[4] else 0f
        val g5 = if (g.size > 5) g[5] else 0f
        val g6 = if (g.size > 6) g[6] else 0f
        val g7 = if (g.size > 7) g[7] else 0f
        val g8 = if (g.size > 8) g[8] else 0f
        val g9 = if (g.size > 9) g[9] else 0f
        listener?.onEqualizerStateChanged(
            state.isEnabled,
            state.hpfHz,
            state.lpfHz,
            g0, g1, g2, g3, g4, g5, g6, g7, g8, g9
        )
    }
}
