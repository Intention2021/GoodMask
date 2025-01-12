package com.intention.android.goodmask.fragment

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityCompat.finishAffinity
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.room.Room
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.gson.GsonBuilder
import com.intention.android.goodmask.R
import com.intention.android.goodmask.activity.BleService
import com.intention.android.goodmask.activity.DeviceActivity
import com.intention.android.goodmask.activity.SplashActivity
import com.intention.android.goodmask.databinding.FragHomeBinding
import com.intention.android.goodmask.db.MaskDB
import com.intention.android.goodmask.dustData.DustInfo
import com.intention.android.goodmask.model.MaskData
import com.intention.android.goodmask.stationData.StationInfo
import com.intention.android.goodmask.viewmodel.BleViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.locationtech.proj4j.BasicCoordinateTransform
import org.locationtech.proj4j.CRSFactory
import org.locationtech.proj4j.ProjCoordinate
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.text.SimpleDateFormat
import java.util.*


class HomeFragment : Fragment() {

    private val viewModel by viewModel<BleViewModel>()
    private var device : BluetoothDevice? = null
    private lateinit var binding : FragHomeBinding
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    lateinit var maskFanPower: SeekBar
    lateinit var maskFanPowerText: TextView
    lateinit var disConBtn : Button
    var addressList: List<String> = listOf("서울시", "중구", "명동")
    var addressInfo: String = "서울시 중구 명동"
    lateinit var address: String
    var registerState : Boolean = false
    var pastFanPower = 0
    private var db : MaskDB? = null
    var readMSG = ""
    private var fanPowerResponse = ""

    private fun registerChooser() {
        if(registerState){
            viewModel.unregisterReceiver()
            registerState = false
        }else{
            viewModel.registBroadCastReceiver()
            registerState = true
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {

        binding = DataBindingUtil.inflate(inflater, R.layout.frag_home, container,false)
        binding.viewModel = viewModel
        val view = binding.root
        registerState = false
        registerChooser()
        activity?.let { register(
            it) }
        Log.d("data device", "device")
        if (arguments?.getParcelable<BluetoothDevice>("device") != null){
            device = arguments?.getParcelable("device")
            Log.d("data device", "${device}")
            viewModel.connectDevice(device!!)
        }
        db = MaskDB.getInstance(context?.applicationContext!!)

        val r = Runnable {
            val data = db?.MaskDao()?.getAll()
            // 맨 처음으로 실행할 때 데이터가 없으므로 다 0으로 세팅
            if (data!!.size == 0){
                Log.e("First!!", "첫 실행입니다.")
                for (i in 1..7){
                    val firstDB = MaskData(i.toString(), 0.toLong(), 0.toLong(), 0.toLong());
                    db?.MaskDao()?.insert(firstDB)
                }
                val data = db?.MaskDao()?.getAll()
                Log.e("DBDBDB", "${data}")
                Log.e("DBDBDB", "${data?.size}")
            }
        }
        val thread = Thread(r)
        thread.start()

        address = arguments?.getString("address").toString()
        val lat = arguments?.getDouble("latitude")
        val long = arguments?.getDouble("longitude")

        maskFanPower = binding.seekBar
        maskFanPowerText = binding.fanTitle
        disConBtn = binding.disconnectBtn
        disConBtn.setOnClickListener {
            activity?.finishAffinity()
            val intent = Intent(context, DeviceActivity::class.java)
            startActivity(intent)
            System.exit(0)
        }
        val gson = GsonBuilder().setLenient().create()

        val retrofit = Retrofit.Builder()
            .baseUrl("http://apis.data.go.kr")
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

        Log.d("lat/long", "${lat}, ${long}")
        val (tmX, tmY) = setWGS84TM(lat!!, long!!)
        Log.d("TM_XY", "$tmX / $tmY")
        getDustInfo(retrofit, tmX, tmY)


        // 정해진 시간마다 업데이트
        val timer = Timer()
        timer.schedule(object : TimerTask(){
            override fun run() {
                Log.d("getnewloc", "newloc")
                getNewLocation(retrofit)
//                for (i in 1..3){
//                    viewModel.onClickWrite('P')
//                    Log.d("read/", "Read Start")
//                    viewModel.onClickRead()
//                    Log.d("read/", "Read End")
//                }
                // Log.d("read", "readByteArray : ${a.toString()}")
            }
        }, 10000, 3600000)

        Log.d("homeFrag", address.toString())
        binding.locationText.text = address
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireContext())
        binding.refreshBtn.setOnClickListener {
            getNewLocation(retrofit)
        }

        var start: Long = 0
        var end: Long = 0
        // 요일 1~7: 일~토
        var day = ""
        maskFanPower.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(p0: SeekBar?, p1: Int, p2: Boolean) {
                maskFanPowerText.text = "팬 세기\n${p1}"
                when(p1){
                    0 -> {
                        // fan stop
                        if(pastFanPower != 0){
                            // 팬 작동 종료
                            if (pastFanPower != 0){
                                end = System.currentTimeMillis()
                                val r = Runnable {
                                    // update use time (100 부분이 새로 추가될 시간, time은 지금까지 누적된 시간)
                                    val time = db?.MaskDao()?.getTime(day)
                                    // 아래부분은 시간 추가할 때 사하면 될
                                    Log.e("Start and End", "$day / $start / $end")
                                    val updateDB = MaskData(day, start, end.toLong(), end - start + time!!);
                                    db?.MaskDao()?.update(updateDB)
                                    val data = db?.MaskDao()?.getAll()
                                    Log.e("DBDBDB", "${data}")
                                    Log.e("DBDBDB", "${data?.size}")
                                }
                                val thread = Thread(r)
                                thread.start()
                            }
                        }
                        fanPowerIO('N')
                    }
                    1 -> {
                        // fan start
                        // 팬 작동 시작
                        if (pastFanPower == 0){
                            start = System.currentTimeMillis()
                            day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK).toString()
                        }
                        fanPowerIO('A')
                    }
                    2 -> {
                        // fan start
                        if (pastFanPower == 0){
                            start = System.currentTimeMillis()
                            day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK).toString()
                        }
                        fanPowerIO('B')
                    }
                    3 -> {
                        // fan start
                        if (pastFanPower == 0){
                            start = System.currentTimeMillis()
                            day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK).toString()
                        }
                        fanPowerIO('C')
                    }
                    4 -> {
                        // fan start
                        if (pastFanPower == 0){
                            start = System.currentTimeMillis()
                            day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK).toString()
                        }
                        fanPowerIO('D')
                    }

                }
                pastFanPower = p1
            }

            override fun onStartTrackingTouch(p0: SeekBar?) {
            }
            override fun onStopTrackingTouch(p0: SeekBar?) {
            }

        })

        initObserver(binding)
        registerChooser()

        return view
    }

    private fun initObserver(binding: FragHomeBinding?) {
        viewModel.readTxt?.observe(this,{
            val now = System.currentTimeMillis()
            var bstatus = ""
            binding?.txtBattery?.text = bstatus
            val datef = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            val timestamp = datef.format(Date(now))
        })
    }

    fun checkIfFragmentAttached(operation: Context.() -> Unit) {
        if (isAdded && context != null) {
            operation(requireContext())
        }
    }

    // 새로고침 시 새 주소 출력
    private fun getNewLocation(retrofit: Retrofit) {
        checkIfFragmentAttached {
            if (ActivityCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                fusedLocationClient.lastLocation
                    .addOnSuccessListener { location: Location? ->
                        val geocoder = Geocoder(requireContext(), Locale.KOREA)
                        addressInfo = getNewAddress(geocoder, location!!, retrofit)
                        binding.locationText.text = addressInfo
                        Log.d("Clicked refresh button ", addressInfo)
                    }
            }
        }
    }

    // 새 좌표를 이용해 주소 반환
    private fun getNewAddress(geocoder: Geocoder, location: Location, retrofit: Retrofit): String {
        val addrList = geocoder.getFromLocation(location.latitude, location.longitude, 1)
        Log.d("address", "${addrList}")
        for (addr in addrList) {
            val splitedAddr = addr.getAddressLine(0).split(" ")
            addressList = splitedAddr
        }
        addressInfo = "${addressList[addressList.size-3]} ${addressList[addressList.size-2]} ${addressList[addressList.size-1]}"
        // 새 좌표를 다시 TM좌표로 변환 후 미세먼지 치수 다시 가져오기
        val (newTMX, newTMY) = setWGS84TM(location.latitude, location.longitude)
        getDustInfo(retrofit, newTMX, newTMY)
        Log.d("New Information", "$newTMX / $newTMY")

        return addressInfo
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if(registerState) viewModel.unregisterReceiver()
    }

    // 위/경도 -> TM좌표로 변환
    private fun setWGS84TM(lat: Double, lon: Double): Pair<Double, Double> {
        Log.d("TMchanger", lat.toString() + "," + lon.toString())

        val factory = CRSFactory()
        val grs80 = factory.createFromName("EPSG:4326")
        val wgs84 = factory.createFromParameters(
            "goodmask",
            "+proj=tmerc +lat_0=38 +lon_0=127 +k=1 +x_0=200000 +y_0=500000 +ellps=GRS80 +units=m +no_defs"
        )

        val transformer = BasicCoordinateTransform(grs80, wgs84)
        val beforeCoord = ProjCoordinate(lon, lat)
        val afterCoord = ProjCoordinate()

        Log.d("TMchanger", transformer.transform(beforeCoord, afterCoord).toString())
        val tmp = transformer.transform(beforeCoord, afterCoord).toString().substring(15)
        val TM_x = tmp.split(" ")[0].toDouble()
        val TM_y = tmp.split(" ")[1].toDouble()
        val TMInfo = Pair(TM_x, TM_y)
        return TMInfo
    }

    public fun checkLongLatNull(long: Double?, lat:Double?) : Boolean{
        if (long == null || lat == null) return true
        else return false
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    // 가장 가까운 측정소를 구하고 미세먼지 농도 데이터 가져오기
    private fun getDustInfo(retrofit: Retrofit, tmX: Double?, tmY: Double?) {
        val stationService: StationService? = retrofit.create(StationService::class.java)

        val key = "reoV++nM+7IsY4GLfsMfFBjKc/0t6gmgytKyqzrR7DbEaCNasNyCT131Qk2yPuPeC5uQqcHFlt4nWQLhDsnWDw=="
        if (tmX == null || tmY == null){

        }
        stationService?.getInfo(key, "json", tmX!!, tmY!!)
            ?.enqueue(object : Callback<StationInfo> {
                override fun onResponse(call: Call<StationInfo>, response: Response<StationInfo>) {
                    val stationList = response.body()?.response?.body?.items
                    val nearestStationAddress = stationList?.get(0)?.addr
                    val nearestStationName = stationList?.get(0)?.stationName
                    val subStationName = stationList?.get(1)?.stationName
                    Log.d("JSON Test", "가장 가까운 측정소 주소는 $nearestStationAddress, 지역은 $nearestStationName 입니다. 후보 지역은 $subStationName!")

                    getDustNum(retrofit, key, nearestStationName.toString(), subStationName.toString())
                }

                override fun onFailure(call: Call<StationInfo>, t: Throwable) {
                    Log.d("onFailure in Station", t.message!!)
                    Toast.makeText(context, "측정소 정보를 가져올 수 없습니다.", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun register(ctx: Context) {
        LocalBroadcastManager.getInstance(ctx).registerReceiver(
            sendReceiver, IntentFilter("sendMSG")
        )
    }

    fun unRegister(ctx: Context) {
        LocalBroadcastManager.getInstance(ctx).registerReceiver(
            sendReceiver, IntentFilter("sendMSG")
        )
    }

    private val sendReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            readMSG = intent.getStringExtra("msg")!!
            Log.d("read/write/log", "fanPowerResponse : ${readMSG}")
            when(readMSG){
//                "Z" -> binding.txtBattery.text = "0%"
//                "L" -> binding.txtBattery.text = "25%"
//                "M" -> binding.txtBattery.text = "50%"
//                "H" -> binding.txtBattery.text = "75%"
//                "F" -> binding.txtBattery.text = "100%"
                "Y" -> fanPowerResponse = "Y"
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun fanPowerIO(inputData : Char){
        Toast.makeText(context, "데이터 전송중..", Toast.LENGTH_SHORT).show()
        for (i in 1..5){
            viewModel.onClickWrite(inputData)
            Log.d("read/write/log", "Read Start")

            viewModel.onClickRead()

        }
        Log.d("read/write/log", "Read End fanpowerresponse : ${fanPowerResponse}")
        Thread.sleep(3000)
        if(fanPowerResponse == "Y"){
            Toast.makeText(context, "팬세기 데이터 송/수신 : OK", Toast.LENGTH_SHORT).show()
            fanPowerResponse = ""
            return
        }
        Toast.makeText(context, "데이터가 수신되지 않습니다.", Toast.LENGTH_SHORT).show()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun dataToService(status: String){
        // 포그라운드 데이터 전달 위함
        Log.e("Give Address", "데이터 전달 $address")
        Log.e("Home to Service Status", status)
        val intent = Intent(context, BleService::class.java)!!
        intent.putExtra("address", address)
        intent.putExtra("dustStatus", status)

        if (arguments?.getParcelable<BluetoothDevice>("device") != null){
            device = arguments?.getParcelable("device")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context?.startForegroundService(intent)
            if(device != null){
                viewModel.connectDevice(device!!)
            }
        } else{
            context?.startService(intent)
            if(device != null){
                viewModel.connectDevice(device!!)
            }
        }
    }

    // 해당 측정소에서 미세먼지 데이터 가져오기
    private fun getDustNum(retrofit: Retrofit, key: String, stationName: String, subStation: String) {
        val dustService: DustService? = retrofit.create(DustService::class.java)
        dustService?.getInfo(key, "json", stationName, "daily")
            ?.enqueue(object : Callback<DustInfo> {
                @RequiresApi(Build.VERSION_CODES.O)
                override fun onResponse(call: Call<DustInfo>, response: Response<DustInfo>) {
                    val dustList = response.body()?.response?.body?.items
                    val dustNum = dustList?.get(0)?.pm10Value

                    if (dustNum != "-") {
                        Log.d("Dust Num", "미세먼지 농도: $dustNum, 측정소는 $stationName")
                        if (addressInfo == "서울시 중구 명동"){
                            binding.locationText.text = subStation
                        }
                        binding.dust.text = "미세먼지 치수: $dustNum"
                        val status: String = setDustUI(dustNum!!.toInt())
                        // 서비스 클래스로 데이터 전송
                        dataToService(status)
                    }
                    // 가장 가까운 측정소가 점검중일때 다음으로 가까운 측정소에 접근
                    else {
                        dustService.getInfo(key, "json", subStation, "daily")
                            .enqueue(object : Callback<DustInfo> {
                                @RequiresApi(Build.VERSION_CODES.O)
                                override fun onResponse(call: Call<DustInfo>, response: Response<DustInfo>) {
                                    val subDustList = response.body()?.response?.body?.items
                                    val subDustNum = subDustList?.get(0)?.pm10Value
                                    Log.d("Sub Dust Num", "미세먼지 농도: $subDustNum, 측정소는 $subStation")
                                    if (addressInfo == "서울시 중구 명동"){
                                        binding.locationText.text = subStation
                                    }
                                    binding.dust.text = "미세먼지 치수: $subDustNum"
                                    val subStatus = setDustUI(subDustNum!!.toInt())
                                    // 서비스 클래스로 데이텉 전송
                                    dataToService(subStatus)
                                }

                                override fun onFailure(call: Call<DustInfo>, t: Throwable) {
                                    Log.d("onFailure in Sub Dust", t.message!!)
                                    Toast.makeText(context, "미세먼지 정보를 가져오는 중 에러가 발생했습니다. 다시 실행해주세요.", Toast.LENGTH_SHORT).show()
                                }
                            })
                    }
                }

                override fun onFailure(call: Call<DustInfo>, t: Throwable) {
                    Log.d("onFailure in Dust", t.message!!)
                    Toast.makeText(context, "미세먼지 정보를 가져오는 중 에러가 발생했습니다. 다시 실행해주세요.", Toast.LENGTH_SHORT).show()
                }
            })
    }

    // 미세먼지 수치에 따른 UI
    private fun setDustUI(dust: Int): String {
        var status: String?= "status"
        when (dust) {
            in 0..15 -> {
                binding.locationLayout.setBackgroundResource(R.drawable.rounded_skyblue_btn)
                binding.imageView2.setBackgroundResource(R.drawable.smile)
                binding.dust2.text = "매우 좋음"
                status = binding.dust2.text.toString()
            }
            in 16..30 -> {
                binding.locationLayout.setBackgroundResource(R.drawable.rounded_green)
                binding.imageView2.setBackgroundResource(R.drawable.smile)
                binding.dust2.text = "좋음"
                status = binding.dust2.text.toString()
            }
            in 31..80 -> {
                binding.locationLayout.setBackgroundResource(R.drawable.rounded_yellow_btn)
                binding.imageView2.setBackgroundResource(R.drawable.sceptic)
                binding.dust2.text = "보통"
                status = binding.dust2.text.toString()
            }
            in 81..150 -> {
                binding.locationLayout.setBackgroundResource(R.drawable.rounded_orange_btn)
                binding.imageView2.setBackgroundResource(R.drawable.bad)
                binding.dust2.text = "나쁨"
                status = binding.dust2.text.toString()
            }
            else -> {
                binding.locationLayout.setBackgroundResource(R.drawable.rounded_red_btn)
                binding.imageView2.setBackgroundResource(R.drawable.angry)
                binding.dust2.text = "매우 나쁨"
                status = binding.dust2.text.toString()
            }
        }
        return status
    }
}

interface StationService {
    @GET("/B552584/MsrstnInfoInqireSvc/getNearbyMsrstnList")
    fun getInfo(
        @Query("serviceKey") serviceKey: String,
        @Query("returnType") returnType: String,
        @Query("tmX") tmX: Double,
        @Query("tmY") tmY: Double,
    ): Call<StationInfo>
}

interface DustService {
    @GET("/B552584/ArpltnInforInqireSvc/getMsrstnAcctoRltmMesureDnsty")
    fun getInfo(
        @Query("serviceKey") serviceKey: String,
        @Query("returnType") returnType: String,
        @Query("stationName") stationName: String,
        @Query("dataTerm") dataTerm: String
    ): Call<DustInfo>
}