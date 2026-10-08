package com.backpack.app.core

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 1.3.0: everything Backpack itself says, in the voice language chosen in
 * Settings > Navigation: English, Spanish (Latin America), French, Italian,
 * German. Turn instructions in "Full" mode come from openrouteservice in
 * the same language; these are the words around them and the "Short" mode.
 */
class VoiceText private constructor(val lang: String) {

    private fun pick(en: String, es: String, fr: String, it: String, de: String) = when (lang) {
        "es" -> es; "fr" -> fr; "it" -> it; "de" -> de; else -> en
    }

    /** Decimal comma outside English ("1,5 kilómetros"). */
    private fun dec(v: Double): String = if (lang == "en") v.toString() else v.toString().replace('.', ',')

    /** "300 meters", "1.5 kilometers"... [dative]: German after "in" ("300 Metern"). */
    fun distance(m: Double, metric: Boolean, dative: Boolean = false): String {
        if (lang == "en") return Speech.distance(m, metric)
        if (metric) {
            if (m < 1000) {
                val step = if (m >= 100) 50 else 10
                val v = max(step, ((m / step).roundToInt() * step))
                if (v >= 1000) return pick("1 kilometer", "1 kilómetro", "1 kilomètre", "1 chilometro", if (dative) "einem Kilometer" else "1 Kilometer")
                return "$v " + pick("meters", "metros", "mètres", "metri", if (dative) "Metern" else "Meter")
            }
            val km = (m / 100.0).roundToInt() / 10.0
            val one = km == 1.0
            val n = if (km == Math.floor(km)) km.toInt().toString() else dec(km)
            if (one) return pick("1 kilometer", "1 kilómetro", "1 kilomètre", "1 chilometro", if (dative) "einem Kilometer" else "1 Kilometer")
            return "$n " + pick("kilometers", "kilómetros", "kilomètres", "chilometri", if (dative) "Kilometern" else "Kilometer")
        }
        val feet = m * 3.28084
        if (feet < 1000) {
            val v = max(50, ((feet / 50).roundToInt() * 50))
            return "$v " + pick("feet", "pies", "pieds", "piedi", "Fuß")
        }
        val mi = (m / 1609.344 * 10).roundToInt() / 10.0
        val n = if (mi == Math.floor(mi)) mi.toInt().toString() else dec(mi)
        return if (mi == 1.0) pick("1 mile", "1 milla", "1 mile", "1 miglio", if (dative) "einer Meile" else "1 Meile")
        else "$n " + pick("miles", "millas", "miles", "miglia", "Meilen")
    }

    fun continueFor(d: String) = pick("Continue for $d", "Continúe por $d", "Continuez sur $d", "Prosegui per $d", "$d geradeaus weiter")

    /** "In 300 meters, turn right" ([what] is the maneuver sentence). */
    fun inDistance(m: Double, metric: Boolean, what: String): String = when (lang) {
        "de" -> "In ${distance(m, metric, dative = true)}: $what"
        else -> pick("In", "En", "Dans", "Tra", "In") + " " + distance(m, metric) + ", " + lowerFirst(what)
    }

    fun then(first: String, second: String): String = first + pick(", then ", ", luego ", ", puis ", ", poi ", ", dann ") +
        (if (lang == "de") second else lowerFirst(second))

    fun stopReached() = pick("You have reached your stop", "Llegó a su parada", "Vous êtes arrivé à votre étape", "Hai raggiunto la tappa", "Sie haben Ihren Zwischenstopp erreicht")
    fun arrived() = pick("You have arrived", "Llegó a su destino", "Vous êtes arrivé à destination", "Sei arrivato a destinazione", "Sie haben Ihr Ziel erreicht")
    fun recalculated() = pick("Route recalculated", "Ruta recalculada", "Itinéraire recalculé", "Percorso ricalcolato", "Route neu berechnet")

    fun category(c: TouristCategory): String = when (c) {
        TouristCategory.SIGHT -> pick("sight", "atracción", "site touristique", "attrazione", "Sehenswürdigkeit")
        TouristCategory.MUSEUM -> pick("museum", "museo", "musée", "museo", "Museum")
        TouristCategory.HISTORIC -> pick("historic site", "sitio histórico", "site historique", "luogo storico", "historischer Ort")
        TouristCategory.VIEWPOINT -> pick("viewpoint", "mirador", "point de vue", "punto panoramico", "Aussichtspunkt")
        TouristCategory.NATURE -> pick("nature", "sitio natural", "site naturel", "luogo naturale", "Naturort")
        TouristCategory.WATER -> pick("waterfall or spring", "cascada o manantial", "cascade ou source", "cascata o sorgente", "Wasserfall oder Quelle")
        TouristCategory.PARK -> pick("nature park", "parque natural", "parc naturel", "parco naturale", "Naturpark")
        TouristCategory.HERITAGE -> pick("World Heritage site", "Patrimonio de la Humanidad", "patrimoine mondial", "Patrimonio dell'Umanità", "Welterbe")
    }

    /** Sight heads-up: "In about 3 minutes, Castle X, historic site, about 6 minutes off your route". */
    fun headsUp(inMin: Int, name: String, c: TouristCategory, detourMin: Int): String {
        val cat = category(c)
        return when (lang) {
            "es" -> "En unos ${minutes(inMin)}, $name, $cat, a unos ${minutes(detourMin)} de su ruta"
            "fr" -> "Dans environ ${minutes(inMin)}, $name, $cat, à environ ${minutes(detourMin)} de votre itinéraire"
            "it" -> "Tra circa ${minutes(inMin)}, $name, $cat, a circa ${minutes(detourMin)} dal percorso"
            "de" -> "In etwa ${minutesDative(inMin)}: $name, $cat, etwa ${minutes(detourMin)} von Ihrer Route entfernt"
            else -> "In about ${minutes(inMin)}, $name, $cat, about ${minutes(detourMin)} off your route"
        }
    }

    private fun minutes(n: Int): String = "$n " + if (n == 1) pick("minute", "minuto", "minute", "minuto", "Minute")
        else pick("minutes", "minutos", "minutes", "minuti", "Minuten")
    private fun minutesDative(n: Int): String = "$n " + if (n == 1) "Minute" else "Minuten"

    /** Ordinal for roundabout exits ("3rd", "tercera", "troisième"...). */
    fun ordinal(n: Int): String {
        val words = when (lang) {
            "es" -> listOf("primera", "segunda", "tercera", "cuarta", "quinta", "sexta", "séptima", "octava")
            "fr" -> listOf("première", "deuxième", "troisième", "quatrième", "cinquième", "sixième", "septième", "huitième")
            "it" -> listOf("prima", "seconda", "terza", "quarta", "quinta", "sesta", "settima", "ottava")
            "de" -> listOf("erste", "zweite", "dritte", "vierte", "fünfte", "sechste", "siebte", "achte")
            else -> return com.backpack.app.core.ordinal(n)
        }
        return words.getOrNull(n - 1) ?: "$n."
    }

    /** "Short" voice: only the maneuver, no street names. */
    fun short(step: NavStep): String = when (step.maneuver) {
        Maneuver.LEFT -> pick("Turn left", "Gire a la izquierda", "Tournez à gauche", "Svolta a sinistra", "Links abbiegen")
        Maneuver.RIGHT -> pick("Turn right", "Gire a la derecha", "Tournez à droite", "Svolta a destra", "Rechts abbiegen")
        Maneuver.SHARP_LEFT -> pick("Turn sharp left", "Gire cerrado a la izquierda", "Tournez franchement à gauche", "Svolta decisamente a sinistra", "Scharf links abbiegen")
        Maneuver.SHARP_RIGHT -> pick("Turn sharp right", "Gire cerrado a la derecha", "Tournez franchement à droite", "Svolta decisamente a destra", "Scharf rechts abbiegen")
        Maneuver.SLIGHT_LEFT -> pick("Bear left", "Gire levemente a la izquierda", "Tournez légèrement à gauche", "Svolta leggermente a sinistra", "Leicht links abbiegen")
        Maneuver.SLIGHT_RIGHT -> pick("Bear right", "Gire levemente a la derecha", "Tournez légèrement à droite", "Svolta leggermente a destra", "Leicht rechts abbiegen")
        Maneuver.STRAIGHT -> pick("Go straight", "Siga derecho", "Continuez tout droit", "Prosegui dritto", "Geradeaus weiter")
        Maneuver.ROUNDABOUT_ENTER -> if (step.exitNumber > 0) {
            val o = ordinal(step.exitNumber)
            pick("Take the $o exit", "Tome la $o salida", "Prenez la $o sortie", "Prendi la $o uscita", "Nehmen Sie die $o Ausfahrt")
        } else pick("Enter the roundabout", "Entre en la rotonda", "Entrez dans le rond-point", "Entra nella rotatoria", "In den Kreisverkehr einfahren")
        Maneuver.ROUNDABOUT_EXIT -> pick("Exit the roundabout", "Salga de la rotonda", "Sortez du rond-point", "Esci dalla rotatoria", "Kreisverkehr verlassen")
        Maneuver.UTURN -> pick("Make a U-turn", "Dé la vuelta en U", "Faites demi-tour", "Fai inversione a U", "Wenden")
        Maneuver.GOAL -> pick("Destination", "Destino", "Destination", "Destinazione", "Ziel")
        Maneuver.DEPART -> pick("Start", "Inicio", "Départ", "Partenza", "Start")
        Maneuver.KEEP_LEFT -> pick("Keep left", "Manténgase a la izquierda", "Restez à gauche", "Mantieni la sinistra", "Links halten")
        Maneuver.KEEP_RIGHT -> pick("Keep right", "Manténgase a la derecha", "Restez à droite", "Mantieni la destra", "Rechts halten")
        Maneuver.UNKNOWN -> pick("Continue", "Continúe", "Continuez", "Prosegui", "Weiter")
    }

    companion object {
        /** Settings order: code to label. */
        val LANGUAGES = listOf("en" to "English", "es" to "Español (Latinoamérica)", "fr" to "Français", "it" to "Italiano", "de" to "Deutsch")

        private val cache = HashMap<String, VoiceText>()
        fun forLanguage(code: String?): VoiceText {
            val c = code?.takeIf { l -> LANGUAGES.any { it.first == l } } ?: "en"
            return synchronized(cache) { cache.getOrPut(c) { VoiceText(c) } }
        }

        /** The phone's language when it is one of ours, else English. */
        fun defaultFor(phoneLanguage: String): String = if (LANGUAGES.any { it.first == phoneLanguage }) phoneLanguage else "en"

        private fun lowerFirst(s: String): String = if (s.isEmpty()) s else s.substring(0, 1).lowercase() + s.substring(1)
    }
}
