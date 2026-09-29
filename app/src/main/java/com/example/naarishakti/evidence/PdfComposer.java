package com.example.naarishakti.evidence;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.os.Build;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

import com.example.naarishakti.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Minimal flowing-text PDF writer (A4, 48pt margins) on top of {@link PdfDocument}: titles,
 * headings, wrapped paragraphs that break across pages, images and rules, with a footer on every
 * page. Printed output is always dark ink on white paper, independent of the app theme.
 */
final class PdfComposer {

    private static final int PAGE_W = 595;
    private static final int PAGE_H = 842;
    private static final int MARGIN = 48;
    private static final int FOOTER_H = 28;
    private static final int CONTENT_W = PAGE_W - 2 * MARGIN;

    // Paper colours: fixed ink, not theme tokens (a PDF is printed on white).
    private static final int INK = Color.BLACK;
    private static final int INK_MUTED = Color.DKGRAY;
    private static final int INK_FAINT = Color.GRAY;
    private static final int RULE = Color.LTGRAY;

    private final Context ctx;
    private final String footerLeft;
    private final PdfDocument doc = new PdfDocument();
    private PdfDocument.Page page;
    private Canvas canvas;
    private float y;
    private int pageNo;

    private final TextPaint titlePaint = paint(22f, INK, Typeface.DEFAULT_BOLD);
    private final TextPaint headingPaint = paint(13f, INK, Typeface.DEFAULT_BOLD);
    private final TextPaint bodyPaint = paint(11f, INK, Typeface.DEFAULT);
    private final TextPaint mutedPaint = paint(10f, INK_MUTED, Typeface.DEFAULT);
    private final TextPaint monoPaint = paint(8.5f, INK_MUTED, Typeface.MONOSPACE);
    private final TextPaint footerPaint = paint(8f, INK_FAINT, Typeface.DEFAULT);
    private final Paint rulePaint = new Paint();

    PdfComposer(Context ctx, String footerLeft) {
        this.ctx = ctx;
        this.footerLeft = footerLeft;
        rulePaint.setColor(RULE);
        rulePaint.setStrokeWidth(0.8f);
        newPage();
    }

    void title(String text) {
        text(text, titlePaint, 6f);
    }

    void heading(String text) {
        space(6f);
        text(text, headingPaint, 4f);
    }

    void body(String text) {
        text(text, bodyPaint, 6f);
    }

    void muted(String text) {
        text(text, mutedPaint, 4f);
    }

    void mono(String text) {
        text(text, monoPaint, 4f);
    }

    void space(float pts) {
        y += pts;
        if (y > bottom()) newPage();
    }

    void rule() {
        space(6f);
        ensure(2f);
        canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, rulePaint);
        y += 10f;
    }

    /** Draws a bitmap scaled to fit the content width and {@code maxHeight}. */
    void image(Bitmap bmp, float maxHeight) {
        if (bmp == null || bmp.getWidth() <= 0 || bmp.getHeight() <= 0) return;
        float scale = Math.min(CONTENT_W / (float) bmp.getWidth(), maxHeight / bmp.getHeight());
        scale = Math.min(scale, 1f * CONTENT_W / Math.max(1, bmp.getWidth()));
        float w = bmp.getWidth() * scale;
        float h = bmp.getHeight() * scale;
        ensure(h);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(bmp, null, new RectF(MARGIN, y, MARGIN + w, y + h), p);
        y += h + 8f;
    }

    /** Wrapped text that continues on the next page when it doesn't fit. */
    void text(String text, TextPaint paint, float after) {
        if (TextUtils.isEmpty(text)) return;
        StaticLayout layout = layout(text, paint);
        for (int i = 0; i < layout.getLineCount(); i++) {
            int top = layout.getLineTop(i);
            int bottom = layout.getLineBottom(i);
            float h = bottom - top;
            ensure(h);
            canvas.save();
            canvas.translate(MARGIN, y - top);
            canvas.clipRect(0, top, CONTENT_W, bottom);
            layout.draw(canvas);
            canvas.restore();
            y += h;
        }
        y += after;
    }

    /** Writes the document; the composer can't be used afterwards. */
    File finish(File out) throws IOException {
        finishPage();
        try (OutputStream os = new FileOutputStream(out)) {
            doc.writeTo(os);
        } finally {
            doc.close();
        }
        return out;
    }

    /** Frees the document without writing (on errors). */
    void abandon() {
        try {
            doc.close();
        } catch (Throwable ignored) {
            // Already closed.
        }
    }

    // ---- internals ----

    private float bottom() {
        return PAGE_H - MARGIN - FOOTER_H;
    }

    private void ensure(float h) {
        if (y + h > bottom() && y > MARGIN) newPage();
    }

    private void newPage() {
        finishPage();
        pageNo++;
        page = doc.startPage(new PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create());
        canvas = page.getCanvas();
        y = MARGIN;
    }

    private void finishPage() {
        if (page == null) return;
        float fy = PAGE_H - MARGIN + 8f;
        canvas.drawLine(MARGIN, fy - 14f, PAGE_W - MARGIN, fy - 14f, rulePaint);
        canvas.drawText(footerLeft, MARGIN, fy, footerPaint);
        String right = ctx.getString(R.string.ev_pdf_page, pageNo);
        canvas.drawText(right, PAGE_W - MARGIN - footerPaint.measureText(right), fy, footerPaint);
        doc.finishPage(page);
        page = null;
        canvas = null;
    }

    @SuppressWarnings("deprecation")
    private static StaticLayout layout(CharSequence text, TextPaint paint) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return StaticLayout.Builder.obtain(text, 0, text.length(), paint, CONTENT_W)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1.15f)
                    .setIncludePad(false)
                    .build();
        }
        return new StaticLayout(text, paint, CONTENT_W, Layout.Alignment.ALIGN_NORMAL, 1.15f, 0f, false);
    }

    private static TextPaint paint(float size, int color, Typeface tf) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(size);
        p.setColor(color);
        p.setTypeface(tf);
        return p;
    }
}
