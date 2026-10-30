package dev.omalauncher.pp.search

/** What a unit measures: only units of the same one convert into each other. */
enum class Dimension { Length, Mass, Temperature, Data, Volume, Time, Speed, Area, Angle, Energy, Pressure }

/**
 * A unit the calculator converts: [factor] of the [dimension]'s base unit (metre, kilogram, byte, litre, second, m/s,
 * m², radian, joule, pascal), plus [offset] for temperatures, whose base is kelvin. [symbol] is how results name it.
 */
class MeasureUnit(
    val symbol: String,
    val dimension: Dimension,
    val factor: Double,
    val offset: Double = 0.0,
)

/** The units "5 km in mi" can name, by their symbols and names in English and the launcher's other languages. */
object Units {
    private val exact = HashMap<String, MeasureUnit>()
    private val folded = HashMap<String, MeasureUnit>()

    private fun unit(
        symbol: String,
        dimension: Dimension,
        factor: Double,
        vararg names: String,
        offset: Double = 0.0,
    ) {
        val u = MeasureUnit(symbol, dimension, factor, offset)
        for (n in listOf(symbol) + names) {
            exact.putIfAbsent(n, u)
            folded.putIfAbsent(n.lowercase(), u)
        }
    }

    init {
        val d = Dimension.Length
        unit("m", d, 1.0, "meter", "meters", "metre", "metres", "metro", "metros")
        unit("km", d, 1000.0, "kilometer", "kilometers", "kilometre", "kilometres", "kilómetro", "kilómetros", "quilômetro", "quilômetros", "kilometro", "kilometros")
        unit("cm", d, 0.01, "centimeter", "centimeters", "centimetre", "centimetres", "centímetro", "centímetros", "sentimeter")
        unit("mm", d, 0.001, "millimeter", "millimeters", "millimetre", "millimetres", "milímetro", "milímetros", "milimeter")
        unit("µm", d, 1e-6, "um", "μm", "micrometer", "micrometers", "micron", "microns")
        unit("nm", d, 1e-9, "nanometer", "nanometers")
        unit("mi", d, 1609.344, "mile", "miles", "milla", "millas", "milha", "milhas")
        unit("yd", d, 0.9144, "yard", "yards", "yarda", "yardas")
        unit("ft", d, 0.3048, "foot", "feet", "'", "pie", "pies", "pé", "pés", "kaki")
        unit("in", d, 0.0254, "inch", "inches", "\"", "pulgada", "pulgadas", "polegada", "polegadas", "inci")
        unit("nmi", d, 1852.0, "nautical mile", "nautical miles")
        unit("ly", d, 9.4607304725808e15, "light year", "light years", "lightyear", "lightyears")
        unit("au", d, 1.495978707e11)

        val w = Dimension.Mass
        unit("kg", w, 1.0, "kilogram", "kilograms", "kilo", "kilos", "kilogramo", "kilogramos", "quilograma", "quilogramas", "kilogram")
        unit("g", w, 0.001, "gram", "grams", "gramo", "gramos", "grama", "gramas", "gr")
        unit("mg", w, 1e-6, "milligram", "milligrams", "miligramo", "miligramos", "miligrama", "miligramas")
        unit("t", w, 1000.0, "tonne", "tonnes", "ton", "tons", "tonelada", "toneladas")
        unit("lb", w, 0.45359237, "lbs", "pound", "pounds", "libra", "libras", "pon")
        unit("oz", w, 0.028349523125, "ounce", "ounces", "onza", "onzas", "onça", "onças", "ons")
        unit("st", w, 6.35029318, "stone", "stones")

        // Kelvin is the base: a temperature in another unit is (x + offset) * factor kelvin.
        val t = Dimension.Temperature
        unit("°C", t, 1.0, "c", "°c", "ºc", "celsius", "centigrade", "grados celsius", "graus celsius", offset = 273.15)
        unit("°F", t, 5.0 / 9.0, "f", "°f", "ºf", "fahrenheit", offset = 459.67)
        unit("K", t, 1.0, "k", "kelvin", "kelvins")

        // Bytes are the base. "B" is a byte and "b" a bit; "mb" and "gb", typed in lower case, mean bytes, as people do.
        val b = Dimension.Data
        unit("bit", b, 0.125, "bits", "b")
        unit("B", b, 1.0, "byte", "bytes", "octet", "octets")
        for ((i, p) in listOf("k", "M", "G", "T", "P").withIndex()) {
            val power = i + 1
            val si = Math.pow(1000.0, power.toDouble())
            val iec = Math.pow(1024.0, power.toDouble())
            val prefix = if (p == "k") "kilo" else listOf("mega", "giga", "tera", "peta")[i - 1]
            unit("${p}B", b, si, "${p.uppercase()}B", "${p.lowercase()}b", "${prefix}byte", "${prefix}bytes")
            unit("${p.uppercase()}iB", b, iec, "${p.lowercase()}ib", "${prefix.dropLast(2)}bibyte", "${prefix.dropLast(2)}bibytes")
            unit("${p}bit", b, si / 8, "${p}b", "${p.lowercase()}bit", "${prefix}bit", "${prefix}bits")
        }

        val v = Dimension.Volume
        unit("L", v, 1.0, "l", "liter", "liters", "litre", "litres", "litro", "litros")
        unit("mL", v, 0.001, "ml", "milliliter", "milliliters", "millilitre", "millilitres", "mililitro", "mililitros")
        unit("cL", v, 0.01, "cl", "centiliter", "centiliters")
        unit("dL", v, 0.1, "dl", "deciliter", "deciliters")
        unit("m³", v, 1000.0, "m3", "cubic meter", "cubic meters")
        unit("gal", v, 3.785411784, "gallon", "gallons", "galón", "galones", "galão", "galões")
        unit("qt", v, 0.946352946, "quart", "quarts")
        unit("pt", v, 0.473176473, "pint", "pints", "pinta", "pintas")
        unit("cup", v, 0.2365882365, "cups", "taza", "tazas", "xícara", "xícaras")
        unit("fl oz", v, 0.0295735295625, "floz", "fluid ounce", "fluid ounces")
        unit("tbsp", v, 0.01478676478125, "tablespoon", "tablespoons")
        unit("tsp", v, 0.00492892159375, "teaspoon", "teaspoons")

        val s = Dimension.Time
        unit("ms", s, 0.001, "millisecond", "milliseconds", "milisegundo", "milisegundos")
        unit("s", s, 1.0, "sec", "secs", "second", "seconds", "segundo", "segundos", "detik")
        unit("min", s, 60.0, "mins", "minute", "minutes", "minuto", "minutos", "menit")
        unit("h", s, 3600.0, "hr", "hrs", "hour", "hours", "hora", "horas", "jam")
        unit("d", s, 86400.0, "day", "days", "día", "días", "dia", "dias", "hari")
        unit("wk", s, 604800.0, "week", "weeks", "semana", "semanas", "minggu")
        unit("yr", s, 31557600.0, "year", "years", "año", "años", "ano", "anos", "tahun")

        val sp = Dimension.Speed
        unit("m/s", sp, 1.0, "mps")
        unit("km/h", sp, 1 / 3.6, "kmh", "kph", "kmph")
        unit("mph", sp, 0.44704, "mi/h")
        unit("kn", sp, 1852.0 / 3600, "knot", "knots", "kt", "nudo", "nudos", "nó", "nós")
        unit("ft/s", sp, 0.3048, "fps")

        val a = Dimension.Area
        unit("m²", a, 1.0, "m2", "sqm", "square meter", "square meters")
        unit("km²", a, 1e6, "km2", "square kilometer", "square kilometers")
        unit("cm²", a, 1e-4, "cm2")
        unit("ha", a, 1e4, "hectare", "hectares", "hectárea", "hectáreas")
        unit("acre", a, 4046.8564224, "acres", "ac")
        unit("ft²", a, 0.09290304, "ft2", "sqft", "square foot", "square feet")
        unit("mi²", a, 2589988.110336, "mi2", "square mile", "square miles")

        val an = Dimension.Angle
        unit("°", an, Math.PI / 180, "deg", "degree", "degrees", "grado", "grados", "grau", "graus", "derajat")
        unit("rad", an, 1.0, "radian", "radians", "radián", "radianes", "radiano", "radianos")
        unit("turn", an, 2 * Math.PI, "turns", "rev", "revolution", "revolutions")

        val e = Dimension.Energy
        unit("J", e, 1.0, "j", "joule", "joules")
        unit("kJ", e, 1000.0, "kj", "kilojoule", "kilojoules")
        unit("cal", e, 4.184, "calorie", "calories", "caloría", "calorías", "caloria", "calorias")
        unit("kcal", e, 4184.0, "kilocalorie", "kilocalories", "kilocaloría", "kilocalorías")
        unit("Wh", e, 3600.0, "wh")
        unit("kWh", e, 3.6e6, "kwh")

        val p = Dimension.Pressure
        unit("Pa", p, 1.0, "pa", "pascal", "pascals")
        unit("kPa", p, 1000.0, "kpa")
        unit("bar", p, 1e5, "bars")
        unit("psi", p, 6894.757293168)
        unit("atm", p, 101325.0, "atmosphere", "atmospheres")
    }

    /** The unit called [name]: its exact spelling first ("MB" bytes, "Mb" bits), then in any case. */
    fun find(name: String): MeasureUnit? {
        val n = name.trim().replace(Regex("\\s+"), " ")
        return exact[n] ?: folded[n.lowercase()]
    }

    /** [amount] of [from] in [to]; both measure the same [Dimension]. */
    fun convert(
        amount: Double,
        from: MeasureUnit,
        to: MeasureUnit,
    ): Double = (amount + from.offset) * from.factor / to.factor - to.offset
}
