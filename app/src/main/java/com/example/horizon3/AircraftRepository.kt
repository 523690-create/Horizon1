package com.example.horizon3

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.math.*

@Serializable
data class OpenSkyResponse(
    val time: Long,
    val states: List<JsonArray>? = null
)

class AircraftRepository {
    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 15000
            connectTimeoutMillis = 10000
        }
    }

    // Enrichment Data Maps
    private val airlineMap = mutableMapOf<String, String>() // ICAO -> Name
    private val aircraftMap = mutableMapOf<String, String>() // ICAO Type -> Name
    // For routes, we'll store a simple string for now if we can find a match
    private val routeMap = mutableMapOf<String, String>() // Airline+Dest? This is tricky without a unique key

    init {
        // Pre-populate with some common ones in case download fails or info is missing
        airlineMap.putAll(mapOf(
            "AAL" to "American Airlines", "BAW" to "British Airways", "DAL" to "Delta Air Lines",
            "DLH" to "Lufthansa", "FDX" to "FedEx", "JBU" to "JetBlue Airways",
            "KLM" to "KLM Royal Dutch", "SWA" to "Southwest Airlines", "UAL" to "United Airlines",
            "UPS" to "United Parcel Service", "AFR" to "Air France", "RYR" to "Ryanair",
            "EZY" to "easyJet", "WZZ" to "Wizz Air", "THY" to "Turkish Airlines",
            "SIA" to "Singapore Airlines", "QFA" to "Qantas", "UAE" to "Emirates",
            "ETD" to "Etihad Airways", "ACA" to "Air Canada", "ASA" to "Alaska Airlines",
            "NKS" to "Spirit Airlines", "FFT" to "Frontier Airlines", "SKW" to "SkyWest Airlines",
            "ENY" to "Envoy Air", "PDT" to "Piedmont Airlines", "EDV" to "Endeavor Air",
            "ASH" to "Mesa Airlines", "GJS" to "GoJet Airlines", "RPA" to "Republic Airways",
            "CPZ" to "Compass Airlines", "QXE" to "Horizon Air", "VOO" to "Volaris",
            "AMX" to "Aeroméxico", "AZA" to "Alitalia", "ANA" to "All Nippon Airways",
            "CAL" to "China Airlines", "CSA" to "Czech Airlines", "FIN" to "Finnair",
            "HAL" to "Hawaiian Airlines", "JAL" to "Japan Airlines", "KAL" to "Korean Air",

            "AAY" to "Allegiant Air",
            "ABX" to "ABX Air",
            "ASQ" to "Atlantic Southeast Airlines",
            "BTQ" to "Boutique Air",
            "CFS" to "Calstar Air Medical",
            "CJC" to "Colgan Air",
            "CXA" to "Xtra Airways",
            "FDY" to "Southern Airways Express",
            "G4T" to "GlobalX Airlines",
            "GIA" to "Global Air Transport",
            "GPD" to "Gulf and Caribbean Cargo",
            "JIA" to "PSA Airlines",
            "KFS" to "Kalitta Charters",
            "KLC" to "Kalitta Air",
            "LYM" to "Key Lime Air",
            "MXY" to "Breeze Airways",
            "NSH" to "North Star Air",
            "QMX" to "Quest Diagnostics Air",
            "ROU" to "Air Canada Rouge",
            "SWQ" to "Swift Air",
            "TSC" to "Air Transat",
            "WSW" to "WestJet",
            "WJA" to "WestJet Airlines",
            "WSX" to "WestJet Encore",
            "VOI" to "Volaris",
            "VIV" to "VivaAerobus",

            "AEE" to "Aegean Airlines",
            "AFL" to "Aeroflot",
            "AFR" to "Air France",
            "ALC" to "Air Leisure",
            "ANE" to "Air Nostrum",
            "AZE" to "Arcus Air",
            "BEL" to "Brussels Airlines",
            "BER" to "Air Berlin (historic but still appears in DBs)",
            "BMS" to "Blue Air",
            "BTI" to "Air Baltic",
            "CFG" to "Condor",
            "CFE" to "BA CityFlyer",
            "CSZ" to "Shenzhen Airlines (EU codeshares)",
            "CYP" to "Cyprus Airways",
            "DTR" to "DAT Danish Air Transport",
            "EIN" to "Aer Lingus",
            "ELL" to "Estonian Air",
            "ELY" to "El Al Israel Airlines",
            "ENT" to "Enter Air",
            "EXS" to "Jet2",
            "FIN" to "Finnair",
            "FPO" to "ASL Airlines France",
            "GMI" to "Germania",
            "GWI" to "Germanwings",
            "HOP" to "HOP! Air France",
            "IBE" to "Iberia",
            "IBS" to "Iberia Express",
            "ISS" to "Meridian Air",
            "JAT" to "Air Serbia",
            "JTG" to "Jet Time",
            "KZR" to "Air Astana",
            "LOT" to "LOT Polish Airlines",
            "MSR" to "EgyptAir",
            "NAX" to "Norwegian Air Shuttle",
            "NSZ" to "Neos",
            "OKW" to "Smartwings",
            "OSL" to "Austrian Airlines",
            "PEG" to "Pegasus Airlines",
            "QTR" to "Qatar Airways",
            "RAM" to "Royal Air Maroc",
            "ROT" to "TAROM",
            "SAS" to "Scandinavian Airlines",
            "SBI" to "S7 Airlines",
            "SDM" to "Rossiya Airlines",
            "SVR" to "Ural Airlines",
            "TAP" to "TAP Air Portugal",
            "TFL" to "TUI fly Netherlands",
            "THA" to "Thai Airways",
            "TOM" to "TUI Airways",
            "TRA" to "Transavia",
            "TUI" to "TUI fly Belgium",
            "UAE" to "Emirates",
            "UAV" to "Ukrainian Aviation",
            "VLG" to "Vueling",
            "WIF" to "Widerøe",
            "WUK" to "Wizz Air UK",

            "AAR" to "Asiana Airlines",
            "AHK" to "Air Hong Kong",
            "ALK" to "SriLankan Airlines",
            "AMU" to "Air Macau",
            "APG" to "Air People’s Republic of China",
            "AXM" to "AirAsia",
            "AZM" to "Air Manas",
            "BAV" to "Beijing Capital Airlines",
            "CCA" to "Air China",
            "CES" to "China Eastern Airlines",
            "CSH" to "Shanghai Airlines",
            "CSN" to "China Southern Airlines",
            "CXA" to "Cathay Dragon",
            "CPA" to "Cathay Pacific",
            "CRK" to "Hong Kong Airlines",
            "ETD" to "Etihad Airways",
            "FJI" to "Fiji Airways",
            "GCR" to "Garuda Indonesia",
            "HDA" to "Cathay Dragon (historic)",
            "HVN" to "Vietnam Airlines",
            "JSA" to "Jetstar Asia",
            "KAL" to "Korean Air",
            "KZR" to "Air Astana",
            "LNI" to "Lion Air",
            "MAS" to "Malaysia Airlines",
            "PAL" to "Philippine Airlines",
            "QFA" to "Qantas",
            "RXA" to "Regional Express",
            "SIA" to "Singapore Airlines",
            "SLM" to "Surinam Airways",
            "TGW" to "Scoot",
            "THY" to "Turkish Airlines",
            "UAE" to "Emirates",
            "VJC" to "VietJet Air",
            "VNA" to "Vietnam Airlines",

            "ACA" to "Air Côte d’Ivoire",
            "AHY" to "Azerbaijan Airlines",
            "ANV" to "Air Niamey",
            "CAA" to "Air Charter Africa",
            "DAH" to "Air Algérie",
            "ETR" to "Ethiopian Airlines",
            "FNA" to "Fastjet",
            "JUB" to "Jubba Airways",
            "KQA" to "Kenya Airways",
            "MSR" to "EgyptAir",
            "RWD" to "RwandAir",
            "SAW" to "Safair",
            "SEY" to "Air Seychelles",
            "SMK" to "Somon Air",
            "TAZ" to "Tanzania Air",

            "ABL" to "Air Arabia",
            "AXM" to "AirAsia X (operates in region)",
            "FAD" to "Flyadeal",
            "FDB" to "Flydubai",
            "GFA" to "Gulf Air",
            "IAW" to "Iraqi Airways",
            "IRN" to "Iran Air",
            "IZM" to "Israir",
            "JZR" to "Jazeera Airways",
            "MEA" to "Middle East Airlines",
            "OMA" to "Oman Air",
            "QTR" to "Qatar Airways",
            "RJA" to "Royal Jordanian",
            "SYR" to "Syrian Air",

            "ARG" to "Aerolíneas Argentinas",
            "AVA" to "Avianca",
            "AZU" to "Azul Brazilian Airlines",
            "GLO" to "Gol Transportes Aéreos",
            "LAN" to "LATAM Airlines",
            "LPE" to "LATAM Peru",
            "ONE" to "Avianca Ecuador",
            "PTB" to "Porter Brazil",
            "TAM" to "TAM Airlines",

            "5Y" to "Atlas Air",
            "ABX" to "ABX Air",
            "ACD" to "Air Cargo Germany",
            "ASL" to "ASL Airlines",
            "BOX" to "AeroLogic",
            "CLX" to "Cargolux",
            "CMP" to "Copa Cargo",
            "FDX" to "FedEx",
            "FXT" to "Flexjet",
            "KYE" to "Sky Lease Cargo",
            "MAS" to "MASkargo",
            "MPH" to "Martinair Cargo",
            "NCA" to "Nippon Cargo Airlines",
            "PAC" to "Polar Air Cargo",
            "QAF" to "Qatar Cargo",
            "UPS" to "United Parcel Service",
            "VRC" to "Volga-Dnepr Airlines",


            ))
        aircraftMap.putAll(mapOf(

            "A318" to "Airbus A318",
            "A319" to "Airbus A319",
            "A320" to "Airbus A320",
            "A20N" to "Airbus A320neo",
            "A321" to "Airbus A321",
            "A21N" to "Airbus A321neo",
            "A332" to "Airbus A330-200",
            "A333" to "Airbus A330-300",
            "A338" to "Airbus A330-800neo",
            "A339" to "Airbus A330-900neo",
            "A342" to "Airbus A340-200",
            "A343" to "Airbus A340-300",
            "A345" to "Airbus A340-500",
            "A346" to "Airbus A340-600",
            "A359" to "Airbus A350-900",
            "A35K" to "Airbus A350-1000",
            "A388" to "Airbus A380-800",
            "BCS1" to "Airbus A220-100",
            "BCS3" to "Airbus A220-300",

            "B712" to "Boeing 717-200",
            "B732" to "Boeing 737-200",
            "B733" to "Boeing 737-300",
            "B734" to "Boeing 737-400",
            "B735" to "Boeing 737-500",
            "B736" to "Boeing 737-600",
            "B737" to "Boeing 737-700",
            "B738" to "Boeing 737-800",
            "B739" to "Boeing 737-900",
            "B38M" to "Boeing 737 MAX 8",
            "B39M" to "Boeing 737 MAX 9",
            "B3XM" to "Boeing 737 MAX (generic)",
            "B741" to "Boeing 747-100",
            "B742" to "Boeing 747-200",
            "B744" to "Boeing 747-400",
            "B748" to "Boeing 747-8",
            "B762" to "Boeing 767-200",
            "B763" to "Boeing 767-300",
            "B764" to "Boeing 767-400",
            "B772" to "Boeing 777-200",
            "B77L" to "Boeing 777-200LR",
            "B773" to "Boeing 777-300",
            "B77W" to "Boeing 777-300ER",
            "B788" to "Boeing 787-8",
            "B789" to "Boeing 787-9",
            "B78X" to "Boeing 787-10",

            "E170" to "Embraer 170",
            "E175" to "Embraer 175",
            "E190" to "Embraer 190",
            "E195" to "Embraer 195",
            "E290" to "Embraer E190-E2",
            "E295" to "Embraer E195-E2",
            "ERJ1" to "Embraer ERJ-135",
            "ERJ2" to "Embraer ERJ-140",
            "E145" to "Embraer ERJ-145",

            "CRJ1" to "Bombardier CRJ-100",
            "CRJ2" to "Bombardier CRJ-200",
            "CRJ7" to "Bombardier CRJ-700",
            "CRJ9" to "Bombardier CRJ-900",
            "CRJX" to "Bombardier CRJ-1000",
            "DH8A" to "De Havilland Dash 8-100",
            "DH8B" to "De Havilland Dash 8-200",
            "DH8C" to "De Havilland Dash 8-300",
            "DH8D" to "De Havilland Dash 8-400",
            "AT45" to "ATR 42-500",
            "AT46" to "ATR 42-600",
            "AT72" to "ATR 72-500",
            "AT76" to "ATR 72-600",

            "AN12" to "Antonov An-12",
            "AN24" to "Antonov An-24",
            "AN26" to "Antonov An-26",
            "AN30" to "Antonov An-30",
            "AN32" to "Antonov An-32",
            "AN72" to "Antonov An-72",
            "AN74" to "Antonov An-74",
            "AN124" to "Antonov An-124 Ruslan",
            "AN225" to "Antonov An-225 Mriya",
            "C130" to "Lockheed C-130 Hercules",
            "C17" to "Boeing C-17 Globemaster III",
            "MD11" to "McDonnell Douglas MD-11",
            "MD11F" to "McDonnell Douglas MD-11F",
            "MD10" to "McDonnell Douglas MD-10",
            "DC10" to "McDonnell Douglas DC-10",
            "DC10F" to "McDonnell Douglas DC-10F",

            "GLF2" to "Gulfstream II",
            "GLF3" to "Gulfstream III",
            "GLF4" to "Gulfstream IV",
            "GLF5" to "Gulfstream V",
            "GLF6" to "Gulfstream G650/G600",
            "G280" to "Gulfstream G280",
            "G550" to "Gulfstream G550",
            "G650" to "Gulfstream G650",
            "GLEX" to "Bombardier Global Express",
            "CL30" to "Challenger 300",
            "CL35" to "Challenger 350",
            "CL60" to "Challenger 600",
            "FA50" to "Dassault Falcon 50",
            "FA7X" to "Dassault Falcon 7X",
            "FA8X" to "Dassault Falcon 8X",
            "C25A" to "Cessna Citation CJ2",
            "C25B" to "Cessna Citation CJ3",
            "C25C" to "Cessna Citation CJ4",
            "C510" to "Cessna Citation Mustang",
            "C525" to "Cessna CitationJet",
            "C550" to "Cessna Citation II",
            "C560" to "Cessna Citation V",
            "C680" to "Cessna Citation Sovereign",
            "C700" to "Cessna Citation Longitude",
            "PC12" to "Pilatus PC-12",
            "PC24" to "Pilatus PC-24",

            "C150" to "Cessna 150",
            "C152" to "Cessna 152",
            "C172" to "Cessna 172",
            "C182" to "Cessna 182 Skylane",
            "C206" to "Cessna 206 Stationair",
            "C208" to "Cessna 208 Caravan",
            "C210" to "Cessna 210 Centurion",
            "BE20" to "Beechcraft King Air 200",
            "BE30" to "Beechcraft Super King Air 300",
            "BE40" to "Beechjet 400",
            "BE58" to "Beechcraft Baron 58",
            "SR20" to "Cirrus SR20",
            "SR22" to "Cirrus SR22",
            "PA28" to "Piper PA-28 Cherokee",
            "PA32" to "Piper PA-32 Saratoga",
            "PA34" to "Piper PA-34 Seneca",
            "PA46" to "Piper Malibu/Mirage",
            "DA40" to "Diamond DA40",
            "DA42" to "Diamond DA42 Twin Star",
            "DA62" to "Diamond DA62",

            "T38" to "Northrop T-38 Talon",
            "T6" to "Beechcraft T-6 Texan II",
            "F16" to "General Dynamics F-16 Fighting Falcon",
            "F18" to "Boeing F/A-18 Hornet",
            "F35" to "Lockheed Martin F-35 Lightning II",
            "F15" to "McDonnell Douglas F-15 Eagle",
            "A10" to "Fairchild Republic A-10 Thunderbolt II",
            "KC10" to "McDonnell Douglas KC-10 Extender",
            "KC46" to "Boeing KC-46 Pegasus",
            "KC135" to "Boeing KC-135 Stratotanker",
            "P3" to "Lockheed P-3 Orion",
            "P8" to "Boeing P-8 Poseidon",

            "H125" to "Airbus H125 (AS350)",
            "H130" to "Airbus H130",
            "H160" to "Airbus H160",
            "EC20" to "Eurocopter EC120",
            "EC30" to "Eurocopter EC130",
            "EC35" to "Eurocopter EC135",
            "EC45" to "Eurocopter EC145",
            "AS50" to "Aérospatiale AS350",
            "AS55" to "Aérospatiale AS355 TwinStar",
            "B06" to "Bell 206 JetRanger",
            "B407" to "Bell 407",
            "B412" to "Bell 412",
            "B429" to "Bell 429",
            "B505" to "Bell 505 Jet Ranger X",
            "S70" to "Sikorsky UH-60 Black Hawk",
            "S76" to "Sikorsky S-76",
            "S92" to "Sikorsky S-92",
            "A109" to "AgustaWestland AW109",
            "A119" to "AgustaWestland AW119",
            "A139" to "AgustaWestland AW139",
            "A169" to "AgustaWestland AW169",
            "A189" to "AgustaWestland AW189",
            "R22" to "Robinson R22",
            "R44" to "Robinson R44",
            "R66" to "Robinson R66",


        ))
    }

    suspend fun checkConnectivity(): Boolean {
        return try {
            val response = client.get("https://raw.githubusercontent.com/jpatokal/openflights/master/data/airlines.dat")
            android.util.Log.d("AircraftRepo", "Connectivity check status: ${response.status}")
            true
        } catch (e: Exception) {
            android.util.Log.e("AircraftRepo", "Connectivity check failed: ${e.message}")
            false
        }
    }

    suspend fun fetchEnrichmentData() {
        android.util.Log.d("AircraftRepo", "Starting data enrichment download...")
        
        // Robust CSV Parser Regex (handles commas inside quotes)
        val csvRegex = Regex(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")

        // 1. Airlines (OpenFlights)
        try {
            val airlines = client.get("https://raw.githubusercontent.com/jpatokal/openflights/master/data/airlines.dat").bodyAsText()
            airlines.lineSequence().forEach { line ->
                val parts = line.split(csvRegex)
                if (parts.size >= 5) {
                    val name = parts[1].replace("\"", "").trim()
                    val icao = parts[4].replace("\"", "").trim()
                    // Filter out \N (null) and invalid entries
                    if (icao.length == 3 && icao != "\\N") {
                        airlineMap[icao] = name
                    }
                }
            }
            android.util.Log.d("AircraftRepo", "Loaded ${airlineMap.size} airlines")
        } catch (e: Exception) { android.util.Log.e("AircraftRepo", "Airlines fetch failed: ${e.message}") }

        // 2. Aircraft (OpenFlights Planes)
        try {
            val planes = client.get("https://raw.githubusercontent.com/jpatokal/openflights/master/data/planes.dat").bodyAsText()
            planes.lineSequence().forEach { line ->
                val parts = line.split(csvRegex)
                if (parts.size >= 3) {
                    val name = parts[0].replace("\"", "").trim()
                    val icao = parts[2].replace("\"", "").trim()
                    if (icao.isNotEmpty() && icao != "\\N") {
                        aircraftMap[icao] = name
                    }
                }
            }
            android.util.Log.d("AircraftRepo", "Loaded ${aircraftMap.size} aircraft types from OpenFlights")
        } catch (e: Exception) { android.util.Log.e("AircraftRepo", "Planes fetch failed: ${e.message}") }

        // 3. Aircraft (Slumbering001 Comprehensive)
        try {
            val icaoCodes = client.get("https://raw.githubusercontent.com/Slumbering001/Aircraft-Type-Codes/master/icao_type_codes.csv").bodyAsText()
            icaoCodes.lineSequence().forEach { line ->
                val parts = line.split(",")
                if (parts.size >= 3) {
                    val icao = parts[0].replace("\"", "").trim()
                    val model = parts[1].replace("\"", "").trim()
                    val manufacturer = parts[2].replace("\"", "").trim()
                    if (icao.isNotEmpty() && icao != "Designator") {
                        aircraftMap[icao] = "$manufacturer $model"
                    }
                }
            }
            android.util.Log.d("AircraftRepo", "Loaded ${aircraftMap.size} aircraft types after Slumbering001")
        } catch (e: Exception) { android.util.Log.e("AircraftRepo", "ICAO Type Codes fetch failed: ${e.message}") }

        // 4. Aircraft (OpenTravelData fallback)
        try {
            val aircraft = client.get("https://raw.githubusercontent.com/opentraveldata/opentraveldata/master/opentraveldata/optd_aircraft.csv").bodyAsText()
            aircraft.lineSequence().forEach { line ->
                // Format: iata_type^icao_type^name^category
                val parts = line.split("^")
                if (parts.size >= 3) {
                    val icao = parts[1].trim()
                    val name = parts[2].trim()
                    if (icao.isNotEmpty() && name.isNotEmpty() && icao != "icao_type") {
                        if (!aircraftMap.containsKey(icao)) {
                            aircraftMap[icao] = name
                        }
                    }
                }
            }
            android.util.Log.d("AircraftRepo", "Total aircraft types loaded: ${aircraftMap.size}")
        } catch (e: Exception) { android.util.Log.e("AircraftRepo", "Aircraft CSV fetch failed: ${e.message}") }
    }

    fun getAirlineName(callsign: String): String? {
        val trimmed = callsign.trim().uppercase()
        if (trimmed.length < 3) return null
        
        // Try 3-letter ICAO first
        val icao3 = trimmed.substring(0, 3)
        airlineMap[icao3]?.let { return it }
        
        // Some regional/smaller ones might use different prefixes, but ICAO is standard for callsigns
        return null
    }

    fun getAircraftName(type: String): String? {
        if (type.isEmpty()) return null
        return aircraftMap[type.uppercase().trim()]
    }

    suspend fun fetchOpenSky(lat: Double, lon: Double, radiusKm: Float): List<AircraftData> {
        val deltaLat = radiusKm / 111.0
        val deltaLon = radiusKm / (111.0 * cos(Math.toRadians(lat)))
        
        val url = "https://opensky-network.org/api/states/all"
        return try {
            val response: OpenSkyResponse = client.get(url) {
                parameter("lamin", lat - deltaLat)
                parameter("lomin", lon - deltaLon)
                parameter("lamax", lat + deltaLat)
                parameter("lomax", lon + deltaLon)
            }.body()

            response.states?.mapNotNull { state ->
                try {
                    val aLat = state[6].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
                    val aLon = state[5].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
                    val dist = calculateDistance(lat, lon, aLat, aLon)
                    val bearing = calculateBearing(lat, lon, aLat, aLon)
                    
                    AircraftData(
                        callsign = state[1].jsonPrimitive.content.trim().ifEmpty { "Unknown" },
                        lat = aLat,
                        lon = aLon,
                        altitudeM = state[7].jsonPrimitive.floatOrNull ?: 0f,
                        speedKts = (state[9].jsonPrimitive.floatOrNull ?: 0f) * 1.94384f,
                        heading = state[10].jsonPrimitive.floatOrNull ?: 0f,
                        distanceKm = dist.toFloat(),
                        bearingDegrees = bearing.toFloat(),
                        tailNumber = "", 
                        aircraftType = "",
                        icao24 = state[0].jsonPrimitive.content.trim().lowercase()
                    )
                } catch (e: Exception) { null }
            } ?: emptyList()
        } catch (e: Exception) {
            android.util.Log.e("AircraftRepo", "OpenSky Error: ${e.message}")
            emptyList()
        }
    }

    suspend fun fetchAirplanesLive(lat: Double, lon: Double, radiusKm: Float): List<AircraftData> {
        val radiusNm = radiusKm * 0.539957f
        val url = "https://api.airplanes.live/v2/point/$lat/$lon/${radiusNm.toInt()}"
        
        @Serializable
        data class AliveAircraft(
            val hex: String? = null,
            val flight: String? = null,
            val r: String? = null, 
            val t: String? = null, 
            val lat: Double? = null,
            val lon: Double? = null,
            val alt_baro: JsonElement? = null,
            val gs: Float? = null,
            val track: Float? = null,
            val dist: Float? = null,
            val nav_q_orig: String? = null,
            val nav_q_dest: String? = null,
            val orig: String? = null,
            val dest: String? = null,
            val route: String? = null
        )
        
        @Serializable
        data class AliveResponse(val ac: List<AliveAircraft>? = null)

        return try {
            val response: AliveResponse = client.get(url).body()
            response.ac?.mapNotNull { ac ->
                val aLat = ac.lat ?: return@mapNotNull null
                val aLon = ac.lon ?: return@mapNotNull null
                val altM = if (ac.alt_baro is JsonPrimitive) {
                    ac.alt_baro.floatOrNull?.let { it * 0.3048f } ?: 0f
                } else 0f

                val dist = calculateDistance(lat, lon, aLat, aLon)
                val bearing = calculateBearing(lat, lon, aLat, aLon)
                
                // Parse route if available as "ORIG-DEST"
                var finalOrig = ac.orig ?: ac.nav_q_orig ?: ""
                var finalDest = ac.dest ?: ac.nav_q_dest ?: ""
                
                if (finalOrig.isEmpty() && ac.route != null) {
                    val parts = ac.route.split("-")
                    if (parts.size >= 2) {
                        finalOrig = parts[0].trim()
                        finalDest = parts[1].trim()
                    }
                }

                AircraftData(
                    callsign = ac.flight?.trim()?.ifEmpty { "Unknown" } ?: "Unknown",
                    lat = aLat,
                    lon = aLon,
                    altitudeM = altM,
                    speedKts = ac.gs ?: 0f,
                    heading = ac.track ?: 0f,
                    distanceKm = dist.toFloat(),
                    bearingDegrees = bearing.toFloat(),
                    tailNumber = ac.r ?: "",
                    aircraftType = ac.t ?: "",
                    origin = finalOrig,
                    destination = finalDest,
                    icao24 = ac.hex?.lowercase() ?: ""
                )
            } ?: emptyList()
        } catch (e: Exception) {
            android.util.Log.e("AircraftRepo", "Airplanes.Live Error: ${e.message}")
            emptyList()
        }
    }

    suspend fun fetchAdsbExchange(lat: Double, lon: Double, radiusKm: Float): List<AircraftData> {
        val radiusNm = radiusKm * 0.539957f
        val url = "https://adsb.fi/api/v2/point/$lat/$lon/${radiusNm.toInt()}"
        
        @Serializable
        data class AeAircraft(
            val hex: String? = null,
            val flight: String? = null,
            val r: String? = null, 
            val t: String? = null, 
            val lat: Double? = null,
            val lon: Double? = null,
            val alt_baro: JsonElement? = null,
            val gs: Float? = null,
            val track: Float? = null,
            val dist: Float? = null,
            val nav_q_orig: String? = null,
            val nav_q_dest: String? = null,
            val orig: String? = null,
            val dest: String? = null,
            val route: String? = null
        )
        
        @Serializable
        data class AeResponse(val ac: List<AeAircraft>? = null)

        return try {
            val response: AeResponse = client.get(url).body()
            response.ac?.mapNotNull { ac ->
                val aLat = ac.lat ?: return@mapNotNull null
                val aLon = ac.lon ?: return@mapNotNull null
                val altM = if (ac.alt_baro is JsonPrimitive) {
                    ac.alt_baro.floatOrNull?.let { it * 0.3048f } ?: 0f
                } else 0f

                val dist = calculateDistance(lat, lon, aLat, aLon)
                val bearing = calculateBearing(lat, lon, aLat, aLon)
                
                // Parse route if available as "ORIG-DEST"
                var finalOrig = ac.orig ?: ac.nav_q_orig ?: ""
                var finalDest = ac.dest ?: ac.nav_q_dest ?: ""
                
                if (finalOrig.isEmpty() && ac.route != null) {
                    val parts = ac.route.split("-")
                    if (parts.size >= 2) {
                        finalOrig = parts[0].trim()
                        finalDest = parts[1].trim()
                    }
                }

                AircraftData(
                    callsign = ac.flight?.trim()?.ifEmpty { "Unknown" } ?: "Unknown",
                    lat = aLat,
                    lon = aLon,
                    altitudeM = altM,
                    speedKts = ac.gs ?: 0f,
                    heading = ac.track ?: 0f,
                    distanceKm = dist.toFloat(),
                    bearingDegrees = bearing.toFloat(),
                    tailNumber = ac.r ?: "",
                    aircraftType = ac.t ?: "",
                    origin = finalOrig,
                    destination = finalDest,
                    icao24 = ac.hex?.lowercase() ?: ""
                )
            } ?: emptyList()
        } catch (e: Exception) {
            android.util.Log.e("AircraftRepo", "ADS-B Exchange Error: ${e.message}")
            emptyList()
        }
    }

    suspend fun fetchRoute(icao24: String): String? {
        if (icao24.isEmpty()) return null
        val url = "https://api.adsb.fi/api/v2/route/$icao24"
        return try {
            val response: JsonObject = client.get(url).body()
            response["route"]?.jsonPrimitive?.content?.ifEmpty { null }
        } catch (e: Exception) { null }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0 
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaLambda = Math.toRadians(lon2 - lon1)

        val y = sin(deltaLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)

        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }
}
