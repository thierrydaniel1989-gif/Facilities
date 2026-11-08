package com.caisse.app;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(NetworkPrinterPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
