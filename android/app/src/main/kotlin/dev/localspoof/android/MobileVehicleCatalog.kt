package dev.localspoof.android

import dev.localspoof.domain.FuelGrade
import kotlinx.serialization.json.*

internal data class VehicleMenuItem(val text: String, val value: String)
internal data class MobileVehicleRecord(val id:Int,val year:Int,val make:String,val model:String,val combinedMpg:Double?,val cityMpg:Double?,val highwayMpg:Double?,val fuelGrade:FuelGrade?)
internal data class MobileFuelPrices(val regular:Double?,val midgrade:Double?,val premium:Double?,val diesel:Double?,val retrievedAtEpochMs:Long,val source:String="FuelEconomy.gov US reference prices",val currency:String="USD")

/** Optional US reference data, held in caller RAM. Tank size and starting fuel always require user input. */
internal class MobileVehicleCatalog(private val data:MobilePlanningData=MobilePlanningNetwork(),private val epochMs:()->Long=System::currentTimeMillis) {
    fun years(cancelled:()->Boolean={false})=menu(VehicleSelection(VehicleResource.YEAR),cancelled)
    fun makes(year:Int,cancelled:()->Boolean={false})=menu(VehicleSelection(VehicleResource.MAKE,year=year),cancelled)
    fun models(year:Int,make:String,cancelled:()->Boolean={false})=menu(VehicleSelection(VehicleResource.MODEL,year=year,make=make),cancelled)
    fun options(year:Int,make:String,model:String,cancelled:()->Boolean={false})=menu(VehicleSelection(VehicleResource.OPTIONS,year=year,make=make,model=model),cancelled)
    fun vehicle(id:Int,cancelled:()->Boolean={false}):MobileVehicleRecord { check(!cancelled()){ "PLANNING_CANCELLED" };return decodeVehicle(data.vehicle(VehicleSelection(VehicleResource.VEHICLE,id=id),cancelled),id).also { check(!cancelled()){ "PLANNING_CANCELLED" } } }
    fun prices(cancelled:()->Boolean={false}):MobileFuelPrices { check(!cancelled()){ "PLANNING_CANCELLED" };return decodePrices(data.vehicle(VehicleSelection(VehicleResource.PRICES),cancelled),epochMs()).also { check(!cancelled()){ "PLANNING_CANCELLED" } } }
    fun cancelRequests()=data.cancelRequests()
    fun close()=data.close()
    private fun menu(selection:VehicleSelection,cancelled:()->Boolean):List<VehicleMenuItem> { check(!cancelled()){ "PLANNING_CANCELLED" };return decodeMenu(data.vehicle(selection,cancelled)).also { check(!cancelled()){ "PLANNING_CANCELLED" } } }
    companion object {
        fun decodeMenu(bytes:ByteArray):List<VehicleMenuItem> {
            val root=parseBoundedJson(bytes,524288).jsonObject
            val raw=root["menuItem"] ?: return emptyList()
            val values=if(raw is JsonArray) raw.toList() else listOf(raw)
            require(values.size<=2000) { "VEHICLE_MENU_TOO_LARGE" }
            val result=values.map { item -> val obj=item.jsonObject;VehicleMenuItem(text(obj,"text",300),text(obj,"value",160)) }
            require(result.map { it.value }.distinct().size==result.size) { "DUPLICATE_VEHICLE_MENU_ITEM" }
            return result
        }
        fun decodeVehicle(bytes:ByteArray,expectedId:Int):MobileVehicleRecord {
            val root=parseBoundedJson(bytes,524288).jsonObject
            require(root["id"]?.jsonPrimitive?.intOrNull==expectedId) { "VEHICLE_ID_MISMATCH" }
            val year=root["year"]?.jsonPrimitive?.intOrNull ?: throw IllegalArgumentException("INVALID_VEHICLE_YEAR")
            require(year in 1984..2100)
            val rawFuel=(root["fuelType1"] ?: root["fuelType"])?.jsonPrimitive?.contentOrNull
            val grade=when(rawFuel) { "Regular Gasoline"->FuelGrade.REGULAR;"Midgrade Gasoline"->FuelGrade.MIDGRADE;"Premium Gasoline"->FuelGrade.PREMIUM;"Diesel"->FuelGrade.DIESEL;else->null }
            // Electric, mixed-fuel and absent specifications never acquire fabricated gasoline MPG.
            return MobileVehicleRecord(expectedId,year,text(root,"make",160),text(root,"model",160),if(grade!=null)positive(root,"comb08",500.0) else null,if(grade!=null)positive(root,"city08",500.0) else null,if(grade!=null)positive(root,"highway08",500.0) else null,grade)
        }
        fun decodePrices(bytes:ByteArray,now:Long):MobileFuelPrices {
            val root=parseBoundedJson(bytes,524288).jsonObject
            return MobileFuelPrices(positive(root,"regular",100.0),positive(root,"midgrade",100.0),positive(root,"premium",100.0),positive(root,"diesel",100.0),now)
        }
        private fun positive(obj:JsonObject,key:String,max:Double):Double? {
            val value=obj[key]?.jsonPrimitive?.doubleOrNull ?: return null
            return value.takeIf { it.isFinite() && it>0 && it<=max }
        }
        private fun text(obj:JsonObject,key:String,max:Int):String {
            val value=obj[key]?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("INVALID_VEHICLE_RESPONSE")
            require(value.isNotBlank() && value.length<=max && value.none { it.isISOControl() }) { "INVALID_VEHICLE_RESPONSE" };return value
        }
    }
}
