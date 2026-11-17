package cl.geoplano.render

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * PdfRenderer no es seguro entre hilos: todo el renderizado pasa por un único hilo.
 * Mantiene abierto el último PDF usado para que los re-renderizados de detalle sean rápidos.
 */
object PdfRenderPool {

    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pdf-render").apply { priority = Thread.NORM_PRIORITY - 1 }
    }.asCoroutineDispatcher()

    private var openPath: String? = null
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null

    private fun open(file: File): PdfRenderer {
        if (openPath == file.absolutePath && renderer != null) return renderer!!
        close()
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        pfd = fd
        renderer = PdfRenderer(fd)
        openPath = file.absolutePath
        return renderer!!
    }

    private fun close() {
        runCatching { renderer?.close() }
        runCatching { pfd?.close() }
        renderer = null; pfd = null; openPath = null
    }

    /** Tamaño de la primera página en puntos. */
    suspend fun pageSize(file: File): Pair<Double, Double>? = withContext(dispatcher) {
        runCatching {
            open(file).openPage(0).use { Pair(it.width.toDouble(), it.height.toDouble()) }
        }.getOrNull()
    }

    /** Página completa con el lado mayor = maxPx. */
    suspend fun renderFull(file: File, maxPx: Int): Bitmap = withContext(dispatcher) {
        open(file).openPage(0).use { page ->
            val s = maxPx.toFloat() / maxOf(page.width, page.height)
            val w = maxOf(1, (page.width * s).toInt())
            val h = maxOf(1, (page.height * s).toInt())
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val m = Matrix().apply { setScale(w.toFloat() / page.width, h.toFloat() / page.height) }
            page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        }
    }

    /**
     * Renderiza sólo una región de la página (en pt, origen arriba-izquierda) a outW x outH píxeles.
     * Permite ver detalle nítido a cualquier zoom sin cargar un bitmap gigante.
     */
    suspend fun renderRegion(file: File, region: RectF, outW: Int, outH: Int): Bitmap = withContext(dispatcher) {
        open(file).openPage(0).use { page ->
            val bmp = Bitmap.createBitmap(maxOf(1, outW), maxOf(1, outH), Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val sx = outW / region.width()
            val sy = outH / region.height()
            val m = Matrix().apply {
                setTranslate(-region.left, -region.top)
                postScale(sx, sy)
            }
            page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        }
    }

    suspend fun writeThumbnail(pdf: File, out: File, maxPx: Int) {
        val bmp = renderFull(pdf, maxPx)
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
        bmp.recycle()
    }

    /** Para liberar el descriptor cuando se borra un plano abierto. */
    fun release() = runBlocking(dispatcher) { close() }
}
