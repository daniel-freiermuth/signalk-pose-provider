import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.signalk.companion.data.model.*

fun main() {
    // Create a sample SignalK message
    val locationData = LocationData(
        latitude = 59.3293,
        longitude = 18.0686,
        accuracy = 5.0f,
        bearing = 180.0f,
        speed = 5.0f,
        altitude = 10.0,
        timestamp = System.currentTimeMillis()
    )
    
    val source = SignalKSource(
        label = "SignalK Navigation Provider",
        src = "signalk-nav-provider"
    )
    
    val values = listOf(
        SignalKValue(
            path = "navigation.position",
            value = SignalKValues.position(locationData.latitude, locationData.longitude)
        ),
        SignalKValue(
            path = "navigation.speedOverGround",
            value = SignalKValues.number(locationData.speed.toDouble())
        ),
        SignalKValue(
            path = "navigation.gnss.type",
            value = SignalKValues.string("GPS")
        )
    )
    
    val update = SignalKUpdate(
        source = source,
        timestamp = "2025-08-07T12:34:56.789Z",
        values = values
    )
    
    val message = SignalKMessage(
        context = "vessels.self",
        updates = listOf(update)
    )
    
    val json = Json { prettyPrint = true }.encodeToString(message)
    println("Generated SignalK JSON:")
    println(json)
}
