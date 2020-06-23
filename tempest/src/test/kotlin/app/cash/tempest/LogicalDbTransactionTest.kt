/*
 * Copyright 2020 Square Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.cash.tempest

import app.cash.tempest.example.AlbumTrack
import app.cash.tempest.example.AlbumTrackKey
import app.cash.tempest.example.MusicDb
import app.cash.tempest.example.MusicDbTestModule
import app.cash.tempest.example.PlaylistEntry
import app.cash.tempest.example.PlaylistEntryKey
import app.cash.tempest.example.PlaylistInfo
import app.cash.tempest.example.PlaylistInfoKey
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBTransactionWriteExpression
import com.amazonaws.services.dynamodbv2.model.AttributeValue
import com.amazonaws.services.dynamodbv2.model.TransactionCanceledException
import java.time.Duration
import javax.inject.Inject
import misk.aws.dynamodb.testing.DockerDynamoDb
import misk.testing.MiskExternalDependency
import misk.testing.MiskTest
import misk.testing.MiskTestModule
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.junit.jupiter.api.Test

@MiskTest(startService = true)
class LogicalDbTransactionTest {

  @MiskTestModule
  val module = MusicDbTestModule()
  @MiskExternalDependency
  val dockerDynamoDb = DockerDynamoDb

  @Inject lateinit var musicDb: MusicDb

  @Test
  fun transactionLoadAfterSave() {
    val previousPlaylistInfo = PlaylistInfo("L_1", "WFH Music", 0)
    musicDb.playlists.info.save(previousPlaylistInfo)
    val albumTrack = AlbumTrack("M_1", "T_1", "dreamin'", Duration.parse("PT3M28S"))
    musicDb.albums.tracks.save(albumTrack)
    val playlistEntry = PlaylistEntry("L_1", "M_1:T_1")
    musicDb.playlists.entries.save(playlistEntry)
    // Update Playlist Info to reflect updated entry.
    val playlistInfo = previousPlaylistInfo.copy(
        playlist_size = 1
    )
    musicDb.playlists.info.save(playlistInfo)

    val loadedItems =
        musicDb.transactionLoad(PlaylistInfoKey("L_1"), PlaylistEntryKey("L_1", "M_1:T_1"))
    val loadedPlaylistInfo = loadedItems.getItems<PlaylistInfo>().single()
    assertThat(loadedPlaylistInfo.playlist_token).isEqualTo(playlistInfo.playlist_token)
    assertThat(loadedPlaylistInfo.playlist_size).isEqualTo(playlistInfo.playlist_size)
    val loadedPlaylistEntry = loadedItems.getItems<PlaylistEntry>().single()
    assertThat(loadedPlaylistEntry.playlist_token).isEqualTo(playlistEntry.playlist_token)
    assertThat(loadedPlaylistEntry.album_track_token).isEqualTo(playlistEntry.album_track_token)
  }

  @Test
  fun transactionLoadAfterTransactionWrite() {
    val previousPlaylistInfo = PlaylistInfo("L_1", "WFH Music", 0)
    musicDb.playlists.info.save(previousPlaylistInfo)
    val albumTrack = AlbumTrack("M_1", "T_1", "dreamin'", Duration.parse("PT3M28S"))
    musicDb.albums.tracks.save(albumTrack)
    val playlistEntry = PlaylistEntry("L_1", "M_1:T_1")
    // Add a PlaylistEntry and update PlaylistInfo, in an ACID manner using transactionWrite.
    val playlistInfo = previousPlaylistInfo.copy(
        playlist_size = 1
    )
    val writeTransaction = TransactionWriteSet.Builder()
        .save(playlistInfo)
        .save(playlistEntry)
        .build()
    musicDb.transactionWrite(writeTransaction)

    // Read items at the same time in a serializable manner.
    val loadedItems =
        musicDb.transactionLoad(PlaylistInfoKey("L_1"), PlaylistEntryKey("L_1", "M_1:T_1"))
    val loadedPlaylistInfo = loadedItems.getItems<PlaylistInfo>().single()
    assertThat(loadedPlaylistInfo.playlist_token).isEqualTo(playlistInfo.playlist_token)
    assertThat(loadedPlaylistInfo.playlist_size).isEqualTo(playlistInfo.playlist_size)
    val loadedPlaylistEntry = loadedItems.getItems<PlaylistEntry>().single()
    assertThat(loadedPlaylistEntry.playlist_token).isEqualTo(playlistEntry.playlist_token)
    assertThat(loadedPlaylistEntry.album_track_token).isEqualTo(playlistEntry.album_track_token)
  }

  @Test
  fun conditionalUpdateInTransactionWrite() {
    val previousPlaylistInfo = PlaylistInfo("L_1", "WFH Music", 0)
    musicDb.playlists.info.save(previousPlaylistInfo)
    val albumTrack = AlbumTrack("M_1", "T_1", "dreamin'", Duration.parse("PT3M28S"))
    musicDb.albums.tracks.save(albumTrack)

    val playlistEntry = PlaylistEntry("L_1", "M_1:T_1")
    // Add a PlaylistEntry and update PlaylistInfo, in an ACID manner using transactionWrite.
    val playlistInfo = previousPlaylistInfo.copy(
        playlist_size = 1
    )
    val writeTransaction = TransactionWriteSet.Builder()
        .save(playlistInfo, DynamoDBTransactionWriteExpression()
            .withConditionExpression("playlist_size = :playlist_size")
            .withExpressionAttributeValues(mapOf(
                ":playlist_size" to AttributeValue().withN("${previousPlaylistInfo.playlist_size}")
            )))
        .save(playlistEntry)
        .build()
    musicDb.transactionWrite(writeTransaction)

    val loadedItems =
        musicDb.transactionLoad(PlaylistInfoKey("L_1"), PlaylistEntryKey("L_1", "M_1:T_1"))
    val loadedPlaylistInfo = loadedItems.getItems<PlaylistInfo>().single()
    assertThat(loadedPlaylistInfo.playlist_token).isEqualTo(playlistInfo.playlist_token)
    assertThat(loadedPlaylistInfo.playlist_size).isEqualTo(playlistInfo.playlist_size)
    val loadedPlaylistEntry = loadedItems.getItems<PlaylistEntry>().single()
    assertThat(loadedPlaylistEntry.playlist_token).isEqualTo(playlistEntry.playlist_token)
    assertThat(loadedPlaylistEntry.album_track_token).isEqualTo(playlistEntry.album_track_token)
  }

  @Test
  fun conditionalUpdateFailureInTransactionWrite() {
    val previousPlaylistInfo = PlaylistInfo("L_1", "WFH Music", 0)
    musicDb.playlists.info.save(previousPlaylistInfo)
    val albumTrack = AlbumTrack("M_1", "T_1", "dreamin'", Duration.parse("PT3M28S"))
    musicDb.albums.tracks.save(albumTrack)

    val playlistEntry = PlaylistEntry("L_1", "M_1:T_1")
    // Add a PlaylistEntry and update PlaylistInfo, in an ACID manner using transactionWrite.
    val playlistInfo = previousPlaylistInfo.copy(playlist_size = 1)
    val writeTransaction = TransactionWriteSet.Builder()
        .save(playlistInfo, DynamoDBTransactionWriteExpression()
            .withConditionExpression("playlist_size = :playlist_size")
            .withExpressionAttributeValues(mapOf(
                ":playlist_size" to AttributeValue().withN("${previousPlaylistInfo.playlist_size}")
            )))
        .save(playlistEntry)
        .build()
    // Introduce a race condition.
    val racingPlaylistInfo = previousPlaylistInfo.copy(playlist_size = 1)
    musicDb.playlists.info.save(racingPlaylistInfo)

    assertThatIllegalStateException()
        .isThrownBy {
          musicDb.transactionWrite(writeTransaction)
        }
        .withCauseExactlyInstanceOf(TransactionCanceledException::class.java)
  }

  @Test
  fun conditionCheckInTransactionWrite() {
    val previousPlaylistInfo = PlaylistInfo("L_1", "WFH Music", 0)
    musicDb.playlists.info.save(previousPlaylistInfo)
    val albumTrack = AlbumTrack("M_1", "T_1", "dreamin'", Duration.parse("PT3M28S"))
    musicDb.albums.tracks.save(albumTrack)

    val albumTrackKey = AlbumTrackKey("M_1", "T_1")
    val playlistEntry = PlaylistEntry("L_1", "M_1:T_1")
    val playlistInfo = previousPlaylistInfo.copy(playlist_size = 1)
    val writeTransaction = TransactionWriteSet.Builder()
        .save(playlistInfo, DynamoDBTransactionWriteExpression()
            .withConditionExpression("playlist_size = :playlist_size")
            .withExpressionAttributeValues(mapOf(
                ":playlist_size" to AttributeValue().withN("${previousPlaylistInfo.playlist_size}")
            )))
        .save(playlistEntry)
        // Add a PlaylistEntry only if the AlbumTrack exists.
        .checkCondition(albumTrackKey, DynamoDBTransactionWriteExpression()
            .withConditionExpression("attribute_exists(track_name)"))
        .build()
    musicDb.transactionWrite(writeTransaction)

    val loadedItems =
        musicDb.transactionLoad(PlaylistInfoKey("L_1"), PlaylistEntryKey("L_1", "M_1:T_1"))
    val loadedPlaylistInfo = loadedItems.getItems<PlaylistInfo>().single()
    assertThat(loadedPlaylistInfo.playlist_token).isEqualTo(playlistInfo.playlist_token)
    assertThat(loadedPlaylistInfo.playlist_size).isEqualTo(playlistInfo.playlist_size)
    val loadedPlaylistEntry = loadedItems.getItems<PlaylistEntry>().single()
    assertThat(loadedPlaylistEntry.playlist_token).isEqualTo(playlistEntry.playlist_token)
    assertThat(loadedPlaylistEntry.album_track_token).isEqualTo(playlistEntry.album_track_token)
  }

  @Test
  fun conditionCheckFailureInTransactionWrite() {
    val previousPlaylistInfo = PlaylistInfo("L_1", "WFH Music", 0)
    musicDb.playlists.info.save(previousPlaylistInfo)

    val albumTrackKey = AlbumTrackKey("M_1", "T_1")
    val playlistEntry = PlaylistEntry("L_1", "M_1:T_1")
    val playlistInfo = previousPlaylistInfo.copy(playlist_size = 1)
    val writeTransaction = TransactionWriteSet.Builder()
        .save(playlistInfo, DynamoDBTransactionWriteExpression()
            .withConditionExpression("playlist_size = :playlist_size")
            .withExpressionAttributeValues(mapOf(
                ":playlist_size" to AttributeValue().withN("${previousPlaylistInfo.playlist_size}")
            )))
        .save(playlistEntry)
        // Add a PlaylistEntry only if the AlbumTrack exists.
        .checkCondition(albumTrackKey, DynamoDBTransactionWriteExpression()
            .withConditionExpression("attribute_exists(track_name)"))
        .build()

    assertThatIllegalStateException()
        .isThrownBy {
          musicDb.transactionWrite(writeTransaction)
        }
        .withCauseExactlyInstanceOf(TransactionCanceledException::class.java)
  }
}
