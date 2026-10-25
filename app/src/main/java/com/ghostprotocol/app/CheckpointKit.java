package com.ghostprotocol.app;

import android.app.Activity;
import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import android.os.*;
import android.print.*;
import com.ghostprotocol.core.*;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import java.io.*;

/** Generates the reusable physical room kit entirely on the device. */
final class CheckpointKit {
  static Bitmap qr(Game.Station station, int size) {
    try {
      BitMatrix m =
          new com.google.zxing.qrcode.QRCodeWriter()
              .encode(CheckpointCode.encode(station), BarcodeFormat.QR_CODE, size, size);
      int[] pixels = new int[size * size];
      for (int y = 0; y < size; y++)
        for (int x = 0; x < size; x++)
          pixels[y * size + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
      return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
    } catch (WriterException e) {
      throw new IllegalStateException(e);
    }
  }

  static void writePdf(OutputStream out) throws IOException {
    PdfDocument pdf = new PdfDocument();
    try {
      int page = 1;
      for (Game.Station station : Game.Station.values())
        if (station != Game.Station.NONE) {
          PdfDocument.Page p =
              pdf.startPage(new PdfDocument.PageInfo.Builder(595, 842, page++).create());
          Canvas c = p.getCanvas();
          c.drawColor(Color.WHITE);
          Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
          paint.setColor(Color.BLACK);
          paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
          paint.setTextSize(14);
          paint.setTextAlign(Paint.Align.CENTER);
          c.drawText("GHOST PROTOCOL / THE HAUNTED HEIST", 297, 88, paint);
          paint.setTextSize(48);
          c.drawText(station.name(), 297, 164, paint);
          Bitmap qr = qr(station, 800);
          c.drawBitmap(qr, null, new Rect(102, 215, 493, 606), paint);
          qr.recycle();
          paint.setTextSize(16);
          String action =
              station == Game.Station.VAULT
                  ? "RECOVER TOGETHER"
                  : station == Game.Station.WORKSHOP ? "SEAL • REPAIR • PURGE" : "DELIVER • ESCAPE";
          c.drawText(action, 297, 670, paint);
          paint.setTextSize(12);
          c.drawText(
              "Place at a distinct point in the room. Scan inside the game.", 297, 711, paint);
          c.drawText("Reusable offline checkpoint • Version 2", 297, 738, paint);
          pdf.finishPage(p);
        }
      pdf.writeTo(out);
    } finally {
      pdf.close();
    }
  }

  static void print(Activity activity) {
    PrintManager manager = activity.getSystemService(PrintManager.class);
    if (manager == null) {
      android.widget.Toast.makeText(
              activity,
              "Printing is unavailable on this device. Use the QR card screens instead.",
              android.widget.Toast.LENGTH_LONG)
          .show();
      return;
    }
    manager.print(
        "Ghost Protocol Checkpoints",
        new PrintDocumentAdapter() {
          @Override
          public void onLayout(
              PrintAttributes old,
              PrintAttributes current,
              CancellationSignal cancel,
              LayoutResultCallback callback,
              Bundle extras) {
            if (cancel.isCanceled()) {
              callback.onLayoutCancelled();
              return;
            }
            callback.onLayoutFinished(
                new PrintDocumentInfo.Builder("GhostProtocol-Checkpoints.pdf")
                    .setPageCount(3)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .build(),
                true);
          }

          @Override
          public void onWrite(
              PageRange[] pages,
              ParcelFileDescriptor destination,
              CancellationSignal cancel,
              WriteResultCallback callback) {
            if (cancel.isCanceled()) {
              callback.onWriteCancelled();
              return;
            }
            try (FileOutputStream out = new FileOutputStream(destination.getFileDescriptor())) {
              writePdf(out);
              callback.onWriteFinished(new PageRange[] {PageRange.ALL_PAGES});
            } catch (IOException e) {
              callback.onWriteFailed("Could not create the checkpoint PDF.");
            }
          }
        },
        new PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
            .build());
  }
}
