package dev.omalauncher.pp.desktop

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import dev.omalauncher.pp.Runtime
import dev.omalauncher.pp.wayland.WindowRenderer
import dev.omalauncher.pp.wayland.XdgToplevel

/**
 * One window of the desktop: a [WindowRenderer] draws it into a TextureView, as the terminal tiles do on home, rather
 * than LinuxWindowActivity's SurfaceView: the desktop's windows overlap (floating ones) and move, and a TextureView
 * keeps the order and place Compose gives it, where SurfaceViews would sit on layers of their own.
 *
 * Only the windows of the workspace on screen have a view. The others have no renderer, so they get no frame
 * callbacks and stop drawing, as a window does in a task in the background; their last frame shows at once when
 * their workspace comes back.
 */
internal class DesktopWindowView(
    context: Context,
    private val runtime: Runtime,
    private val window: XdgToplevel,
) : TextureView(context),
    TextureView.SurfaceTextureListener {
    private val renderer = WindowRenderer(runtime.server, window)
    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    var background by renderer::background

    /** View pixels per logical pixel, 0 for the output's; see [WindowRenderer.scale]. */
    var scale = 0
        set(value) {
            if (value == field) return
            field = value
            renderer.scale = value
            layoutWindow()
        }

    /** Tells the client it's fullscreen, so it drops its own decorations. */
    var fullscreen = false
        set(value) {
            if (value == field) return
            field = value
            layoutWindow()
        }

    /** The desktop's activity is out of sight (a tablet's, in the background): the window stops drawing, as above. */
    private var stopped = false
    private val lifecycle =
        LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    stopped = true
                    renderer.detach()
                }
                Lifecycle.Event.ON_START -> {
                    stopped = false
                    surface?.let(::attachSurface)
                }
                else -> Unit
            }
        }

    init {
        surfaceTextureListener = this
    }

    private fun layoutWindow() {
        if (surfaceWidth == 0 || surfaceHeight == 0) return
        runtime.wm.applyAppLayout(window, surfaceWidth, surfaceHeight, scale, fullscreen)
    }

    private fun attachSurface(s: Surface) {
        if (!stopped) renderer.attach(s, surfaceWidth, surfaceHeight)
        layoutWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        findViewTreeLifecycleOwner()?.lifecycle?.let {
            stopped = !it.currentState.isAtLeast(Lifecycle.State.STARTED)
            it.addObserver(lifecycle)
        }
        window.renderer = renderer
    }

    override fun onDetachedFromWindow() {
        findViewTreeLifecycleOwner()?.lifecycle?.removeObserver(lifecycle)
        if (window.renderer === renderer) window.renderer = null
        renderer.release()
        super.onDetachedFromWindow()
    }

    override fun onSurfaceTextureAvailable(
        st: SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        surfaceWidth = width
        surfaceHeight = height
        attachSurface(Surface(st).also { surface = it })
    }

    override fun onSurfaceTextureSizeChanged(
        st: SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        surfaceWidth = width
        surfaceHeight = height
        surface?.let(::attachSurface)
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        renderer.detach()
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
}
