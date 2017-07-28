package org.ergoplatform.modifiers.history

import org.ergoplatform.modifiers.ErgoPersistentModifier
import scorex.core.serialization.Serializer

import scala.util.Try

object HistoryModifierSerializer extends Serializer[ErgoPersistentModifier] {
  override def toBytes(obj: ErgoPersistentModifier): Array[Byte] = obj match {
    case m: Header =>
      Header.ModifierTypeId +: HeaderSerializer.toBytes(m)
    case m: ADProof =>
      ADProof.ModifierTypeId +: ADProofSerializer.toBytes(m)
    case m: BlockTransactions =>
      BlockTransactions.ModifierTypeId +: BlockTransactionsSerializer.toBytes(m)
    case m =>
      throw new Error(s"Serialization for unknown modifier: ${m.json.noSpaces}")
  }

  override def parseBytes(bytes: Array[Byte]): Try[ErgoPersistentModifier] = Try {
    bytes.head match {
      case Header.ModifierTypeId =>
        HeaderSerializer.parseBytes(bytes.tail).get
      case ADProof.ModifierTypeId =>
        ADProofSerializer.parseBytes(bytes.tail).get
      case BlockTransactions.ModifierTypeId =>
        BlockTransactionsSerializer.parseBytes(bytes.tail).get
      case m =>
        throw new Error(s"Deserialization for unknown type byte: $m")
    }
  }
}
