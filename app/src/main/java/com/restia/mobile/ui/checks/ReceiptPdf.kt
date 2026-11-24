package com.restia.mobile.ui.checks

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import android.widget.Toast
import com.restia.mobile.R
import com.restia.mobile.core.network.MoneyDto
import com.restia.mobile.core.network.PaymentDto
import com.restia.mobile.ui.orders.moneyText
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal fun previewReceiptPdf(context: Context, tableNumber: Int, payment: PaymentDto) {
    val title = context.getString(R.string.receipt_pdf_job, payment.id.takeLast(8).uppercase())
    val attributes = PrintAttributes.Builder()
        .setMediaSize(PrintAttributes.MediaSize.ISO_A5)
        .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
        .build()
    val opened = runCatching {
        context.getSystemService(PrintManager::class.java)
            ?.print(title, ReceiptPrintAdapter(context, tableNumber, payment, title), attributes)
    }.getOrNull()
    if (opened == null) Toast.makeText(context, R.string.receipt_pdf_error, Toast.LENGTH_LONG).show()
}

private class ReceiptPrintAdapter(
    private val context: Context,
    private val tableNumber: Int,
    private val payment: PaymentDto,
    private val title: String,
) : PrintDocumentAdapter() {
    private var attributes: PrintAttributes? = null

    override fun onLayout(
        oldAttributes: PrintAttributes,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }
        attributes = newAttributes
        val document = PrintDocumentInfo.Builder("$title.pdf")
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(1)
            .build()
        callback.onLayoutFinished(document, oldAttributes != newAttributes)
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback,
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onWriteCancelled()
            return
        }
        val pageAttributes = attributes
        if (pageAttributes == null) {
            callback.onWriteFailed(context.getString(R.string.receipt_pdf_error))
            return
        }
        if (pages.none { it.start == 0 }) {
            callback.onWriteFinished(emptyArray())
            return
        }
        val pdf = PrintedPdfDocument(context, pageAttributes)
        try {
            val page = pdf.startPage(0)
            drawReceipt(page.canvas, pdf.pageContentRect.width().toFloat())
            pdf.finishPage(page)
            ParcelFileDescriptor.AutoCloseOutputStream(destination).use { pdf.writeTo(it) }
            if (cancellationSignal.isCanceled) callback.onWriteCancelled()
            else callback.onWriteFinished(arrayOf(PageRange(0, 0)))
        } catch (_: IOException) {
            callback.onWriteFailed(context.getString(R.string.receipt_pdf_error))
        } finally {
            pdf.close()
        }
    }

    private fun drawReceipt(canvas: android.graphics.Canvas, width: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        val left = 16f
        val right = width - 16f
        var y = 30f

        fun line(text: String, size: Float = 11f, bold: Boolean = false) {
            paint.textSize = size
            paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            canvas.drawText(text, left, y, paint)
            y += size + 10f
        }

        fun amount(label: Int, value: String, bold: Boolean = false) {
            paint.textSize = if (bold) 14f else 11f
            paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            canvas.drawText(context.getString(label), left, y, paint)
            canvas.drawText(value, right - paint.measureText(value), y, paint)
            y += paint.textSize + 10f
        }

        fun money(value: MoneyDto) = moneyText(amountOf(value), value.currency)
        val date = runCatching {
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(Locale.forLanguageTag("es-MX"))
                .withZone(ZoneId.systemDefault()).format(Instant.parse(payment.createdAt))
        }.getOrDefault(payment.createdAt)

        line(context.getString(R.string.receipt_pdf_title), size = 16f, bold = true)
        line(context.getString(R.string.receipt_pdf_folio, payment.id))
        line(context.getString(R.string.receipt_pdf_table, tableNumber) + " · " + date)
        val card = payment.card?.last4?.let { " · •••• $it" }.orEmpty()
        line(context.getString(methodLabel(payment.method)) + card)
        canvas.drawLine(left, y - 8f, right, y - 8f, paint)
        y += 10f
        amount(R.string.pay_amount, money(payment.amount))
        amount(R.string.pay_tip, money(payment.tip))
        amount(R.string.receipt_pdf_total, moneyText(amountOf(payment.amount) + amountOf(payment.tip), payment.amount.currency), bold = true)
        payment.amountTendered?.let { amount(R.string.pay_tendered, money(it)) }
        payment.change?.let { canvas.drawText(context.getString(R.string.pay_change, money(it)), left, y, paint) }
    }
}
