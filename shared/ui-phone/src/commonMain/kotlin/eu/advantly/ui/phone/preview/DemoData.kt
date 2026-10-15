package eu.advantly.ui.phone.preview

import eu.advantly.core.model.Discipline
import eu.advantly.core.model.Environment
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.Player
import eu.advantly.core.model.Side
import eu.advantly.core.model.Surface
import eu.advantly.core.scoring.Presets
import eu.advantly.presentation.match.LiveMatchPort
import eu.advantly.presentation.match.MatchSetup
import eu.advantly.ui.phone.app.AdvantlyAppModel

/**
 * Beispieldaten für Desktop-Preview und Screenshots (kein Produktcode-Pfad): drei Gäste, drei beendete Matches
 * (Tennis, Padel, Badminton) und ein Padel-Favorit. Namen sind Daten, keine UI-Texte.
 */
object DemoData {
    fun seed(model: AdvantlyAppModel) {
        val history = model.history.matches.value
        if (history.isNotEmpty() || model.live.active.value != null) return
        val players = model.players
        val self = players.self.value
        val jonas = players.addGuest("Jonas")
        val mia = players.addGuest("Mia")
        val tom = players.addGuest("Tom")
        val live = model.live

        // Tennis Einzel auf Sand: 6:4 6:3 (jedes Spiel zu null, Gewinnerfolge je Spiel)
        live.play(
            setup(Presets.TENNIS_SINGLES_BO3.format(), listOf(self), listOf(jonas), MatchMeta(Surface.CLAY, Environment.OUTDOOR)),
            games("ABABABABAA" + "AABABABAA"),
        )
        // Padel Golden Point: 4:6 4:6
        live.play(
            setup(Presets.PADEL_GOLDEN_BO3.format(), listOf(self, mia), listOf(jonas, tom), MatchMeta(Surface.ARTIFICIAL_GRASS)),
            games("BABABABABB".repeat(2)),
        )
        // Badminton ein Satz bis 21: 21:15
        live.play(
            setup(
                Presets.BADMINTON_1GAME_21.format(Discipline.SINGLES),
                listOf(self),
                listOf(tom),
                MatchMeta(environment = Environment.INDOOR),
            ),
            "AB".repeat(15) + "A".repeat(6),
        )
        players.saveFavorite(setup(Presets.PADEL_GOLDEN_BO3.format(), listOf(self, mia), listOf(jonas, tom), MatchMeta()))
    }

    private fun setup(
        format: MatchFormat,
        a: List<Player>,
        b: List<Player>,
        meta: MatchMeta,
    ) = MatchSetup(format, Lineup(a.map { it.id }, b.map { it.id }, a.first().id), meta, a + b)

    /** Jedes Spiel mit vier Punkten in Folge für den Gewinner (Tennis/Padel). */
    private fun games(winners: String): String = winners.map { it.toString().repeat(4) }.joinToString("")

    private fun LiveMatchPort.play(
        setup: MatchSetup,
        points: String,
    ) {
        start(setup)
        points.forEach { recordPoint(if (it == 'A') Side.A else Side.B) }
        dismiss()
    }
}
