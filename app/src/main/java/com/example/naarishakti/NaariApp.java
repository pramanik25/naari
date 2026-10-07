package com.example.naarishakti;

import android.app.Application;

import com.example.naarishakti.cloud.Cloud;
import com.example.naarishakti.core.Appearance;
import com.example.naarishakti.core.Kv;
import com.example.naarishakti.cloud.CloudModule;
import com.example.naarishakti.core.SafetyHooks;
import com.example.naarishakti.daily.DailyNudge;
import com.example.naarishakti.evidence.EvidenceModule;
import com.example.naarishakti.mesh.MeshModule;
import com.example.naarishakti.triggers.TriggerModule;

/** Wires the feature modules into the SOS engine once per process. */
public class NaariApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Kv.init(this); // storage first: everything below reads settings
        Appearance.apply(this);
        Cloud.init(this);
        // Order matters only for logging; each module is isolated by SafetyHooks.
        SafetyHooks.register(new CloudModule());
        SafetyHooks.register(new EvidenceModule());
        SafetyHooks.register(new TriggerModule());
        SafetyHooks.register(new MeshModule());
        DailyNudge.sync(this);
    }
}