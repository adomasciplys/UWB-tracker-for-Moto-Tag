package com.google.apps.hellouwb.data

import kotlinx.coroutines.flow.MutableSharedFlow

interface TagConnectionReceiveManager {

    val data: MutableSharedFlow<Resource<TagConnectionResult>>

    fun reconnect()

    fun disconnect()

    fun startReceiving()

    fun closeConnection()


}