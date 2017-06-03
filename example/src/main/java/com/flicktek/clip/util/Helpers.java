package com.flicktek.clip.util;

import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;
import android.util.Log;

import com.flicktek.clip.MainActivity;
import com.flicktek.example.R;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;

public class Helpers {
    private static final String TAG = "Helpers";

    @Nullable
    public static JSONObject getJsonFromResources(MainActivity main, String json_resource_name) {
        Resources res= main.getApplicationContext().getResources();
        int resource = res.getIdentifier(json_resource_name, "raw",
                main.getPackageName());

        InputStream is = res.openRawResource(resource);

        Writer writer = new StringWriter();
        char[] buffer = new char[1024];
        Reader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));

            int n;
            while ((n = reader.read(buffer)) != -1) {
                writer.write(buffer, 0, n);
            }
            is.close();

        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            return null;
        } catch (IOException e) {
            e.printStackTrace();
            Log.e(TAG, "Failed reading JSON");
            return null;
        }

        String json = writer.toString();
        JSONObject jsonObj = null;
        try {
            jsonObj = new JSONObject(json);
        } catch (JSONException e) {
            e.printStackTrace();
            Log.e(TAG, "Failed parsing JSON");
        }
        return jsonObj;
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Nullable
    public static Drawable getDrawableFromResourcesByName(MainActivity main,
                                                    String drawable_name,
                                                    JSONObject config) {
        Resources res = main.getResources();

        int res_id = res.getIdentifier(drawable_name, "drawable", main.getPackageName());
        Log.d(TAG, "ID " + res_id);

        // The resource doesn't exist?, use a X
        if (res_id == 0) {
            res_id = R.drawable.ic_clear_white;
        }

        Drawable draw_image = res.getDrawable(res_id, main.getTheme());
        return draw_image;
    }

    @Nullable
    public static Drawable getDrawableFromResources(MainActivity main,
                                                    String drawable_name,
                                                    JSONObject config) {

        if (config == null) {
            return main.getResources().getDrawable(R.drawable.ic_clear_white, main.getTheme());
        }

        // You can specify a package, by default if there is no package we assume it is
        // an internal resource.
        String package_name = new String();
        try {

            package_name = config.getString("package");
        } catch (JSONException ex) {
            Log.d(TAG, "Use default tag " + ex);
        }

        String drawable = null;
        try {
            drawable = config.getString(drawable_name);
        } catch (JSONException e) {
            e.printStackTrace();
            return null;
        }

        Resources res = main.getResources();

        int res_id;
        if (package_name.length() == 0)
            res_id = res.getIdentifier(drawable, "drawable", main.getPackageName());
        else
            res_id = res.getIdentifier(drawable, "drawable", package_name);

        Log.d(TAG, "ID " + res_id);

        // The resource doesn't exist?, use a X
        if (res_id == 0) {
            res_id = R.drawable.ic_clear_white;
        }

        Drawable draw_image = res.getDrawable(res_id, main.getTheme());
        return draw_image;
    }
}
