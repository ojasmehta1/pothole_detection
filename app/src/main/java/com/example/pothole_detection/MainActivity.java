package com.example.pothole_detection;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Environment;
import android.widget.TextView;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.TileOverlay;
import com.google.android.gms.maps.model.TileOverlayOptions;
import com.google.maps.android.heatmaps.Gradient;
import com.google.maps.android.heatmaps.HeatmapTileProvider;
import com.google.maps.android.heatmaps.WeightedLatLng;
import com.opencsv.CSVReader;

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
import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity implements OnMapReadyCallback,
        LocationListener,
        SensorEventListener {
    ////////////////////////////
    private static final String MODEL_NAME = "updated_binaryclassifier.tflite";
    private SensorManager sensorManager;
    Sensor accelerometer;
    int pothole_counter = 0;
    TextView pothole_type;
    double latitude, longitude;
    private Interpreter tflite;
    LocationManager locationManager;
    double acc_X, acc_Y, acc_Z, gyro_x, gyro_y, gyro_z, mag_x, mag_y, mag_z, milli_;
    private final Interpreter.Options options = new Interpreter.Options();
    FileOutputStream fos = null;
    ////////////////////////////
    private MapView mapView;
    private GoogleMap mMap;
    private static final String MAPVIEW_BUNDLE_KEY = "MapViewBundleKey";
    List<WeightedLatLng> weightedLatLng = new ArrayList<>();
    HeatmapTileProvider provider;
    TileOverlay tileOverlay;
    private double[] accdata = new double[50];
    private int acccount = 0;
    private double[] gyrodata = new double[50];
    private int gyrocount = 0;
    private int lastElapsedSec = -1;
    //Permissions
    private static final int REQUEST_STORAGE = 1;
    private static final int REQUEST_LOCATION = 2;
    private static String[] PERMISSIONS_STORAGE = {
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };
    private static String[] PERMISSIONS_LOCATION = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
    };

    @SuppressLint("MissingPermission")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        //Verify storage, and location permissions
        verifyStoragePermissions(this);
        Bundle mapViewBundle = null;
        if (savedInstanceState != null) {
            mapViewBundle = savedInstanceState.getBundle(MAPVIEW_BUNDLE_KEY);
        }

        //attach mapview, and pothole textview
        pothole_type = findViewById(R.id.pothole_type);
        mapView = (MapView) findViewById(R.id.map_view);

        //Fetch the previous data collected by the app
        try {
            File file = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/pothole_data", "new_data.csv");
            if (file.exists()) {
                fos = new FileOutputStream(file, true);
            } else {
                fos = new FileOutputStream(file);
                fos.write(("acc_X,acc_Y,acc_Z,gyro_X,gyro_Y,gyro_Z,mag_x, mag_y, mag_z,milli,latitude,longitude,counter,pothole type,label" + "\n").getBytes());
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        //Load the tflite model
        try {
            tflite = new Interpreter(loadModelFile(this), options);
        } catch (IOException e) {
            e.printStackTrace();
        }
        //Request data from accelerometer, gyroscope
        RequestSensorData(this);

        //Request location updates every 500 ms, 5 meters
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,500,5,this);
        mapView.onCreate(mapViewBundle);
        mapView.getMapAsync(this);


    }

    void RequestSensorData(Activity activity)
    {
        Sensor gyroscope;
        sensorManager = (SensorManager)  getSystemService(Context.SENSOR_SERVICE);

        //Request accelerometer values
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        sensorManager.registerListener((SensorEventListener) activity, accelerometer, sensorManager.SENSOR_DELAY_NORMAL);

        // Request gyroscope values
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        sensorManager.registerListener((SensorEventListener) activity, gyroscope, sensorManager.SENSOR_DELAY_NORMAL);

    }
    private void addHeatMap(GoogleMap map) {

        //RGB values for pothole color
        int[] colors = {
//                Color.rgb(128, 225, 0), // green
//                Color.rgb(255, 128, 0),    // orange
//                Color.rgb(255, 0, 0),   // red
//                Color.rgb(0, 0, 255)
                //Color.rgb(255, 0, 0), // green
                Color.rgb(255, 0, 0)    // red
        };

        //Starting point for those colors
        float[] startPoints = {
               // 1.0f, 2.0f, 3.0f , 3.9f
                0.2f
        };

        //Attach it to a gradient
        Gradient gradient = new Gradient(colors, startPoints);
        // Create a heat map tile provider, passing it the latlngs of the police stations.
        provider = new HeatmapTileProvider.Builder()
                .weightedData(weightedLatLng)
                .radius(10)
                .gradient(gradient)
                .opacity(1.0)
                .build();
        // Add a tile overlay to the map, using the heat map tile provider.
        tileOverlay = map.addTileOverlay(new TileOverlayOptions().tileProvider(provider));
    }

    @SuppressLint("MissingPermission")
    @Override
    public void onMapReady(GoogleMap googleMap) {
        //Update global variable mMap
        mMap = googleMap;
        mMap.setMyLocationEnabled(true);

        //Plot previously collected datapoints
        PlotHeatMap("data_0.csv");
        PlotHeatMap("data_1.csv");
        PlotHeatMap("data_2.csv");
        PlotHeatMap("data_3.csv");
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

    void PlotHeatMap(String filename)
    {
        //Parse previously collected data from the csv file, and store it in a WeightedLatLng object that will be assigned to the heatmap
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
        // Check if we have storage permissions
        int write_permission = ActivityCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        int read_permission = ActivityCompat.checkSelfPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE);
        // Check if we have location permissions
        int coarse_location_permission = ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION);
        int fine_location_permission= ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION);

        if ((coarse_location_permission != PackageManager.PERMISSION_GRANTED) || (fine_location_permission != PackageManager.PERMISSION_GRANTED)){
            // We don't have permission so prompt the user
            ActivityCompat.requestPermissions(
                    activity,
                    PERMISSIONS_LOCATION,
                    REQUEST_LOCATION
            );
        }
        if ((write_permission != PackageManager.PERMISSION_GRANTED) || (read_permission != PackageManager.PERMISSION_GRANTED)){
            // We don't have permission so prompt the user
            ActivityCompat.requestPermissions(
                    activity,
                    PERMISSIONS_STORAGE,
                    REQUEST_STORAGE
            );
        }

    }

    @Override
    public void onLocationChanged(Location location) {
        //Update global variables
        latitude = location.getLatitude();
        longitude = location.getLongitude();

        LatLng latLng = new LatLng(location.getLatitude(), location.getLongitude());
        mMap.moveCamera(CameraUpdateFactory.newLatLng(latLng));
        mMap.animateCamera(CameraUpdateFactory.zoomBy(14));

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
    public void onSensorChanged(SensorEvent sensorEvent) {
        long millis = System.currentTimeMillis();
        int seconds = (int) (millis / 1000);
        seconds = seconds % 60;
        double magnitude = -1;
            if (sensorEvent.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                acc_X = sensorEvent.values[0];
                acc_Y = sensorEvent.values[1];
                acc_Z = sensorEvent.values[2];
                magnitude = Math.sqrt(Math.pow(acc_X,2)+Math.pow(acc_Y,2)+Math.pow(acc_Z,2));
            }
            if (sensorEvent.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
                gyro_x = sensorEvent.values[0];
                gyro_y = sensorEvent.values[1];
                gyro_z = sensorEvent.values[2];
                magnitude = Math.sqrt(Math.pow(gyro_x,2)+Math.pow(gyro_y,2)+Math.pow(gyro_z,2));
            }

        if ((seconds % 1 == 0 || gyrocount == 50 || acccount == 50) && seconds != lastElapsedSec && gyrocount > 0 && acccount > 0) {
            double accMin = minimum(accdata) / 10;
            double accMax = maximum(accdata) / 10;
            double accStd = standardDeviation(accdata);
            double accZcr = zeroCrossingRate(accdata);
            double accMean = mean(accdata) / 10;
            double accVar = variance(accdata);

            double gyroMin = minimum(gyrodata);
            double gyroMax = maximum(gyrodata);
            double gyroStd = standardDeviation(gyrodata);
            double gyroZcr = zeroCrossingRate(gyrodata);
            double gyroMean = mean(gyrodata);
            double gyroVar = variance(gyrodata);

            float[] input = {(float) accMax, (float) accMin, (float) accStd, (float) accZcr, (float) accMean, (float) accVar, (float) gyroMax, (float) gyroMin, (float) gyroStd, (float) gyroZcr, (float) gyroMean, (float) gyroVar};
            String littleTest = accMax + ","
                    + accMin + ","
                    + accStd + ","
                    + accZcr + ","
                    + accMean + ","
                    + accVar + "\n";
            System.out.println(littleTest);
            float[][] mResult = new float[1][1];
            try {
                tflite.run(input, mResult);
            } catch (Exception e) {

            }
            int state = argmax(mResult[0]);
            if (mResult[0] != null && state < mResult[0].length && state != -1) {
                int classification_res = (int) mResult[0][state];
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
            lastElapsedSec = seconds;
            Arrays.fill(accdata, 0.0);
            Arrays.fill(gyrodata, 0.0);
            gyrocount = 0;
            acccount = 0;
        }
        else if(sensorEvent.sensor.getType() == Sensor.TYPE_GYROSCOPE && gyrocount < 50){
            gyrodata[gyrocount]=magnitude;
            gyrocount++;
        }
        else if(sensorEvent.sensor.getType() == Sensor.TYPE_ACCELEROMETER && acccount < 50){
            accdata[acccount]=magnitude;
            acccount++;
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
