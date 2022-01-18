package org.ergoplatform.reemission

import org.ergoplatform.ErgoBox.R2
import org.ergoplatform.ErgoLikeContext.Height
import org.ergoplatform.ErgoScriptPredef.{boxCreationHeight, expectedMinerOutScriptBytesVal}
import org.ergoplatform.mining.emission.EmissionRules
import org.ergoplatform.{ErgoAddressEncoder, ErgoBox, Height, MinerPubkey, Outputs, Self}
import org.ergoplatform.settings.{ErgoSettings, MonetarySettings, ReemissionSettings}
import org.ergoplatform.wallet.boxes.ErgoBoxSerializer
import scorex.util.encode.Base16
import sigmastate.{AND, EQ, GE, GT, LE, Minus, OR, SByte, SCollection, SLong, STuple}
import sigmastate.Values.{ByteArrayConstant, ErgoTree, IntConstant, LongConstant}
import sigmastate.utxo.{ByIndex, ExtractAmount, ExtractRegisterAs, ExtractScriptBytes, OptionGet, SelectField, SizeOf}


object ReemissionRules {

  val basicChargeAmount = 12 // in ERG


  // todo: move box id to settings
  lazy val InjectionBoxBytes: Array[Byte] = Base16.decode("").get

  lazy val injectionBox: ErgoBox = ErgoBoxSerializer.parseBytes(InjectionBoxBytes)

  /**
    * Contract for boxes miners paying to according to EIP-27. Then anyone can merge multiple boxes locked by this
    * contract with reemission box
    */
  def payToReemission(reemissionNftId: Array[Byte]): ErgoTree = {
    // output of the reemission contract
    val reemissionOut = ByIndex(Outputs, IntConstant(0))

    val rOutTokens = OptionGet(ExtractRegisterAs(reemissionOut, R2)(SCollection(STuple(SCollection(SByte), SLong))))

    val firstTokenId = SelectField(ByIndex(rOutTokens, IntConstant(0)), 0.toByte)

    EQ(firstTokenId, ByteArrayConstant(reemissionNftId))
  }.toSigmaProp.treeWithSegregation

  /**
    * Reemission box contract
    */
  def reemissionBoxProp(ms: MonetarySettings, rs: ReemissionSettings): ErgoTree = {
    // output of the reemission contract
    val reemissionOut = ByIndex(Outputs, IntConstant(0))

    // output to pay miner
    val minerOut = ByIndex(Outputs, IntConstant(1))

    val rOutTokens = OptionGet(ExtractRegisterAs(reemissionOut, R2)(SCollection(STuple(SCollection(SByte), SLong))))

    val firstTokenId = SelectField(ByIndex(rOutTokens, IntConstant(0)), 1.toByte)

    val correctNftId = EQ(firstTokenId, ByteArrayConstant(rs.reemissionNftIdBytes))

    // miner's output must have script which is time-locking reward for miner's pubkey
    // box height must be the same as block height
    val correctMinerOutput = AND(
      EQ(ExtractScriptBytes(minerOut), expectedMinerOutScriptBytesVal(ms.minerRewardDelay, MinerPubkey)),
      EQ(Height, boxCreationHeight(minerOut))
    )

    // reemission output's height must be the same as block height
    val heightCorrect = EQ(boxCreationHeight(reemissionOut), Height)

    // reemission output's height is greater than reemission input
    val heightIncreased = GT(Height, boxCreationHeight(Self))

    // check that height is greater than end of emission (>= 2,080,800 for the mainnet)
    val afterEmission = GE(Height, IntConstant(rs.reemissionStartHeight))

    // reemission contract must be preserved
    val sameScriptRule = EQ(ExtractScriptBytes(Self), ExtractScriptBytes(reemissionOut))

    // miner's reward
    val coinsToIssue = 3 * 1000000000L // 3 ERG
    val correctCoinsIssued = EQ(coinsToIssue, Minus(ExtractAmount(Self), ExtractAmount(reemissionOut)))

    // when reemission contract box got merged with other boxes
    val sponsored = {
      val feeOut = ByIndex(Outputs, IntConstant(1))
      AND(
        GT(ExtractAmount(reemissionOut), ExtractAmount(Self)),
        LE(ExtractAmount(feeOut), LongConstant(10000000)), // 0.01 ERG
        EQ(SizeOf(Outputs), 2)
      )
    }

    AND(
      correctNftId,
      sameScriptRule,
      heightCorrect,
      OR(
        sponsored,
        AND(
          correctMinerOutput,
          afterEmission,
          heightIncreased,
          correctCoinsIssued
        )
      )
    ).toSigmaProp.treeWithSegregation
  }

  def reemissionForHeight(height: Height,
                          emissionRules: EmissionRules,
                          reemissionSettings: ReemissionSettings): Long = {
    val emission = emissionRules.emissionAtHeight(height)
    if (height >= reemissionSettings.activationHeight &&
          emission >= (basicChargeAmount + 3) * EmissionRules.CoinsInOneErgo) {
      basicChargeAmount * EmissionRules.CoinsInOneErgo
    } else if (emission > 3 * EmissionRules.CoinsInOneErgo) {
      emission - 3 * EmissionRules.CoinsInOneErgo
    } else {
      0L
    }
  }

  def main(args: Array[String]): Unit = {
    val settings = ErgoSettings.read()

    println("Monetary settings: " + settings.chainSettings.monetary)
    println("Reemission settings: " + settings.chainSettings.reemission)
    val ms = settings.chainSettings.monetary
    val rs = settings.chainSettings.reemission
    val emissionRules = settings.chainSettings.emissionRules

    val et = reemissionBoxProp(ms, rs)
    val enc = new ErgoAddressEncoder(ErgoAddressEncoder.MainnetNetworkPrefix)
    println("p2s address: " + enc.fromProposition(et))

    // reemission.tokenPreservation(Array.fill(32)(0: Byte), 0: Byte)

    var lowSet = false

    val total = (rs.activationHeight to rs.reemissionStartHeight).map { h =>
      val e = emissionRules.emissionAtHeight(h) / EmissionRules.CoinsInOneErgo
      val r = reemissionForHeight(h, emissionRules, rs) / EmissionRules.CoinsInOneErgo

      if ((e - r) == 3 && !lowSet) {
        println("Start of low emission period: " + h)
        lowSet = true
      }
      if ((h % 65536 == 0) || h == rs.activationHeight) {
        println(s"Emission at height $h : " + e)
        println(s"Reemission at height $h : " + r)
      }
      r
    }.sum

    val totalBlocks = total / 3 // 3 erg per block
    println("Total reemission: " + total + " ERG")
    println("Total reemission is enough for: " + totalBlocks + " blocks (" + totalBlocks / 720.0 / 365.0 + " years")
  }
}
