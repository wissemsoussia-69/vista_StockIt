package com.example.stockit.controller;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.example.stockit.model.AppDatabase;
import com.example.stockit.model.Product;
import com.example.stockit.util.StockItReporter;
import java.util.List;

public class ReportWorker extends Worker {

    public ReportWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        String type = getInputData().getString("REPORT_TYPE");
        String finalType = (type != null) ? type : "DAILY";

        generateAndSendReport(finalType);

        return Result.success();
    }

    private void generateAndSendReport(String type) {
        AppDatabase db = AppDatabase.getInstance(getApplicationContext());
        List<Product> products = db.productDao().getAll();

        StringBuilder csv = new StringBuilder("ID,Nom,Quantite,Prix\n");
        for (Product p : products) {
            csv.append(p.getId()).append(",")
               .append(p.getName()).append(",")
               .append(p.getQuantity()).append(",")
               .append(p.getUnitPrice()).append("\n");
        }

        sendReportViaWebhook(type, csv.toString(), products.size());
    }

    /**
     * Envoie le rapport au workflow n8n / automation.vista.io qui se charge
     * de router l'e-mail vers les destinataires configurés côté workflow.
     * Remplace l'ancien path SendGrid direct (clé placeholder, non utilisable).
     */
    private void sendReportViaWebhook(String type, String csvData, int count) {
        String message = "📊 Rapport de stock automatique généré par le worker planifié.\n\n"
                + "• Type              : " + type + "\n"
                + "• Nombre d'articles : " + count + "\n\n"
                + "Contenu CSV :\n" + csvData;

        StockItReporter.sendReport(
                "StockIT-Worker",
                "wissem.soussia@vista.com",
                "Rapport " + type,
                message,
                (success, bodyOrError) -> {
                    if (success) {
                        Log.d("ReportWorker", "Rapport envoyé via webhook : " + bodyOrError);
                        NotificationHelper.showNotification(getApplicationContext(),
                                "Rapport envoyé !",
                                "Le rapport a été transmis au workflow StockIT.",
                                777);
                    } else {
                        Log.e("ReportWorker", "Webhook KO : " + bodyOrError);
                    }
                });
    }
}
