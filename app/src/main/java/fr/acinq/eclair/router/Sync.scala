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
import fr.acinq.eclair.ShortChannelId

object Sync {
  def shouldRequestUpdate(ourTimestamp: Long, theirTimestamp: Long): Boolean = {
    val theirsIsMoreRecent = ourTimestamp < theirTimestamp
    val theirsIsStale = StaleChannels.isStale(theirTimestamp)
    theirsIsMoreRecent && !theirsIsStale
  }

  def computeShortIdAndFlag(channels: Map[ShortChannelId, PublicChannel],
                            includeNodeAnnouncements: Boolean,
                            shortChannelId: ShortChannelId,
                            theirTimestamps: ReplyChannelRangeTlv.Timestamps): Option[ShortChannelIdAndFlag] = {
    import QueryShortChannelIdsTlv.QueryFlagType._

    val flagsNodes = if (includeNodeAnnouncements) INCLUDE_NODE_ANNOUNCEMENT_1 | INCLUDE_NODE_ANNOUNCEMENT_2 else 0

    val flags = if (!channels.contains(shortChannelId)) {
      INCLUDE_CHANNEL_ANNOUNCEMENT | INCLUDE_CHANNEL_UPDATE_1 | INCLUDE_CHANNEL_UPDATE_2
    } else {
      // we already know this channel
      val ourTimestamps = getChannelDigestInfo(channels)(shortChannelId)
      // if they don't provide timestamps or checksums, we set appropriate default values:
      // - we assume their timestamp is more recent than ours by setting timestamp = Long.MaxValue
      // - we assume their update is different from ours by setting checkum = Long.MaxValue (NB: our default value for checksum is 0)
      val shouldRequestUpdate1 = shouldRequestUpdate(ourTimestamps.timestamp1, theirTimestamps.timestamp1)
      val shouldRequestUpdate2 = shouldRequestUpdate(ourTimestamps.timestamp2, theirTimestamps.timestamp2)
      val flagUpdate1 = if (shouldRequestUpdate1) INCLUDE_CHANNEL_UPDATE_1 else 0
      val flagUpdate2 = if (shouldRequestUpdate2) INCLUDE_CHANNEL_UPDATE_2 else 0
      flagUpdate1 | flagUpdate2
    }

    if (flags == 0) None else Some(ShortChannelIdAndFlag(shortChannelId, flags | flagsNodes))
  }

  def getChannelDigestInfo(channels: Map[ShortChannelId, PublicChannel])(shortChannelId: ShortChannelId): ReplyChannelRangeTlv.Timestamps = {
    val c = channels(shortChannelId)
    val timestamp1 = c.update_1_opt.map(_.timestamp).getOrElse(0L)
    val timestamp2 = c.update_2_opt.map(_.timestamp).getOrElse(0L)
    ReplyChannelRangeTlv.Timestamps(timestamp1 = timestamp1, timestamp2 = timestamp2)
  }
}
