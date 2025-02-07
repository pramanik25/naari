package Home_Activity;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;

import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;


import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.example.naarishakti.R;

public class CallPoliceActivity extends AppCompatActivity {
    private static final int REQUEST_CALL = 1;
    private ImageButton callPoliceButton;
    private TextView helpIsComingText;
    private TextView safetyTip;
    private ImageButton cancelButton;
    private String[] safetyTips;
    private int currentTipIndex = 0;
    private Handler tipHandler = new Handler();
    private final long TIP_ROTATION_DELAY = 5000; // 5 seconds

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_call_police);

        callPoliceButton = findViewById(R.id.call_police_button);
        helpIsComingText = findViewById(R.id.help_is_coming_text);
        safetyTip = findViewById(R.id.safety_tip);
        cancelButton = findViewById(R.id.cancel_button);

        // Safety tips array
        safetyTips = new String[]{
                "Remain Calm, Help is on the way!",
                "Speak clearly: share your location and situation details.",
                "If safe, describe the suspect or vehicle to the operator.",
                "Trust your instincts. If unsafe, seek help.",
                "Try to move to a well-lit and populated area."

        };

        callPoliceButton.setOnClickListener(v -> makePhoneCall());
        cancelButton.setOnClickListener(v -> finish());

        // Start rotating tips
        rotateSafetyTips();


    }

    private void makePhoneCall() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CALL_PHONE}, REQUEST_CALL);
        } else {
            String emergencyNumber = "100"; // Replace with your country's emergency number
            Intent callIntent = new Intent(Intent.ACTION_CALL);
            callIntent.setData(Uri.parse("tel:" + emergencyNumber));

            // Show "Help is Coming" and make call button
            helpIsComingText.setVisibility(View.VISIBLE);


            startActivity(callIntent);
        }
    }


    private void rotateSafetyTips() {

        tipHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                safetyTip.setText(safetyTips[currentTipIndex]);
                currentTipIndex = (currentTipIndex + 1) % safetyTips.length;
                rotateSafetyTips(); // Schedule next tip update
            }
        }, TIP_ROTATION_DELAY);





    }



    @Override
        public void onRequestPermissionsResult(int requestCode,  String[] permissions, int[] grantResults) {

            super.onRequestPermissionsResult(requestCode, permissions, grantResults);

            if(requestCode==REQUEST_CALL){
                if(grantResults.length>0 && grantResults[0]== PackageManager.PERMISSION_GRANTED){

                    makePhoneCall();

                }else{
                    Toast.makeText(this, "Permission DENIED", Toast.LENGTH_SHORT).show();
                }




            }else if(requestCode== 100){

                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {



                } else {
                    Toast.makeText(this, "Location permission denied.", Toast.LENGTH_SHORT).show();
                }




            }




        }




    }