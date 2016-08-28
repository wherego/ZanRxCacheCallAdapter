package com.youzan.mobile.sample;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;

import com.youzan.mobile.rxcacheadapter.cache.ZanLocalCache;

import java.io.File;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ZanLocalCache.init(getCacheDir().getAbsolutePath() + File.separator + "/http_cache");
    }
}
