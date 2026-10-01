package ge.kadastr.savele;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(NmeaBluetoothPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
