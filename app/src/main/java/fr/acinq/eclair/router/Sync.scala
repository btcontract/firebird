/*
 * Copyright 2020 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.router

import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.wire._
import fr.acinq.eclair._
import fr.acinq.eclair.ShortChannelId
import scodec.bits.ByteVector
import shapeless.HNil

object Sync {
  def shouldRequestUpdate(ourTimestamp: Long, ourChecksum: Long, theirTimestamp: Long, theirChecksum: Long): Boolean = {
    // we request their channel_update if all those conditions are met:
    // - it is more recent than ours
    // - it is different from ours, or it is the same but ours is about to be stale
    // - it is not stale
    val theirsIsMoreRecent = ourTimestamp < theirTimestamp
    val areDifferent = ourChecksum != theirChecksum
    val oursIsAlmostStale = StaleChannels.isAlmostStale(ourTimestamp)
    val theirsIsStale = StaleChannels.isStale(theirTimestamp)
    theirsIsMoreRecent && (areDifferent || oursIsAlmostStale) && !theirsIsStale
  }

  def computeShortIdAndFlag(channels: Map[ShortChannelId, PublicChannel],
                            shortChannelId: ShortChannelId,
                            theirTimestamps: ReplyChannelRangeTlv.Timestamps,
                            theirChecksums: ReplyChannelRangeTlv.Checksums): Option[ShortChannelIdAndFlag] = {
    import QueryShortChannelIdsTlv.QueryFlagType._

    val flags = if (!channels.contains(shortChannelId)) {
      INCLUDE_CHANNEL_ANNOUNCEMENT | INCLUDE_CHANNEL_UPDATE_1 | INCLUDE_CHANNEL_UPDATE_2
    } else {
      // we already know this channel
      val (ourTimestamps, ourChecksums) = getChannelDigestInfo(channels)(shortChannelId)
      // if they don't provide timestamps or checksums, we set appropriate default values:
      // - we assume their timestamp is more recent than ours by setting timestamp = Long.MaxValue
      // - we assume their update is different from ours by setting checkum = Long.MaxValue (NB: our default value for checksum is 0)
      val shouldRequestUpdate1 = shouldRequestUpdate(ourTimestamps.timestamp1, ourChecksums.checksum1, theirTimestamps.timestamp1, theirChecksums.checksum1)
      val shouldRequestUpdate2 = shouldRequestUpdate(ourTimestamps.timestamp2, ourChecksums.checksum2, theirTimestamps.timestamp2, theirChecksums.checksum2)
      val flagUpdate1 = if (shouldRequestUpdate1) INCLUDE_CHANNEL_UPDATE_1 else 0
      val flagUpdate2 = if (shouldRequestUpdate2) INCLUDE_CHANNEL_UPDATE_2 else 0
      flagUpdate1 | flagUpdate2
    }

    if (flags == 0) None else Some(ShortChannelIdAndFlag(shortChannelId, flags))
  }

  def getChannelDigestInfo(channels: Map[ShortChannelId, PublicChannel])(shortChannelId: ShortChannelId): (ReplyChannelRangeTlv.Timestamps, ReplyChannelRangeTlv.Checksums) = {
    val c = channels(shortChannelId)
    val timestamp1 = c.update_1_opt.map(_.timestamp).getOrElse(0L)
    val timestamp2 = c.update_2_opt.map(_.timestamp).getOrElse(0L)
    val checksum1 = c.update_1_opt.map(getChecksum).getOrElse(0L)
    val checksum2 = c.update_2_opt.map(getChecksum).getOrElse(0L)
    (ReplyChannelRangeTlv.Timestamps(timestamp1 = timestamp1, timestamp2 = timestamp2), ReplyChannelRangeTlv.Checksums(checksum1 = checksum1, checksum2 = checksum2))
  }

  def crc32c(data: ByteVector): Long = {
    import com.google.common.hash.Hashing
    Hashing.crc32c().hashBytes(data.toArray).asInt() & 0xFFFFFFFFL
  }

  def getChecksum(u: ChannelUpdate): Long = {
    import u._

    val data = serializationResult(LightningMessageCodecs.channelUpdateChecksumCodec.encode(chainHash :: shortChannelId :: messageFlags :: channelFlags ::
      cltvExpiryDelta :: htlcMinimumMsat :: feeBaseMsat :: feeProportionalMillionths :: htlcMaximumMsat :: HNil))
    crc32c(data)
  }
}
