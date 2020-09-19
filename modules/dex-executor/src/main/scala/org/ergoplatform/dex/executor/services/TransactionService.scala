package org.ergoplatform.dex.executor.services

import cats.{Applicative, Functor, Monad}
import cats.data.NonEmptyList
import org.ergoplatform.ErgoBox.{NonMandatoryRegisterId, R4}
import org.ergoplatform.contracts.{DexBuyerContractParameters, DexLimitOrderContracts, DexSellerContractParameters}
import org.ergoplatform.dex.domain.models.Trade.AnyTrade
import org.ergoplatform.dex.domain.models.Order.{Ask, Bid}
import org.ergoplatform.dex.domain.syntax.ergo._
import org.ergoplatform.dex.domain.syntax.trade._
import org.ergoplatform.dex.executor.config.ExchangeConfig
import org.ergoplatform.dex.executor.context.TxContext
import org.ergoplatform.{ErgoBoxCandidate, ErgoLikeTransaction, Input}
import sigmastate.SType.AnyOps
import sigmastate.Values.{EvaluatedValue, _}
import sigmastate._
import sigmastate.eval._
import sigmastate.interpreter.ProverResult
import tofu.HasContext
import tofu.syntax.monadic._
import tofu.syntax.context._

abstract class TransactionService[F[_]] {

  /** Assembly Ergo transaction from a given `match`.
    */
  def makeTx(anyMatch: AnyTrade)(implicit ctx: TxContext): F[ErgoLikeTransaction]
}

object TransactionService {

  /** Implements processing of trades necessarily involving ERG.
    */
  final private class ErgoToTokenTransactionService[F[_]: Monad: HasContext[*[_], ExchangeConfig]]
    extends TransactionService[F] {

    def makeTx(anyMatch: AnyTrade)(implicit ctx: TxContext): F[ErgoLikeTransaction] =
      outputs(anyMatch).map(_.toList.toVector).map { out =>
        val in = inputs(anyMatch).toList.toVector
        ErgoLikeTransaction(in, out)
      }
  }

  private val MinBoxValue = 1000000

  private[services] def inputs(anyMatch: AnyTrade): NonEmptyList[Input] =
    anyMatch.orders.map { ord =>
      Input(ord.meta.boxId.toErgo, ProverResult.empty)
    }

  private[services] def outputs[F[_]: Monad: HasContext[*[_], ExchangeConfig]](
    trade: AnyTrade
  )(implicit ctx: TxContext): F[NonEmptyList[ErgoBoxCandidate]] = {
    val toFill      = trade.order.amount
    val canBeFilled = trade.counterOrders.map(_.amount).toList.sum
    trade.refine match {
      case Left(sellTrade) =>
        val order = sellTrade.order
        val (totalCost, _) = sellTrade.counterOrders.foldLeft(0L -> toFill) {
          case ((acc, rem), ord) =>
            val amt = math.min(ord.amount, rem)
            (acc + amt * ord.price) -> (toFill - amt)
        }
        val (counterEval, _) = sellTrade.counterOrders.foldLeft(List.empty[ErgoBoxCandidate].pure[F] -> toFill) {
          case ((acc, rem), ord) =>
            if (rem > 0) (buyOut(ord, rem) >>= (xs => acc.map(_ ++ xs.toList))) -> (rem - ord.amount)
            else acc                                                            -> rem
        }
        val orderEval = sellOut(order, canBeFilled, totalCost)
        orderEval >>= (xs => counterEval.map(xs.concat))
      case Right(buyTrade) =>
        val order = buyTrade.order
        val (counterEval, _) = buyTrade.counterOrders.foldLeft(List.empty[ErgoBoxCandidate].pure[F] -> toFill) {
          case ((acc, rem), ord) =>
            val cost = math.min(ord.amount, rem)
            if (rem > 0) (sellOut(ord, rem, cost) >>= (xs => acc.map(_ ++ xs.toList))) -> (rem - ord.amount)
            else acc                                                                   -> rem
        }
        val orderEval = buyOut(order, canBeFilled)
        orderEval >>= (xs => counterEval.map(xs.concat))
    }
  }

  private[services] def sellOut[F[_]: Functor: HasContext[*[_], ExchangeConfig]](
    order: Ask,
    canFill: Long,
    totalCost: Long
  )(implicit ctx: TxContext): F[NonEmptyList[ErgoBoxCandidate]] =
    context map { conf =>
      val amount    = math.min(order.amount, canFill)
      val fee       = amount * order.feePerToken
      val rem       = order.meta.boxValue - fee
      val reward    = amount * order.price
      val pk        = order.meta.ownerAddress.script
      val unfilled  = order.amount - canFill
      val boxValue  = math.max(rem, MinBoxValue)
      val feeAmount = if (boxValue == rem) fee else fee - MinBoxValue
      val dexFee    = new ErgoBoxCandidate(feeAmount, conf.rewardAddress.toErgoTree, ctx.curHeight)
      if (unfilled <= 0) {
        val ret =
          if (totalCost == amount) new ErgoBoxCandidate(reward + rem, pk, ctx.curHeight)
          else {
            val spreadTokens = Colls.fromItems(order.quoteAsset.toErgo -> (amount - totalCost))
            new ErgoBoxCandidate(reward + rem, pk, ctx.curHeight, spreadTokens)
          }
        NonEmptyList.of(ret, dexFee)
      } else {
        val ret = new ErgoBoxCandidate(reward, pk, ctx.curHeight)
        val residualParams =
          DexSellerContractParameters(
            order.meta.ownerAddress.pubkey,
            order.quoteAsset.toErgo,
            order.price,
            order.feePerToken
          )
        val residualScript = DexLimitOrderContracts.sellerContractInstance(residualParams).ergoTree
        val residualTokens = Colls.fromItems(order.quoteAsset.toErgo -> unfilled)
        val residualRegisters = Map(R4 -> Constant(order.meta.boxId.toSigma.asWrappedType, SCollection(SByte)).value)
          .asInstanceOf[Map[NonMandatoryRegisterId, EvaluatedValue[SType]]]
        val residual = new ErgoBoxCandidate(rem, residualScript, ctx.curHeight, residualTokens, residualRegisters)
        NonEmptyList.of(ret, residual, dexFee)
      }
    }

  private[services] def buyOut[F[_]: Functor: HasContext[*[_], ExchangeConfig]](order: Bid, canFill: Long)(implicit
    ctx: TxContext
  ): F[NonEmptyList[ErgoBoxCandidate]] =
    context map { conf =>
      val amount    = math.min(order.amount, canFill)
      val fee       = amount * order.feePerToken
      val rem       = order.meta.boxValue - fee
      val reward    = amount
      val pk        = order.meta.ownerAddress.script
      val unfilled  = order.amount - canFill
      val boxValue  = math.max(rem, MinBoxValue)
      val feeAmount = if (boxValue == rem) fee else fee - MinBoxValue
      val retTokens = Colls.fromItems(order.quoteAsset.toErgo -> reward)
      val dexFee    = new ErgoBoxCandidate(feeAmount, conf.rewardAddress.toErgoTree, ctx.curHeight)
      if (unfilled <= 0) {
        val ret = new ErgoBoxCandidate(rem, pk, ctx.curHeight, retTokens)
        NonEmptyList.of(ret, dexFee)
      } else {
        val ret = new ErgoBoxCandidate(rem, pk, ctx.curHeight, retTokens)
        val residualParams =
          DexBuyerContractParameters(
            order.meta.ownerAddress.pubkey,
            order.quoteAsset.toErgo,
            order.price,
            order.feePerToken
          )
        val residualScript = DexLimitOrderContracts.buyerContractInstance(residualParams).ergoTree
        val residualRegisters = Map(R4 -> Constant(order.meta.boxId.toSigma.asWrappedType, SCollection(SByte)).value)
          .asInstanceOf[Map[NonMandatoryRegisterId, EvaluatedValue[SType]]]
        val residual = new ErgoBoxCandidate(rem, residualScript, ctx.curHeight, Colls.emptyColl, residualRegisters)
        NonEmptyList.of(ret, residual, dexFee)
      }
    }
}
