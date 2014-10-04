package lila.round

import akka.pattern.ask
import chess.{ Game => ChessGame, Board, Clock, Variant }

import lila.db.api._
import lila.game.tube.gameTube
import lila.game.{ GameRepo, Game, Event, Progress, Pov, PlayerRef, Namer, Source }
import lila.pref.{ Pref, PrefApi }
import makeTimeout.short

private[round] final class Drawer(
    messenger: Messenger,
    finisher: Finisher,
    prefApi: PrefApi) {

  def autoThreefold(game: Game): Fu[Option[Pov]] = Pov(game).map { pov =>
    import Pref.PrefZero
    pov.player.userId ?? prefApi.getPref map { pref =>
      pref.autoThreefold == Pref.AutoThreefold.ALWAYS || {
        pref.autoThreefold == Pref.AutoThreefold.TIME &&
          game.clock ?? { _.remainingTime(pov.color) < 30 }
      }
    } map (_ option pov)
  }.sequenceFu map (_.flatten.headOption)

  def yes(pov: Pov): Fu[Events] = pov match {
    case pov if pov.opponent.isOfferingDraw =>
      finisher(pov.game, _.Draw, None, Some(_.drawOfferAccepted))
    case Pov(g, color) if (g playerCanOfferDraw color) => GameRepo save {
      messenger.system(g, _.drawOfferSent)
      Progress(g) map { g => g.updatePlayer(color, _ offerDraw g.turns) }
    } inject List(Event.ReloadOwner)
    case _ => fufail("[drawer] invalid yes " + pov)
  }

  def no(pov: Pov): Fu[Events] = pov match {
    case Pov(g, color) if pov.player.isOfferingDraw => GameRepo save {
      messenger.system(g, _.drawOfferCanceled)
      Progress(g) map { g => g.updatePlayer(color, _.removeDrawOffer) }
    } inject List(Event.ReloadOwner)
    case Pov(g, color) if pov.opponent.isOfferingDraw => GameRepo save {
      messenger.system(g, _.drawOfferDeclined)
      Progress(g) map { g => g.updatePlayer(!color, _.removeDrawOffer) }
    } inject List(Event.ReloadOwner)
    case _ => fufail("[drawer] invalid no " + pov)
  }

  def claim(pov: Pov): Fu[Events] =
    (pov.game.playable && pov.game.toChessHistory.threefoldRepetition) ?? finisher(pov.game, _.Draw)

  def force(game: Game): Fu[Events] = finisher(game, _.Draw, None, None)
}
