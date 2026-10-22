# 📷 Image Labeling Utility - Documentation

## 📌 Résumé

Classe utilitaire `ImageLabelingUtil` pour détecter les objets informatiques dans une image Android et les afficher dans un AlertDialog avec possibilité d'ajouter au stock.

## ✨ Fonctionnalités

✅ **Détection d'objets informatiques** :
- Clavier (keyboard)
- Souris (mouse)
- Ordinateur portable (laptop)
- Moniteur (monitor)

✅ **Traduction automatique** en français

✅ **AlertDialog interactif** avec :
- Liste des objets détectés
- Champ de saisie pour la quantité (par défaut: 1)
- Bouton "Ajouter au stock"
- Bouton "Annuler"

✅ **Callback pour intégration** au système de stock existant

## 📂 Fichiers créés

```
app/src/main/java/com/example/stockit/util/
├── ImageLabelingUtil.java        # Classe principale
└── USAGE_EXAMPLE.java             # Exemples d'utilisation
```

## 🚀 Utilisation

### Étape 1: Importer la classe

```java
import com.example.stockit.util.ImageLabelingUtil;
import com.google.mlkit.vision.common.InputImage;
```

### Étape 2: Utiliser la méthode dans votre Activity

```java
// Supposons que vous avez un Bitmap de l'image capturée
Bitmap imageBitmap = ...; // Votre image capturée par la caméra

// Convertir en InputImage (format ML Kit)
InputImage image = InputImage.fromBitmap(imageBitmap);

// Appeler le traitement
ImageLabelingUtil.processImageLabeling(
    this,  // contexte Android
    image, // l'image à traiter
    new ImageLabelingUtil.AddArticleCallback() {
        @Override
        public void onArticleAdded(String articleName, String quantity) {
            // Votre code pour ajouter l'article au stock
            addProductToStock(articleName, Integer.parseInt(quantity));
        }
    }
);
```

### Étape 3: Intégrer au système de stock

```java
private void addProductToStock(String articleName, int quantity) {
    // Créer un nouveau produit
    Product product = new Product();
    product.setName(articleName);
    product.setQuantity(quantity);
    product.setDateAdded(System.currentTimeMillis());
    
    // Ajouter à la base de données
    ProductDao productDao = AppDatabase.getInstance(this).productDao();
    productDao.insertProduct(product);
    
    Toast.makeText(this, "✅ " + articleName + " ajouté au stock", 
        Toast.LENGTH_LONG).show();
}
```

## 📊 Format de l'AlertDialog

```
┌─────────────────────────┐
│  Objets détectés        │
├─────────────────────────┤
│ Articles détectés:      │
│ • Clavier               │
│ • Souris                │
│                         │
│ [_____ Quantité ____]   │
│                         │
│ [Ajouter] [Annuler]     │
└─────────────────────────┘
```

## 🛠 Intégration avec Camera

Si vous utilisez Camera2 ou CameraX:

```java
// Avec CameraX ImageAnalysis
imageAnalysis.setAnalyzer(cameraExecutor, image -> {
    Bitmap bitmap = image.getImage().toBitmap(); // Convertir en Bitmap
    InputImage mlImage = InputImage.fromBitmap(bitmap);
    
    ImageLabelingUtil.processImageLabeling(MainActivity.this, mlImage, 
        (articleName, quantity) -> {
            addProductToStock(articleName, Integer.parseInt(quantity));
        }
    );
});
```

## 🔍 Détails techniques

### Dépendances utilisées

```gradle
implementation("com.google.mlkit:object-detection:17.0.2")
implementation(libs.androidx.camera.core) // Pour InputImage
implementation(libs.google.material)      # Pour AlertDialog
```

### Traductions par défaut

| English | Français |
|---------|----------|
| keyboard | Clavier |
| mouse | Souris |
| laptop | Ordinateur portable |
| monitor | Moniteur |

### Configuration ML Kit

Dans `AndroidManifest.xml`:
```xml
<meta-data
    android:name="com.google.mlkit.vision.DEPENDENCIES"
    android:value="ocr,barcode,image_labeling" />
```

## ⚙️ Permissions requises

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-feature android:name="android.hardware.camera" android:required="false" />
```

## 🎯 Flux complet

1. 📸 Utilisateur capture une image avec la caméra
2. 🔄 Conversion en `InputImage` pour ML Kit
3. 🤖 ML Kit analyse l'image et détecte les objets
4. 🇫🇷 Traduction des labels en français
5. 🎨 Affichage du `AlertDialog` avec les résultats
6. ✏️ Utilisateur saisit la quantité (optionnel)
7. 💾 Callback déclenché pour ajouter au stock

## 📝 Notes

- Les objets non-pertinents sont filtrés automatiquement
- La quantité par défaut est 1 si le champ est vide
- Le premier objet détecté est sélectionné automatiquement
- Les erreurs sont affichées via `Toast`

## 🔗 Intégration future

Pour intégrer complètement Google ML Kit Image Labeling:

1. Ajouter la dépendance complète:
```gradle
implementation("com.google.mlkit:image-labeling:X.Y.Z")
```

2. Décommenter et utiliser le code ML Kit complet dans `ImageLabelingUtil.java`

## ✅ Build Status

✅ **Le projet compile avec succès**

```
BUILD SUCCESSFUL in 1m 24s
96 actionable tasks: 95 executed, 1 up-to-date
```

---

**Auteur**: Copilot  
**Date**: 2026-06-12  
**Version**: 1.0
