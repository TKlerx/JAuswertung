package de.df.jauswertung.zeitnahme.android.storage

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import de.df.jauswertung.zeitnahme.android.net.DeskOrigin
import java.util.UUID
import kotlinx.coroutines.flow.Flow

enum class EventKind { FINISH, STOPWATCH }

enum class DeliveryStatus { PENDING, ACCEPTED, REJECTED, BLOCKED }

@Entity(
    tableName = "outbox",
    indices = [Index(value = ["pairingGeneration", "attemptId", "lane"], unique = true)],
)
data class OutboxEvent(
    @PrimaryKey val id: UUID,
    val pairingGeneration: UUID,
    val deskOrigin: String,
    val attemptId: UUID,
    val lane: Int,
    val kind: EventKind,
    val timeMicros: Long,
    val clockEpoch: UUID? = null,
    val estimateId: UUID? = null,
    val status: DeliveryStatus = DeliveryStatus.PENDING,
    val sendAttempts: Int = 0,
    val reason: String? = null,
) {
    init {
        require(lane > 0)
        require(DeskOrigin.parse(deskOrigin).value == deskOrigin)
        when (kind) {
            EventKind.FINISH -> require(timeMicros >= 0 && clockEpoch != null && estimateId != null)
            EventKind.STOPWATCH -> require(timeMicros > 0 && clockEpoch == null && estimateId == null)
        }
    }
}

@Dao
abstract class TimingOutbox {
    @Insert
    protected abstract suspend fun insert(event: OutboxEvent)

    @Transaction
    open suspend fun enqueue(event: OutboxEvent) {
        require(event.status == DeliveryStatus.PENDING && event.sendAttempts == 0 && event.reason == null)
        insert(event)
    }

    @Query("SELECT * FROM outbox ORDER BY rowid")
    abstract fun observe(): Flow<List<OutboxEvent>>

    @Query("SELECT * FROM outbox WHERE status = 'PENDING' ORDER BY rowid")
    abstract suspend fun pending(): List<OutboxEvent>

    @Query(
        """
        UPDATE outbox SET status = :status, sendAttempts = sendAttempts + 1, reason = :reason
        WHERE id = :id AND status = 'PENDING'
        """,
    )
    abstract suspend fun recordSend(id: UUID, status: DeliveryStatus, reason: String?)

    @Query(
        """
        UPDATE outbox SET status = 'BLOCKED', reason = :reason
        WHERE pairingGeneration = :generation AND status = 'PENDING'
        """,
    )
    abstract suspend fun blockPairing(generation: UUID, reason: String)
}

@Database(entities = [OutboxEvent::class], version = 1)
abstract class TimingDatabase : RoomDatabase() {
    abstract fun outbox(): TimingOutbox

    companion object {
        fun open(context: Context): TimingDatabase =
            Room.databaseBuilder(context.applicationContext, TimingDatabase::class.java, "timing-outbox").build()
    }
}
