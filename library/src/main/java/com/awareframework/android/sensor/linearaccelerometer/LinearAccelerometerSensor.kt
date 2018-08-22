package com.awareframework.android.sensor.linearaccelerometer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.Sensor.TYPE_LINEAR_ACCELERATION
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import com.awareframework.android.core.AwareSensor
import com.awareframework.android.core.db.model.DbSyncConfig
import com.awareframework.android.core.model.SensorConfig
import com.awareframework.android.sensor.linearaccelerometer.model.LinearAccelerometerData
import com.awareframework.android.sensor.linearaccelerometer.model.LinearAccelerometerDevice

/**
 * AWARE Linear Accelerometer module
 * - Linear Accelerometer raw data
 * - Linear Accelerometer sensor information
 *
 * @author  sercant
 * @date 22/08/2018
 */
class LinearAccelerometerSensor : AwareSensor(), SensorEventListener {

    companion object {
        const val TAG = "AWARE::LinearAcc"

        const val ACTION_AWARE_LINEAR_ACCELEROMETER = "ACTION_AWARE_LINEAR_ACCELEROMETER"

        const val ACTION_AWARE_LINEAR_ACCELEROMETER_START = "com.awareframework.android.sensor.linearaccelerometer.SENSOR_START"
        const val ACTION_AWARE_LINEAR_ACCELEROMETER_STOP = "com.awareframework.android.sensor.linearaccelerometer.SENSOR_STOP"

        const val ACTION_AWARE_LINEAR_ACCELEROMETER_SET_LABEL = "com.awareframework.android.sensor.linearaccelerometer.ACTION_AWARE_LINEAR_ACCELEROMETER_SET_LABEL"
        const val EXTRA_LABEL = "label"

        const val ACTION_AWARE_LINEAR_ACCELEROMETER_SYNC = "com.awareframework.android.sensor.linearaccelerometer.SENSOR_SYNC"

        val CONFIG = Config()

        var currentInterval: Int = 0
            private set

        fun start(context: Context, config: Config? = null) {
            if (config != null)
                CONFIG.replaceWith(config)
            context.startService(Intent(context, LinearAccelerometerSensor::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LinearAccelerometerSensor::class.java))
        }
    }

    private lateinit var mSensorManager: SensorManager
    private var mLinearAccelerometer: Sensor? = null
    private lateinit var sensorThread: HandlerThread
    private lateinit var sensorHandler: Handler

    private var lastSave = 0L

    private var lastValues = arrayOf(0f, 0f, 0f)
    private var lastTimestamp: Long = 0
    private var lastSavedAt: Long = 0

    private val dataBuffer = ArrayList<LinearAccelerometerData>()

    private var dataCount: Int = 0
    private var lastDataCountTimestamp: Long = 0

    private val linearAccelerometerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            when (intent.action) {
                ACTION_AWARE_LINEAR_ACCELEROMETER_SET_LABEL -> {
                    intent.getStringExtra(EXTRA_LABEL)?.let {
                        CONFIG.label = it
                    }
                }

                ACTION_AWARE_LINEAR_ACCELEROMETER_SYNC -> onSync(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        initializeDbEngine(CONFIG)

        mSensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        mLinearAccelerometer = mSensorManager.getDefaultSensor(TYPE_LINEAR_ACCELERATION)

        sensorThread = HandlerThread(TAG)
        sensorThread.start()

        sensorHandler = Handler(sensorThread.looper)

        registerReceiver(linearAccelerometerReceiver, IntentFilter().apply {
            addAction(ACTION_AWARE_LINEAR_ACCELEROMETER_SET_LABEL)
            addAction(ACTION_AWARE_LINEAR_ACCELEROMETER_SYNC)
        })

        logd("Linear accelerometer service created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        return if (mLinearAccelerometer != null) {
            saveSensorDevice(mLinearAccelerometer)

            val samplingFreqUs = if (CONFIG.interval > 0) 1000000 / CONFIG.interval else 0
            mSensorManager.registerListener(
                    this,
                    mLinearAccelerometer,
                    samplingFreqUs,
                    sensorHandler)

            lastSave = System.currentTimeMillis()

            logd("Linear accelerometer service active: ${CONFIG.interval} samples per second.")

            START_STICKY
        } else {
            logw("This device doesn't have a linear accelerometer sensor!")

            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        sensorHandler.removeCallbacksAndMessages(null)
        mSensorManager.unregisterListener(this, mLinearAccelerometer)
        sensorThread.quit()

        dbEngine?.close()

        unregisterReceiver(linearAccelerometerReceiver)

        logd("Linear accelerometer service terminated...")
    }

    private fun saveSensorDevice(sensor: Sensor?) {
        sensor ?: return

        val device = LinearAccelerometerDevice().apply {
            deviceId = CONFIG.deviceId
            timestamp = System.currentTimeMillis()

            maxRange = sensor.maximumRange
            minDelay = sensor.minDelay.toFloat()
            name = sensor.name
            power = sensor.power
            resolution = sensor.resolution
            type = sensor.type.toString()
            vendor = sensor.vendor
            version = sensor.version.toString()
        }

        dbEngine?.save(device, LinearAccelerometerDevice.TABLE_NAME, 0)

        logd("Linear accelerometer sensor info: $device")
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        //We log current accuracy on the sensor changed event
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return

        val currentTime = System.currentTimeMillis()

        if (currentTime - lastDataCountTimestamp >= 1000) {
            currentInterval = dataCount
            dataCount = 0
            lastDataCountTimestamp = currentTime
        }

        if (currentTime - lastTimestamp < (900.0 / CONFIG.interval)) {
            // skip this event
            return
        }
        lastTimestamp = currentTime

        if (CONFIG.threshold > 0 &&
                Math.abs(event.values[0] - lastValues[0]) < CONFIG.threshold &&
                Math.abs(event.values[1] - lastValues[1]) < CONFIG.threshold &&
                Math.abs(event.values[2] - lastValues[2]) < CONFIG.threshold) {
            return
        }

        lastValues.forEachIndexed { index, _ ->
            lastValues[index] = event.values[index]
        }

        val data = LinearAccelerometerData().apply {
            timestamp = currentTime
            deviceId = CONFIG.deviceId
            label = CONFIG.label

            x = event.values[0]
            y = event.values[1]
            z = event.values[2]

            accuracy = event.accuracy
            eventTimestamp = event.timestamp
        }

        CONFIG.sensorObserver?.onDataChanged(data)

        dataBuffer.add(data)
        dataCount++

        if (currentTime - lastSavedAt < CONFIG.period * 60000) { // convert minute to ms
            // not ready to save yet
            return
        }
        lastSavedAt = currentTime

        val dataBuffer = this.dataBuffer.toTypedArray()
        this.dataBuffer.clear()

        try {
            logd("Saving buffer to database.")
            dbEngine?.save(dataBuffer, LinearAccelerometerData.TABLE_NAME)

            sendBroadcast(Intent(ACTION_AWARE_LINEAR_ACCELEROMETER))
        } catch (e: Exception) {
            e.message ?: logw(e.message!!)
            e.printStackTrace()
        }
    }

    override fun onSync(intent: Intent?) {
        dbEngine?.startSync(LinearAccelerometerData.TABLE_NAME)
        dbEngine?.startSync(LinearAccelerometerDevice.TABLE_NAME, DbSyncConfig(removeAfterSync = false))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    interface Observer {
        fun onDataChanged(data: LinearAccelerometerData)
    }

    data class Config(
            /**
             * For real-time observation of the sensor data collection.
             */
            var sensorObserver: Observer? = null,

            /**
             * Linear accelerometer interval in hertz per second: e.g.
             *
             * 0 - fastest
             * 1 - sample per second
             * 5 - sample per second
             * 20 - sample per second
             */
            var interval: Int = 5,

            /**
             * Period to save data in minutes. (optional)
             */
            var period: Float = 1f,

            /**
             * Linear accelerometer threshold (float).  Do not record consecutive points if
             * change in value is less than the set value.
             */
            var threshold: Double = 0.0

            // TODO wakelock?

    ) : SensorConfig(dbPath = "aware_linear_accelerometer") {

        override fun <T : SensorConfig> replaceWith(config: T) {
            super.replaceWith(config)

            if (config is Config) {
                sensorObserver = config.sensorObserver
                interval = config.interval
                period = config.period
                threshold = config.threshold
            }
        }
    }

    class LinearAccelerometerSensorBroadcastReceiver : AwareSensor.SensorBroadcastReceiver() {

        override fun onReceive(context: Context?, intent: Intent?) {
            context ?: return

            logd("Sensor broadcast received. action: " + intent?.action)

            when (intent?.action) {
                SENSOR_START_ENABLED -> {
                    logd("Sensor enabled: " + CONFIG.enabled)

                    if (CONFIG.enabled) {
                        start(context)
                    }
                }

                ACTION_AWARE_LINEAR_ACCELEROMETER_STOP,
                SENSOR_STOP_ALL -> {
                    logd("Stopping sensor.")
                    stop(context)
                }

                ACTION_AWARE_LINEAR_ACCELEROMETER_START -> {
                    start(context)
                }
            }
        }
    }
}

private fun logd(text: String) {
    if (LinearAccelerometerSensor.CONFIG.debug) Log.d(LinearAccelerometerSensor.TAG, text)
}

private fun logw(text: String) {
    Log.w(LinearAccelerometerSensor.TAG, text)
}