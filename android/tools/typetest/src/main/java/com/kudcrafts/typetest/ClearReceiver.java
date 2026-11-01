package com.kudcrafts.typetest;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ClearReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        MainActivity a = MainActivity.current;
        if (a != null) a.runOnUiThread(a::clear);
    }
}
