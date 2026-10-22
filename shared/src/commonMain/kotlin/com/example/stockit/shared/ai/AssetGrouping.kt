package com.example.stockit.shared.ai

/**
 * Regroupement des objets Jira Assets en produits d'inventaire.
 *
 * Jira contient un objet par unité (« EPOS IMPACT 100 #001 », « … #002 », « … - SN 0255004491 »).
 * L'inventaire de l'app affiche un produit par modèle avec une quantité = nombre d'unités « In stock ».
 *
 * La clé de regroupement tolère les variantes d'orthographe trouvées dans le schéma :
 *  - suffixes d'unité (« #001 », « - SN xxx ») retirés ;
 *  - casse, accents, espaces, tirets ignorés (« PRO14PLUS » = « PRO 14 PLUS ») ;
 *  - confusions OCR fréquentes I/1 dans « WINIIPRO » → « WIN11PRO » ;
 *  - marque répétée (« Dell DELL PRO… » → « DELL PRO… »).
 */
object AssetGrouping {

    /** Une unité lue dans Jira Assets. */
    data class AssetUnit(
        val objectKey: String,
        val typeId: String,
        val label: String,
        val status: String,
        val brand: String?,
        val model: String?,
        val serial: String?,
        val poNumber: String?,
        val vendor: String?,
        val assetType: String?,
    )

    /** Un produit d'inventaire construit à partir de plusieurs unités. */
    data class AssetGroup(
        val key: String,
        val name: String,
        val brand: String?,
        val typeId: String,
        val quantity: Int,
        val serials: List<String>,
        val poNumber: String?,
        val vendor: String?,
        val objectKeys: List<String>,
        /** Numéro de série de chaque unité (même ordre que objectKeys ; null si inconnu). */
        val unitSerials: List<String?> = emptyList(),
    )

    /** Statuts comptés comme « en stock ». */
    fun isInStock(status: String?): Boolean = status?.trim()?.equals("In stock", ignoreCase = true) == true

    /**
     * Nom d'affichage du modèle (sans le suffixe d'unité ni la marque en double).
     * Si le nom Jira est en fait le numéro de série (cas des ordinateurs : « 9JD9HF4 »)
     * ou s'il est tronqué, le champ « Asset Model » est utilisé.
     */
    fun displayName(u: AssetUnit): String {
        val stripped = stripUnitSuffix(u.label)
        val model = u.model?.trim().orEmpty()
        val labelIsSerial = stripped.isNotBlank() && (
            (u.serial != null && canonical(stripped) == canonical(u.serial)) || looksLikeSerial(stripped))
        val base = when {
            model.isNotEmpty() && (stripped.isBlank() || labelIsSerial) -> model
            model.isNotEmpty() && canonical(model).startsWith(canonical(stripped)) && model.length > stripped.length -> model
            else -> stripped.ifBlank { model }
        }
        val brand = u.brand?.trim().orEmpty()
        val withBrand = if (brand.isNotEmpty() && !normalize(base).startsWith(normalize(brand))) "$brand $base" else base
        return dedupeLeadingBrand(withBrand).trim()
    }

    /** Clé de regroupement (tolérante aux variantes d'orthographe). */
    fun groupKey(u: AssetUnit): String = u.typeId + "|" + canonical(displayName(u))

    /**
     * Clé d'un produit créé dans l'app, identique à celle que [group] calculera quand les unités
     * reviendront de Jira (le nom envoyé = brand + nom, comme displayName()).
     */
    fun keyForLocal(typeId: String, name: String, brand: String?): String =
        groupKey(AssetUnit("", typeId, name, "In stock", brand, null, null, null, null, null))

    fun group(units: List<AssetUnit>): List<AssetGroup> =
        units.filter { isInStock(it.status) }
            .groupBy { groupKey(it) }
            .map { (key, list) ->
                // Nom le plus fréquent du groupe (à égalité : le plus long, souvent le plus complet).
                val name = list.map { displayName(it) }.groupingBy { it }.eachCount()
                    .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key.length })
                    .first().key
                AssetGroup(
                    key = key,
                    name = name,
                    brand = list.mapNotNull { it.brand?.takeIf { b -> b.isNotBlank() } }.mostFrequent(),
                    typeId = list.first().typeId,
                    quantity = list.size,
                    serials = list.mapNotNull { it.serial?.takeIf { s -> s.isNotBlank() } }.distinct(),
                    poNumber = list.mapNotNull { it.poNumber?.takeIf { p -> p.isNotBlank() } }.mostFrequent(),
                    vendor = list.mapNotNull { it.vendor?.takeIf { v -> v.isNotBlank() } }.mostFrequent(),
                    objectKeys = list.map { it.objectKey },
                    unitSerials = list.map { it.serial?.takeIf { s -> s.isNotBlank() } },
                )
            }
            .sortedBy { it.name.lowercase() }

    // ------------------------------------------------------------------ normalisation

    /** « 9JD9HF4 », « 12HVTC121836 » : un seul mot, lettres + chiffres mêlés, sans espace. */
    private fun looksLikeSerial(s: String): Boolean =
        !s.contains(' ') && s.length in 6..20 && s.any { it.isDigit() } && s.any { it.isLetter() } &&
            s.count { it.isDigit() } >= 2

    private val UNIT_SUFFIX = Regex("""\s*(#\s*\d{1,4}|-\s*SN\s+\S+|\(\s*SN\s*\S+\s*\))\s*$""", RegexOption.IGNORE_CASE)

    fun stripUnitSuffix(label: String): String {
        var s = label.trim()
        repeat(2) { s = s.replace(UNIT_SUFFIX, "").trim() }
        return s
    }

    private fun dedupeLeadingBrand(s: String): String {
        val words = s.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (words.size >= 2 && normalize(words[0]) == normalize(words[1])) return words.drop(1).joinToString(" ")
        return s
    }

    fun normalize(s: String): String = ProductMatcher.normalize(s)

    /** Forme canonique : sans espaces/ponctuation, I/l → 1 entre lettres et chiffres (« WINIIPRO » → « win11pro »). */
    fun canonical(name: String): String {
        var s = normalize(name).replace(Regex("""[^a-z0-9]"""), "")
        s = s.replace(Regex("""win(?:ii|i1|1i|ll|l1|1l)pro"""), "win11pro")
        s = s.replace(Regex("""(\d)[il](\d)"""), "$11$2")
        return s
    }

    private fun List<String>.mostFrequent(): String? =
        if (isEmpty()) null else groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
}
