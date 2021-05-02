package com.example.pothole_detection;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RawRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.TileOverlay;
import com.google.android.gms.maps.model.TileOverlayOptions;
import com.google.maps.android.heatmaps.Gradient;
import com.google.maps.android.heatmaps.HeatmapTileProvider;
import com.google.maps.android.heatmaps.WeightedLatLng;
import com.opencsv.CSVReader;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.tensorflow.lite.Interpreter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements OnMapReadyCallback,
        GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener,
        LocationListener,
        SensorEventListener {
    ////////////////////////////
    private static final String MODEL_NAME = "updated_binaryclassifier.tflite";
    private SensorManager sensorManager;
    Sensor accelerometer;
    long starttime = 0;
    int interval = 5; //interval = 5 seconds
    int pothole_counter = 0;
    TextView pothole_type, pothole_accuracy;
    double latitude, longitude;
    private Interpreter tflite;
    LocationManager locationManager;
    ArrayList<WeightedLatLng> new_potholes = new ArrayList<>();
    double acc_X, acc_Y, acc_Z, gyro_x, gyro_y, gyro_z, mag_x, mag_y, mag_z, milli_;
    double accMax, accMin, accStd, accZcr, accMean, accVar, gyroMax, gyroMin, gyroStd, gyroZcr, gyroMean, gyroVar;
    private final Interpreter.Options options = new Interpreter.Options();
    FileOutputStream fos = null;
    ////////////////////////////
    private MapView mapView;
    private GoogleMap mMap;
    private static final String MAPVIEW_BUNDLE_KEY = "MapViewBundleKey";
    List<WeightedLatLng> weightedLatLng = new ArrayList<>();
    HeatmapTileProvider provider;
    TileOverlay tileOverlay;
    private GoogleApiClient googleApiClient;
    private LocationRequest locationRequest;
    private Location lastLocation;
    private Marker currentUserLocationMarker;
    // Storage Permissions
    private static final int REQUEST_EXTERNAL_STORAGE = 1;
    private static String[] PERMISSIONS_STORAGE = {
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Bundle mapViewBundle = null;
        if (savedInstanceState != null) {
            mapViewBundle = savedInstanceState.getBundle(MAPVIEW_BUNDLE_KEY);
        }

        try {
            File file = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/pothole_data", "new_data.csv");
            if (file.exists()) {
                fos = new FileOutputStream(file, true);
                //fos.write(("acc_X,acc_Y,acc_Z,gyro_X,gyro_Y,gyro_Z,mag_x, mag_y, mag_z,milli,latitude,longitude,counter,pothole type,label" + "\n").getBytes());
            } else {
                fos = new FileOutputStream(file);
                fos.write(("acc_X,acc_Y,acc_Z,gyro_X,gyro_Y,gyro_Z,mag_x, mag_y, mag_z,milli,latitude,longitude,counter,pothole type,label" + "\n").getBytes());
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            tflite = new Interpreter(loadModelFile(this), options);
        } catch (IOException e) {
            e.printStackTrace();
        }

        RequestSensorData(this);

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,3000,10,this);

        //pothole_accuracy = findViewById(R.id.pothole_accuracy);
        pothole_type = findViewById(R.id.pothole_type);
        verifyStoragePermissions(this);

        mapView = (MapView) findViewById(R.id.map_view);
        mapView.onCreate(mapViewBundle);
        mapView.getMapAsync(this);

    }

    void RequestSensorData(Activity activity)
    {
        Sensor gyroscope;
        Sensor magnetometer;
        sensorManager = (SensorManager)  getSystemService(Context.SENSOR_SERVICE);

        //Request accelerometer values
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        sensorManager.registerListener((SensorEventListener) activity, accelerometer, sensorManager.SENSOR_DELAY_NORMAL);

        // Request gyroscope values
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        sensorManager.registerListener((SensorEventListener) activity, gyroscope, sensorManager.SENSOR_DELAY_NORMAL);

        // Request magnetometer values
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        sensorManager.registerListener((SensorEventListener) activity, magnetometer, sensorManager.SENSOR_DELAY_NORMAL);

    }
    private void addHeatMap(GoogleMap map) {
        int[] colors = {
//                Color.rgb(128, 225, 0), // green
//                Color.rgb(255, 128, 0),    // orange
//                Color.rgb(255, 0, 0),   // red
//                Color.rgb(0, 0, 255)
                Color.rgb(102, 225, 0), // green
                Color.rgb(255, 0, 0)    // red
        };

        float[] startPoints = {
               // 1.0f, 2.0f, 3.0f , 3.9f
                0.2f, 1f
        };

        Gradient gradient = new Gradient(colors, startPoints);
        // Create a heat map tile provider, passing it the latlngs of the police stations.
        provider = new HeatmapTileProvider.Builder()
                .weightedData(weightedLatLng)

                //.radius(50)
                .gradient(gradient)
                .opacity(1.0)
                .build();
        // Add a tile overlay to the map, using the heat map tile provider.
        tileOverlay = map.addTileOverlay(new TileOverlayOptions().tileProvider(provider));
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        mMap.setMyLocationEnabled(true);

//        PlotHeatMap("data_0.csv");
//        PlotHeatMap("data_1.csv");
//        PlotHeatMap("data_2.csv");
//        PlotHeatMap("data_3.csv");
        PlotHeatMap("new_data.csv");
        //googleMap.addMarker((new MarkerOptions().position((new LatLng(0,0))).title("Test")));

        addHeatMap(mMap);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        Bundle mapViewBundle = outState.getBundle(MAPVIEW_BUNDLE_KEY);
        if (mapViewBundle == null) {
            mapViewBundle = new Bundle();
            outState.putBundle(MAPVIEW_BUNDLE_KEY, mapViewBundle);
        }

        mapView.onSaveInstanceState(mapViewBundle);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();

    }

    @Override
    protected void onPause() {
        mapView.onDestroy();
        super.onPause();
    }

    @Override
    public void onStart() {
        super.onStart();
        mapView.onStart();
    }

    @Override
    public void onStop() {
        super.onStop();
       mapView.onStop();
    }

    @Override
    public void onDestroy() {
        mapView.onDestroy();
        super.onDestroy();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mapView.onLowMemory();
    }

    void LogNewPotholes(double lat, double lng, double intensity)
    {

    }
    void PlotHeatMap(String filename)
    {
        int pothole_counter = 1;
        try {
            File file = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/pothole_data", filename);
            CSVReader reader = new CSVReader(new FileReader(file));
            String[] nextLine = reader.readNext();
            if (nextLine != null)
            {
                reader.readNext();
            }
            while ((nextLine = reader.readNext()) != null) {
                // nextLine[] is an array of values from the line
                //System.out.println(nextLine[0] + nextLine[1] + "etc...");
                if(Double.parseDouble(nextLine[13]) > 0)
                //if(Double.parseDouble(nextLine[12]) == pothole_counter)
                {
                    weightedLatLng.add(new WeightedLatLng(new LatLng(Double.parseDouble(nextLine[10]), Double.parseDouble(nextLine[11])), Double.parseDouble(nextLine[13])));
                    double x = Double.parseDouble(nextLine[13]);
                    System.out.println(x + '\n');
                    pothole_counter++;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Checks if the app has permission to write to device storage
     *
     * If the app does not has permission then the user will be prompted to grant permissions
     *
     * @param activity
     */
    public static void verifyStoragePermissions(Activity activity) {
        // Check if we have write permission
        int permission = ActivityCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE);

        if (permission != PackageManager.PERMISSION_GRANTED) {
            // We don't have permission so prompt the user
            ActivityCompat.requestPermissions(
                    activity,
                    PERMISSIONS_STORAGE,
                    REQUEST_EXTERNAL_STORAGE
            );
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        lastLocation = location;

        latitude = location.getLatitude();
        longitude = location.getLongitude();
        if (currentUserLocationMarker != null)
        {
            currentUserLocationMarker.remove();
        }

        LatLng latLng = new LatLng(location.getLatitude(), location.getLongitude());

//        MarkerOptions markerOptions = new MarkerOptions();
//        markerOptions.position(latLng);
//        markerOptions.title("User Current Location");
//        markerOptions.icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN));

//        currentUserLocationMarker = mMap.addMarker(markerOptions);

        mMap.moveCamera(CameraUpdateFactory.newLatLng(latLng));
        mMap.animateCamera(CameraUpdateFactory.zoomBy(14));
        if(googleApiClient != null)
        {
            LocationServices.FusedLocationApi.removeLocationUpdates(googleApiClient, (com.google.android.gms.location.LocationListener) this);

        }
        updateCameraBearing(mMap, location.getBearing());
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {

    }

    @Override
    public void onProviderEnabled(String provider) {

    }

    @Override
    public void onProviderDisabled(String provider) {

    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {

//        locationRequest = new LocationRequest();
//        locationRequest.setInterval(1100);
//        locationRequest.setFastestInterval(1100);
//        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
//
//        if(ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
//            LocationServices.FusedLocationApi.requestLocationUpdates(googleApiClient, locationRequest, (com.google.android.gms.location.LocationListener) this);
//        }
    }
    private void updateCameraBearing(GoogleMap googleMap, float bearing) {
        if ( googleMap == null) return;
        CameraPosition camPos = CameraPosition
                .builder(
                        googleMap.getCameraPosition() // current Camera
                )
                .bearing(bearing)
                .build();
        googleMap.animateCamera(CameraUpdateFactory.newCameraPosition(camPos));
    }
    @Override
    public void onConnectionSuspended(int i) {

    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        long millis = System.currentTimeMillis() - starttime;
        int seconds = (int) (millis / 1000);
        int minutes = seconds / 60;
        seconds = seconds % 60;
        if (seconds % interval == 2) {
            if (sensorEvent.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                acc_X = sensorEvent.values[0];
                acc_Y = sensorEvent.values[1];
                acc_Z = sensorEvent.values[2];

                double[] data_arr = {sensorEvent.values[0], sensorEvent.values[1], sensorEvent.values[2]};
                accMax = maximum(data_arr) / 10;
                accMin = minimum(data_arr) / 10;
                accStd = standardDeviation(data_arr);
                accZcr = zeroCrossingRate(data_arr);
                accMean = mean(data_arr) / 10;
                accVar = variance(data_arr);
            }

            if (sensorEvent.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
                gyro_x = sensorEvent.values[0];
                gyro_y = sensorEvent.values[1];
                gyro_z = sensorEvent.values[2];

                double[] data_arr = {sensorEvent.values[0], sensorEvent.values[1], sensorEvent.values[2]};
                gyroMax = maximum(data_arr);
                gyroMin = minimum(data_arr);
                gyroStd = standardDeviation(data_arr);
                gyroZcr = zeroCrossingRate(data_arr);
                gyroMean = mean(data_arr);
                gyroVar = variance(data_arr);
            }

            float[] input = {(float) accMax, (float) accMin, (float) accStd, (float) accZcr, (float) accMean, (float) accVar, (float) gyroMax, (float) gyroMin, (float) gyroStd, (float) gyroZcr, (float) gyroMean, (float) gyroVar};
            float[][] mResult = new float[1][1];
            try {
                tflite.run(input, mResult);
            }
            catch (Exception e)
            {

            }
            int state = argmax(mResult[0]);
            int classification_res = (int)mResult[0][state];
            //Toast.makeText(getApplicationContext(),"RESULT: " + classification_res,Toast.LENGTH_SHORT).show();

            String mytext = "";
            
                if (classification_res == 0) {
                    mytext = "no pothole";
                } else {
                    mytext = "pothole";
                    writeToCSV(1);
                }
                pothole_type.setText(mytext);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }


    private MappedByteBuffer loadModelFile(Activity activity) throws IOException {
        AssetFileDescriptor fileDescriptor = activity.getAssets().openFd(MODEL_NAME);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    private static int argmax(float[] probs) {
        int maxIdx = -1;
        float maxProb = 0.0f;
        for (int i = 0; i < probs.length; i++) {
            if (probs[i] > maxProb) {
                maxProb = probs[i];
                maxIdx = i;
            }
        }
        return maxIdx;
    }
    public static double mean(double data[]){
        if(data == null || data.length == 0) return
                0.0;
        int length = data.length;
        double Sum = 0;
        for (int i = 0; i < length; i++)
            Sum = Sum + data[i];
        return Sum / length;
    }

    public static double minimum(double data[]){
        if(data == null || data.length == 0) return 0.0;
        int length = data.length;
        double MIN = data[0];
        for (int i = 1; i < length; i++){
            MIN = data[i]<MIN?data[i]:MIN;
        }
        return MIN;
    }
    public static double maximum(double data[]){
        if(data == null || data.length == 0) return 0.0;

        int length = data.length;
        double Max = data[0];
        for (int i = 1; i<length; i++)
            Max = data[i]<Max ? Max : data[i];
        return Max;
    }
    public static double variance(double data[]){
        if(data == null || data.length == 0) return 0.0;
        int length = data.length;
        double average = 0, s = 0, sum = 0;
        for (int i = 0; i<length; i++)
        {
            sum = sum + data[i];
        }
        average = sum / length;
        for (int i = 0; i<length; i++)
        {
            s = s + Math.pow(data[i] - average, 2);
        }
        s = s / length;
        return s;
    }
    public static double standardDeviation(double data[]){
        if(data == null || data.length == 0) return 0.0;
        double s = variance(data);
        s = Math.sqrt(s);
        return s;
    }
    /**æ±‚æ•°ç»„è¿‡é›¶çŽ‡**/
    public static double zeroCrossingRate(double data[]){
        int length = data.length;
        double num = 0;
        for (int i = 0; i < length - 1; i++)
        {
            if (data[i] * data[i + 1]< 0){
                num++;
            }
        }
        return num / length;
    }
    public void writeToCSV(int pothole_type) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            //requestPermission();
            return;
        }

        String littleTest = acc_X + ","
                + acc_Y + ","
                + acc_Z + ","
                + gyro_x + ","
                + gyro_y + ","
                + gyro_z + ","
                + mag_x + ","
                + mag_y + ","
                + mag_z + ","
                + milli_ + ","
                + latitude + ","
                + longitude + "," +
                + ++pothole_counter + "," +
                + pothole_type + "\n";;

                weightedLatLng.add(new WeightedLatLng(new LatLng(latitude,longitude),pothole_type));
                //tileOverlay.remove();
                provider.setWeightedData(weightedLatLng);
        try {
            fos.write(littleTest.getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}