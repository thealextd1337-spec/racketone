package eu.advantly.ui.phone.auth

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import eu.advantly.ui.phone.platform.countryName

/**
 * Länder mit Konto-Zulassung laut `country_age_rules` (Migration `20261028000100_countries_worldwide` plus AT/DE/LI/CH);
 * ohne die Embargoländer CU, IR, KP, SY. Neue Regeln im Backend hier nachziehen.
 */
internal val SIGNUP_COUNTRIES: List<String> =
    (
        "AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ BR BS BT BV BW BY BZ CA CC CD " +
            "CF CG CH CI CK CL CM CN CO CR CV CW CX CY CZ DE DJ DK DM DO DZ EC EE EG EH ER ES ET FI FJ FK FM FO FR GA GB GD GE GF GG " +
            "GH GI GL GM GN GP GQ GR GS GT GU GW GY HK HM HN HR HT HU ID IE IL IM IN IO IQ IS IT JE JM JO JP KE KG KH KI KM KN KR KW " +
            "KY KZ LA LB LC LI LK LR LS LT LU LV LY MA MC MD ME MF MG MH MK ML MM MN MO MP MQ MR MS MT MU MV MW MX MY MZ NA NC NE NF " +
            "NG NI NL NO NP NR NU NZ OM PA PE PF PG PH PK PL PM PN PR PS PT PW PY QA RE RO RS RU RW SA SB SC SD SE SG SH SI SJ SK SL " +
            "SM SN SO SR SS ST SV SX SZ TC TD TF TG TH TJ TK TL TM TN TO TR TT TV TW TZ UA UG UM US UY UZ VA VC VE VG VI VN VU WF WS " +
            "YE YT ZA ZM ZW"
    ).split(' ')

/** Treffer für die Suche: Name oder Code enthält [query] (ohne Groß-/Kleinschreibung), alphabetisch nach Name. */
internal fun filterCountries(
    query: String,
    name: (String) -> String,
): List<Pair<String, String>> =
    SIGNUP_COUNTRIES
        .map { it to name(it) }
        .filter { (code, label) -> query.isBlank() || label.contains(query.trim(), true) || code.equals(query.trim(), true) }
        .sortedBy { it.second.lowercase() }

/** Wohnsitzland als Dropdown mit Suche: Tippen filtert, erst die Auswahl setzt den Code. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CountryField(
    country: String,
    onCountry: (String) -> Unit,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf(countryName(country)) }
    // Steht noch der gewählte Name im Feld, zeigt das Aufklappen alle Länder statt nur dieses einen.
    val filter = if (query == countryName(country)) "" else query
    val matches = remember(filter) { filterCountries(filter, ::countryName) }
    ExposedDropdownMenuBox(expanded, { expanded = it && enabled }, modifier) {
        OutlinedTextField(
            query,
            {
                query = it
                expanded = true
                // Getippt statt gewählt: kein Land, bis ein Treffer ausgewählt ist.
                onCountry("")
            },
            label = { Text(label) },
            singleLine = true,
            enabled = enabled,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled).testTag("auth_country"),
        )
        ExposedDropdownMenu(
            expanded && matches.isNotEmpty(),
            {
                expanded = false
                query = countryName(country)
            },
        ) {
            matches.forEach { (code, name) ->
                DropdownMenuItem(
                    { Text(name) },
                    {
                        onCountry(code)
                        query = name
                        expanded = false
                    },
                    modifier = Modifier.testTag("auth_country_$code"),
                )
            }
        }
    }
}

/** `TTMMJJJJ` (nur Ziffern) → ISO `JJJJ-MM-TT` für die Altersprüfung; `null`, solange unvollständig. */
internal fun birthDateIso(digits: String): String? =
    if (digits.length == 8 && digits.all(Char::isDigit)) "${digits.substring(4)}-${digits.substring(2, 4)}-${digits.take(2)}" else null

/** Zeigt bis zu 8 Ziffern als `TT-MM-JJJJ`; Bindestriche werden nur angezeigt, nicht getippt. */
internal object BirthDateTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text
        val shown =
            digits.take(2) + (if (digits.length > 2) "-" + digits.substring(2, minOf(4, digits.length)) else "") +
                (if (digits.length > 4) "-" + digits.substring(4) else "")
        val mapping =
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int) = offset + (if (offset > 2) 1 else 0) + (if (offset > 4) 1 else 0)

                override fun transformedToOriginal(offset: Int) =
                    (
                        offset - (
                            if (offset >
                                2
                            ) {
                                1
                            } else {
                                0
                            }
                        ) - (if (offset > 5) 1 else 0)
                    ).coerceIn(0, digits.length)
            }
        return TransformedText(AnnotatedString(shown), mapping)
    }
}

internal val BirthDateKeyboard = KeyboardOptions(keyboardType = KeyboardType.Number)
