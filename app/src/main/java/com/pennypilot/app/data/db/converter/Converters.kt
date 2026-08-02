package com.pennypilot.app.data.db.converter

import androidx.room.TypeConverter
import com.pennypilot.app.domain.model.TxSource
import com.pennypilot.app.domain.model.TxStatus
import com.pennypilot.app.domain.model.TxType

class Converters {
    @TypeConverter
    fun fromTxType(v: TxType) = v.name
    @TypeConverter
    fun toTxType(v: String) = TxType.valueOf(v)

    @TypeConverter
    fun fromTxSource(v: TxSource) = v.name
    @TypeConverter
    fun toTxSource(v: String) = TxSource.valueOf(v)

    @TypeConverter
    fun fromTxStatus(v: TxStatus) = v.name
    @TypeConverter
    fun toTxStatus(v: String) = TxStatus.valueOf(v)
}
