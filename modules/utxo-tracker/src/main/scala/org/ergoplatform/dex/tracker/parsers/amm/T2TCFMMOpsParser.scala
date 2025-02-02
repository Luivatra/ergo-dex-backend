package org.ergoplatform.dex.tracker.parsers.amm

import cats.effect.Clock
import cats.{Applicative, Monad}
import org.ergoplatform.dex.domain.AssetAmount
import org.ergoplatform.dex.domain.amm._
import org.ergoplatform.dex.protocol.ErgoTreeSerializer
import org.ergoplatform.dex.protocol.amm.AMMType.T2T_CFMM
import org.ergoplatform.dex.protocol.amm.{T2TCFMMTemplates => templates}
import org.ergoplatform.ergo.models.Output
import org.ergoplatform.ergo.syntax._
import org.ergoplatform.ergo.{Address, ErgoTreeTemplate, TokenId}
import org.ergoplatform.{ErgoAddressEncoder, P2PKAddress}
import tofu.syntax.embed._
import tofu.syntax.monadic._
import tofu.syntax.time.now

final class T2TCFMMOpsParser[F[_]: Applicative: Clock](ts: Long)(implicit
  e: ErgoAddressEncoder
) extends AMMOpsParser[T2T_CFMM, F] {

  def deposit(box: Output): F[Option[Deposit]] = {
    val tree     = ErgoTreeSerializer.default.deserialize(box.ergoTree)
    val template = ErgoTreeTemplate.fromBytes(tree.template)
    val parsed =
      if (template == templates.deposit) {
        for {
          poolId <- tree.constants.parseBytea(13).map(PoolId.fromBytes)
          inX    <- box.assets.lift(0).map(a => AssetAmount(a.tokenId, a.amount, a.name))
          inY    <- box.assets.lift(1).map(a => AssetAmount(a.tokenId, a.amount, a.name))
          dexFee <- tree.constants.parseLong(15)
          p2pk   <- tree.constants.parsePk(0).map(pk => Address.fromStringUnsafe(P2PKAddress(pk).toString))
          params = DepositParams(inX, inY, dexFee, p2pk)
        } yield Deposit(poolId, ts, params, box)
      } else None
    parsed.pure
  }

  def redeem(box: Output): F[Option[Redeem]] = {
    val tree     = ErgoTreeSerializer.default.deserialize(box.ergoTree)
    val template = ErgoTreeTemplate.fromBytes(tree.template)
    val parsed =
      if (template == templates.redeem) {
        for {
          poolId <- tree.constants.parseBytea(13).map(PoolId.fromBytes)
          inLP   <- box.assets.lift(0).map(a => AssetAmount(a.tokenId, a.amount, a.name))
          dexFee <- tree.constants.parseLong(15)
          p2pk   <- tree.constants.parsePk(0).map(pk => Address.fromStringUnsafe(P2PKAddress(pk).toString))
          params = RedeemParams(inLP, dexFee, p2pk)
        } yield Redeem(poolId, ts, params, box)
      } else None
    parsed.pure
  }

  def swap(box: Output): F[Option[Swap]] = {
    val tree     = ErgoTreeSerializer.default.deserialize(box.ergoTree)
    val template = ErgoTreeTemplate.fromBytes(tree.template)
    val parsed =
      if (template == templates.swapSell) {
        for {
          poolId       <- tree.constants.parseBytea(14).map(PoolId.fromBytes)
          inAmount     <- box.assets.lift(0).map(a => AssetAmount(a.tokenId, a.amount, a.name))
          outId        <- tree.constants.parseBytea(2).map(TokenId.fromBytes)
          minOutAmount <- tree.constants.parseLong(15)
          outAmount = AssetAmount(outId, minOutAmount, None)
          dexFeePerTokenNum   <- tree.constants.parseLong(16)
          dexFeePerTokenDenom <- tree.constants.parseLong(17)
          p2pk                <- tree.constants.parsePk(0).map(pk => Address.fromStringUnsafe(P2PKAddress(pk).toString))
          params = SwapParams(inAmount, outAmount, dexFeePerTokenNum, dexFeePerTokenDenom, p2pk)
        } yield Swap(poolId, ts, params, box)
      } else None
    parsed.pure
  }
}

object T2TCFMMOpsParser {

  def make[F[_]: Monad: Clock](implicit e: ErgoAddressEncoder): AMMOpsParser[T2T_CFMM, F] =
    now.millis.map(ts => new T2TCFMMOpsParser(ts): AMMOpsParser[T2T_CFMM, F]).embed
}
