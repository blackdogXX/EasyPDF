package com.blackdogxx.easypdf

import androidx.annotation.OptIn
import androidx.pdf.ExperimentalPdfApi
import androidx.pdf.PdfDocument
import androidx.pdf.view.PdfView
import androidx.pdf.viewer.fragment.PdfViewerFragment

/** Стандартный просмотрщик из androidx.pdf; события пробрасываются в активность. */
class EasyPdfFragment : PdfViewerFragment() {

    interface Host {
        fun onPdfViewReady(pdfView: PdfView)
        fun onDocumentLoaded(pageCount: Int)
        fun onDocumentError(error: Throwable)
        fun onImmersiveMode(enter: Boolean)
    }

    private val host: Host? get() = activity as? Host

    @OptIn(ExperimentalPdfApi::class)
    override fun onPdfViewCreated(pdfView: PdfView) {
        super.onPdfViewCreated(pdfView)
        host?.onPdfViewReady(pdfView)
    }

    override fun onLoadDocumentSuccess(document: PdfDocument) {
        super.onLoadDocumentSuccess(document)
        host?.onDocumentLoaded(document.pageCount)
    }

    override fun onLoadDocumentError(error: Throwable) {
        super.onLoadDocumentError(error)
        host?.onDocumentError(error)
    }

    override fun onRequestImmersiveMode(enterImmersive: Boolean) {
        super.onRequestImmersiveMode(enterImmersive)
        host?.onImmersiveMode(enterImmersive)
    }
}
