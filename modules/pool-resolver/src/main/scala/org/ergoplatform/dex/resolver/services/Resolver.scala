package org.ergoplatform.dex.resolver.services

import cats.{Functor, Monad}
import monocle.macros.syntax.lens._
import org.ergoplatform.dex.domain.amm.state.{Confirmed, Predicted}
import org.ergoplatform.dex.domain.amm.{CFMMPool, PoolId}
import org.ergoplatform.dex.resolver.repositories.CFMMPools
import org.ergoplatform.ergo.BoxId
import tofu.logging.{Logging, Logs}
import tofu.syntax.logging._
import tofu.syntax.monadic._

trait Resolver[F[_]] {

  /** Get pool state by pool id.
    */
  def resolve(id: PoolId): F[Option[CFMMPool]]

  /** Persist predicted pool.
    */
  def put(pool: Predicted[CFMMPool]): F[Unit]

  /** Invalidate pool state.
    */
  def invalidate(boxId: BoxId): F[Unit]
}

object Resolver {

  def make[I[+_]: Functor, F[_]: Monad](implicit pools: CFMMPools[F], logs: Logs[I, F]): I[Resolver[F]] =
    logs.forService[Resolver[F]] map (implicit l => new Live[F])

  final class Live[F[_]: Monad: Logging](implicit pools: CFMMPools[F]) extends Resolver[F] {

    def resolve(id: PoolId): F[Option[CFMMPool]] =
      for {
        confirmedOpt <- pools.getLastConfirmed(id)
        predictedOpt <- pools.getLastPredicted(id)
        pool <- (confirmedOpt, predictedOpt) match {
                  case (Some(Confirmed(confirmed)), Some(pps @ Predicted(predicted))) =>
                    val upToDate = confirmed.box.lastConfirmedBoxGix <= predicted.box.lastConfirmedBoxGix
                    for {
                      consistentChain <- pools.existsPrediction(confirmed.box.boxId)
                      pessimistic =
                        if (consistentChain) {
                          val updatedPool =
                            pps.lens(_.predicted.box.lastConfirmedBoxGix).set(confirmed.box.lastConfirmedBoxGix)
                          debug"Updating consistent chain for Pool{id='$id'}" >>
                          pools.update(updatedPool) as updatedPool.predicted
                        } else warn"Prediction chain is inconsistent for Pool{id='$id'}" as confirmed
                      pool <- if (upToDate) predicted.pure else pessimistic
                    } yield Some(pool)
                  case (Some(Confirmed(confirmed)), None) =>
                    debug"No predictions found for Pool{id='$id'}" as Some(confirmed)
                  case _ =>
                    warn"Got resolve request for an unknown Pool{id='$id'}" as None
                }
      } yield pool

    def put(pool: Predicted[CFMMPool]): F[Unit] =
      debug"New prediction for Pool{id='${pool.predicted.poolId}'}, $pool" >>
      pools.put(pool)

    def invalidate(boxId: BoxId): F[Unit] =
      debug"Invalidating PoolState{boxId=$boxId}" >>
      pools.dropPrediction(boxId)
  }
}
