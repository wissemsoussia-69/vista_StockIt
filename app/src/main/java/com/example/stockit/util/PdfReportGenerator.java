package com.example.stockit.util;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.os.Environment;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class PdfReportGenerator {

    public static File generateMonthlyReport(Context context, String aiSummary) {
        PdfDocument document = new PdfDocument();
        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(595, 842, 1).create();
        PdfDocument.Page page = document.startPage(pageInfo);
        Canvas canvas = page.getCanvas();
        Paint paint = new Paint();

        // Titre
        paint.setColor(Color.BLUE);
        paint.setTextSize(24);
        canvas.drawText("StockIT - Rapport Mensuel d'Inventaire", 50, 50, paint);

        // Date
        paint.setColor(Color.GRAY);
        paint.setTextSize(12);
        String date = new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(new Date());
        canvas.drawText("Généré le : " + date, 50, 80, paint);

        // Contenu rédigé par l'IA
        paint.setColor(Color.BLACK);
        paint.setTextSize(14);
        int y = 130;
        
        // Split text into lines to fit page
        String[] lines = aiSummary.split("\n");
        for (String line : lines) {
            // Basic line wrapping check
            if (line.length() > 60) {
                canvas.drawText(line.substring(0, 60), 50, y, paint);
                y += 20;
                canvas.drawText(line.substring(60), 50, y, paint);
            } else {
                canvas.drawText(line, 50, y, paint);
            }
            y += 25;
            if (y > 750) break; // Simple page limit
        }

        document.finishPage(page);

        // Sauvegarde
        File file = new File(context.getExternalFilesDir(null), "Rapport_Stock_" + System.currentTimeMillis() + ".pdf");
        try {
            document.writeTo(new FileOutputStream(file));
            Toast.makeText(context, "PDF généré avec succès", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        } finally {
            document.close();
        }
        return file;
    }
}
