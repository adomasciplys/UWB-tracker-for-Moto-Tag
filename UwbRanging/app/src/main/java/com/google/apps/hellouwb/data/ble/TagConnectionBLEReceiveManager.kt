package com.google.apps.hellouwb.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import com.google.apps.hellouwb.data.ConnectionState
import com.google.apps.hellouwb.data.Resource
import com.google.apps.hellouwb.data.TagConnectionReceiveManager
import com.google.apps.hellouwb.data.TagConnectionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@SuppressLint("MissingPermission")
class TagConnectionBLEReceiveManager @Inject constructor(
    private val bluetoothAdapter: BluetoothAdapter,
    private val context: Context
) : TagConnectionReceiveManager{

    private val DEVICE_NAME = "Moto Moto"
    private val UWB_SERVICE_UUID = ""
    private val UWB_CHARACTERISTIC_UUID = ""

    override val data: MutableSharedFlow<Resource<TagConnectionResult>>
        get() = MutableSharedFlow()

    private val bleScanner by lazy {
        bluetoothAdapter.bluetoothLeScanner
    }

    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()

    private var gatt : BluetoothGatt? = null

    private var isScanning = false

    private  val coroutineScope = CoroutineScope(Dispatchers.Default)

    private val scanCallback = object : ScanCallback(){
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            Log.i("BLE DEVICES", "" + result.device.name)

            if(result.device.name == DEVICE_NAME){
                coroutineScope.launch {
                    data.emit(Resource.Loading(message = "Connecting to device..."))
                }
                if(isScanning){
                    result.device.connectGatt(context, false, gattCallBack)
                    isScanning = false
                    bleScanner.stopScan(this)
                }
            }

        }
    }

    private var currentConnectionAttempt = 1
    private var MAXIMUM_CONNECTION_ATTEMPTS = 1

    private val gattCallBack = object : BluetoothGattCallback(){

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if(status == BluetoothGatt.GATT_SUCCESS){
                if(newState == BluetoothProfile.STATE_CONNECTED){
                    coroutineScope.launch {
                        data.emit(Resource.Loading(message = "Discovering Services..."))
                    }
                    gatt.discoverServices()
                    this@TagConnectionBLEReceiveManager.gatt = gatt
                } else if(newState == BluetoothProfile.STATE_DISCONNECTED){
                    coroutineScope.launch {
                        data.emit(Resource.Success(data = TagConnectionResult(ConnectionState.Disconnected)))
                    }
                    gatt.close()
                }
            }else{
                gatt.close()
                currentConnectionAttempt += 1
                coroutineScope.launch {
                    data.emit(Resource.Loading(
                        message = "Attempting to connect $currentConnectionAttempt / $MAXIMUM_CONNECTION_ATTEMPTS "
                    ))
                }
                if(currentConnectionAttempt <= MAXIMUM_CONNECTION_ATTEMPTS){
                    startReceiving()
                }else{
                    coroutineScope.launch {
                        data.emit(Resource.Error(errorMessage = "Could not connect to ble device"))
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            with(gatt){
                printGattTable()
                coroutineScope.launch {
                    data.emit(Resource.Loading(message = "Adjusting MTU space..."))
                }
                gatt.requestMtu(517) //How many bytes we request from the peripheral device
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            val characteristic = findCharacteristics(UWB_SERVICE_UUID, UWB_CHARACTERISTIC_UUID)
            if(characteristic == null){
                coroutineScope.launch {
                    data.emit(Resource.Error(errorMessage = "Could not find UWB publisher"))
                }
                return
            }
            enableNotification(characteristic)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            with(characteristic){
                when(uuid){
                    UUID.fromString(UWB_CHARACTERISTIC_UUID) -> {
                       // BYTE CODE FROM CHARACTERISTIC IN SOM KIND OF FORMAT: XX XX XX XX
                        val tagConnectionResult = TagConnectionResult(
                            ConnectionState.Connected
                        )
                        coroutineScope.launch {
                            data.emit(Resource.Success(data = tagConnectionResult))
                        }
                    }
                    else -> Unit
                }

            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            //HERE YOU CAN READ CHARACTERISTICS
        }

    }

    private fun enableNotification(characteristic: BluetoothGattCharacteristic){
        val cccdUuid = UUID.fromString(CCCD_DESCRIPTOR_UUID)
        val payload = when {
            characteristic.isIndicatable() -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            characteristic.isNotifiable() -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else -> return
        }

        characteristic.getDescriptor(cccdUuid)?.let { cccdDescriptor ->
            if(gatt?.setCharacteristicNotification(characteristic, true) == false) {
                Log.d("BLEReceiveManager", "set characteristics notification failed")
                return
            }
            writeDescription(cccdDescriptor, payload)
        }
    }

    private fun writeDescription(descriptor: BluetoothGattDescriptor, payload : ByteArray){
        gatt?.let { gatt ->
            gatt.writeDescriptor(descriptor, payload)
        } ?: error("Not connected to a BLE device")
    }

    private fun findCharacteristics(serviceUUID : String, characteristicsUUID: String): BluetoothGattCharacteristic?{
        return gatt?.services?.find { service ->
            service.uuid.toString() == serviceUUID
        }?.characteristics?.find { characteristics ->
            characteristics.uuid.toString() == characteristicsUUID
        }
    }

    override fun startReceiving() {
        coroutineScope.launch {
            data.emit(Resource.Loading(message = "Scanning Ble devices..."))
        }

        isScanning = true
        bleScanner.startScan(null, scanSettings, scanCallback)
    }

    override fun reconnect() {
        gatt?.connect()
    }

    override fun disconnect() {
        gatt?.disconnect()
    }

    override fun closeConnection() {
        bleScanner.stopScan(scanCallback)
        val characteristic = findCharacteristics(UWB_SERVICE_UUID, UWB_CHARACTERISTIC_UUID)
        if(characteristic != null){
            disconnectCharacteristic(characteristic)
        }
        gatt?.close()
    }

    private fun disconnectCharacteristic(characteristic: BluetoothGattCharacteristic){
        val cccdUuid = UUID.fromString(CCCD_DESCRIPTOR_UUID)
        characteristic.getDescriptor(cccdUuid)?.let { cccdDescriptor ->
            if(gatt?.setCharacteristicNotification(characteristic, false) == false){
                Log.d("BLEReceiveManager", "set characteristics notification failed")
                return
            }
            writeDescription(cccdDescriptor, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
        }
    }

    private fun example(){
        val characteristic = gatt?.getService(UUID.fromString("serviceId"))?.getCharacteristic(UUID.fromString("characteristicUUID"))
        gatt?.readCharacteristic(characteristic)

    }
}