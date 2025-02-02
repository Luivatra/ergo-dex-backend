package org.ergoplatform.dex.resolver.repositories

import cats.data.OptionT
import cats.effect.concurrent.Ref
import cats.effect.{BracketThrow, Concurrent, Timer}
import cats.syntax.show._
import cats.{FlatMap, Functor, Parallel}
import derevo.derive
import io.circe.Json
import io.circe.syntax._
import io.github.oskin1.rocksdb.scodec.TxRocksDB
import org.ergoplatform.dex.domain.amm.state.{Confirmed, Predicted, PredictionLink}
import org.ergoplatform.dex.domain.amm.{CFMMPool, PoolId}
import org.ergoplatform.ergo.BoxId
import scodec.Codec
import scodec.codecs.{bool, utf8}
import tofu.concurrent.MakeRef
import tofu.higherKind.Mid
import tofu.higherKind.derived.representableK
import tofu.logging.{Logging, Logs}
import tofu.syntax.foption._
import tofu.syntax.logging._
import tofu.syntax.monadic._

@derive(representableK)
trait CFMMPools[F[_]] {

  /** Get last predicted state of a pool with the given `id`.
    */
  def getLastPredicted(id: PoolId): F[Option[Predicted[CFMMPool]]]

  /** Get last confirmed state of a pool with the given `id`.
    */
  def getLastConfirmed(id: PoolId): F[Option[Confirmed[CFMMPool]]]

  /** Persist predicted pool.
    */
  def put(pool: Predicted[CFMMPool]): F[Unit]

  /** Update predicted pool.
    */
  def update(pool: Predicted[CFMMPool]): F[Unit]

  /** Persist confirmed pool state.
    */
  def put(pool: Confirmed[CFMMPool]): F[Unit]

  /** Check whether a pool state prediction with the given `id` exists.
    */
  def existsPrediction(id: BoxId): F[Boolean]

  /** Drop prediction.
    */
  def dropPrediction(id: BoxId): F[Unit]
}

object CFMMPools {

  def make[I[_]: FlatMap, F[_]: Parallel: Concurrent: Timer](implicit
    rocks: TxRocksDB[F],
    logs: Logs[I, F]
  ): I[CFMMPools[F]] =
    logs.forService[CFMMPools[F]].map { implicit l =>
      new CFMMPoolsTracing[F] attach new CFMMPoolsRocks[F]
    }

  def ephemeral[I[_]: FlatMap, F[_]: FlatMap](implicit makeRef: MakeRef[I, F], logs: Logs[I, F]): I[CFMMPools[F]] =
    for {
      store                      <- makeRef.refOf(Map.empty[String, Json])
      implicit0(log: Logging[F]) <- logs.forService[CFMMPools[F]]
    } yield new CFMMPoolsTracing[F] attach new InMemory[F](store)

  private def PredictedKey(id: BoxId)      = s"predicted:$id" // -> link(state)
  private def LastPredictedKey(id: PoolId) = s"predicted:last:$id" // -> state
  private def LastConfirmedKey(id: PoolId) = s"confirmed:last:$id"

  final class CFMMPoolsRocks[F[_]: BracketThrow](implicit rocks: TxRocksDB[F]) extends CFMMPools[F] {

    implicit val codecString: Codec[String] = utf8
    implicit val codecBool: Codec[Boolean]  = bool

    def getLastPredicted(id: PoolId): F[Option[Predicted[CFMMPool]]] =
      rocks.get[String, Predicted[CFMMPool]](LastPredictedKey(id))

    def getLastConfirmed(id: PoolId): F[Option[Confirmed[CFMMPool]]] =
      rocks.get[String, Confirmed[CFMMPool]](LastConfirmedKey(id))

    def put(pool: Predicted[CFMMPool]): F[Unit] =
      rocks.beginTransaction.use { tx =>
        for {
          prevStateRef <- tx.get[String, Predicted[CFMMPool]](LastPredictedKey(pool.predicted.poolId))
                            .mapIn(_.predicted.box.boxId)
          _ <- tx.put(LastPredictedKey(pool.predicted.poolId), pool)
          _ <- tx.put(PredictedKey(pool.predicted.box.boxId), PredictionLink(pool, prevStateRef))
          _ <- tx.commit
        } yield ()
      }

    def update(pool: Predicted[CFMMPool]): F[Unit] =
      rocks.beginTransaction.use { tx =>
        (for {
          link <- OptionT(tx.get[String, PredictionLink[CFMMPool]](PredictedKey(pool.predicted.box.boxId)))
          updatedLink = link.copy(state = pool)
          _ <- OptionT.liftF(tx.put(LastPredictedKey(pool.predicted.poolId), pool))
          _ <- OptionT.liftF(tx.put(PredictedKey(pool.predicted.box.boxId), updatedLink))
          _ <- OptionT.liftF(tx.commit)
        } yield ()).value.void
      }

    def put(pool: Confirmed[CFMMPool]): F[Unit] =
      rocks.put(LastConfirmedKey(pool.confirmed.poolId), pool)

    def existsPrediction(id: BoxId): F[Boolean] =
      rocks.get[String, PredictionLink[Predicted[CFMMPool]]](PredictedKey(id)).map(_.isDefined)

    def dropPrediction(id: BoxId): F[Unit] =
      rocks.beginTransaction.use { tx =>
        (for {
          currentLink <- OptionT(tx.get[String, PredictionLink[CFMMPool]](PredictedKey(id)))
          poolId = currentLink.state.predicted.poolId
          _ <- currentLink.predecessorBoxId match {
                 case Some(predId) =>
                   for {
                     prevPool <- OptionT(tx.get[String, PredictionLink[CFMMPool]](PredictedKey(predId)))
                     _ <- OptionT.liftF(
                            tx.put(LastPredictedKey(poolId), prevPool.state) >>
                            tx.delete(PredictedKey(id))
                          )
                   } yield ()
                 case None =>
                   OptionT.liftF(tx.delete(LastPredictedKey(poolId)) >> tx.delete(PredictedKey(id)))
               }
          _ <- OptionT.liftF(tx.commit)
        } yield ()).value.void
      }
  }

  final class InMemory[F[_]: Functor](store: Ref[F, Map[String, Json]]) extends CFMMPools[F] {

    def getLastPredicted(id: PoolId): F[Option[Predicted[CFMMPool]]] =
      store.get.map(_.get(LastPredictedKey(id)) >>= (_.as[Predicted[CFMMPool]].toOption))

    def getLastConfirmed(id: PoolId): F[Option[Confirmed[CFMMPool]]] =
      store.get.map(_.get(LastConfirmedKey(id)) >>= (_.as[Confirmed[CFMMPool]].toOption))

    def put(pool: Predicted[CFMMPool]): F[Unit] =
      store.update {
        _.updated(LastPredictedKey(pool.predicted.poolId), pool.asJson)
          .updated(PredictedKey(pool.predicted.box.boxId), Json.Null)
      }

    def update(pool: Predicted[CFMMPool]): F[Unit] = put(pool)

    def put(pool: Confirmed[CFMMPool]): F[Unit] =
      store.update(_.updated(LastConfirmedKey(pool.confirmed.poolId), pool.asJson))

    def existsPrediction(id: BoxId): F[Boolean] =
      store.get.map(_.contains(PredictedKey(id)))

    def dropPrediction(id: BoxId): F[Unit] =
      store.update(_ - id.value)
  }

  final class CFMMPoolsTracing[F[_]: FlatMap: Logging] extends CFMMPools[Mid[F, *]] {

    def getLastPredicted(id: PoolId): Mid[F, Option[Predicted[CFMMPool]]] =
      for {
        _ <- trace"getLastPredicted(id=$id)"
        r <- _
        _ <- trace"getLastPredicted(id=$id) -> $r"
      } yield r

    def getLastConfirmed(id: PoolId): Mid[F, Option[Confirmed[CFMMPool]]] =
      for {
        _ <- trace"getLastConfirmed(id=$id)"
        r <- _
        _ <- trace"getLastConfirmed(id=$id) -> $r"
      } yield r

    def put(pool: Predicted[CFMMPool]): Mid[F, Unit] =
      for {
        _ <- trace"put(pool=$pool)"
        r <- _
        _ <- trace"put(pool=$pool) -> $r"
      } yield r

    def put(pool: Confirmed[CFMMPool]): Mid[F, Unit] =
      for {
        _ <- trace"put(pool=$pool)"
        r <- _
        _ <- trace"put(pool=$pool) -> $r"
      } yield r

    def update(pool: Predicted[CFMMPool]): Mid[F, Unit] =
      for {
        _ <- trace"update(pool=$pool)"
        r <- _
        _ <- trace"update(pool=$pool) -> $r"
      } yield r

    def existsPrediction(id: BoxId): Mid[F, Boolean] =
      for {
        _ <- trace"existsPredicted(id=$id)"
        r <- _
        _ <- trace"existsPredicted(id=$id) -> $r"
      } yield r

    def dropPrediction(id: BoxId): Mid[F, Unit] =
      for {
        _ <- trace"dropPrediction(id=$id)"
        r <- _
        _ <- trace"dropPrediction(id=$id) -> $r"
      } yield r
  }
}
