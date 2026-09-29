package com.example.naarishakti.escape;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.example.naarishakti.R;

/** Alarm target (ring), notification action target (schedule in N s) and "Decline" handler. */
public class FakeCallReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (FakeCall.ACTION_RING.equals(action)) {
            String name = intent.getStringExtra(FakeCall.EXTRA_NAME);
            if (name == null || name.isEmpty()) name = FakeCall.callerName(context);
            FakeCall.onAlarm(context, name, intent.getLongExtra(FakeCall.EXTRA_TOKEN, -1));
        } else if (FakeCall.ACTION_SCHEDULE.equals(action)) {
            int delay = Math.max(0, intent.getIntExtra(FakeCall.EXTRA_DELAY, 10));
            FakeCall.schedule(context, delay, null);
            Toast.makeText(context.getApplicationContext(),
                    context.getString(R.string.en_fake_call_scheduled, delay), Toast.LENGTH_SHORT).show();
        } else if (FakeCall.ACTION_DECLINE.equals(action)) {
            FakeCall.dismiss(context);
        }
    }
}
