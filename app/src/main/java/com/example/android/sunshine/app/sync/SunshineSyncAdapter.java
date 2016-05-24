package com.example.android.sunshine.app.sync;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SyncRequest;
import android.content.SyncResult;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.TaskStackBuilder;
import android.text.format.Time;
import android.util.Log;

import com.example.android.sunshine.app.BuildConfig;
import com.example.android.sunshine.app.MainActivity;
import com.example.android.sunshine.app.R;
import com.example.android.sunshine.app.Utility;
import com.example.android.sunshine.app.data.WeatherContract;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Vector;

/**
 * 所有的网络操作都在这个类中
 * 两个事:
 *          从网络中 获取数据,解析json
 *          把相应的数据 存放起来
 *
 */
public class SunshineSyncAdapter extends AbstractThreadedSyncAdapter {
    private static final String TAG                     = "SunshineSyncAdapter";
    // Interval at which to sync with the weather, in seconds.
    // 60 seconds (1 minute) * 180 = 3 hours
    public static final  int    SYNC_INTERVAL           = 60 * 180; // 同步间隔时间 三个小时
    public static final  int    SYNC_FLEXTIME           = SYNC_INTERVAL / 3;  // 一个小时
    private static final long   DAY_IN_MILLIS           = 1000 * 60 * 60 * 24;  // 一天中的毫秒数
    private static final int    WEATHER_NOTIFICATION_ID = 3004;


    private static final String[] NOTIFY_WEATHER_PROJECTION = new String[]{
            WeatherContract.WeatherEntry.COLUMN_WEATHER_ID,
            WeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
            WeatherContract.WeatherEntry.COLUMN_MIN_TEMP,
            WeatherContract.WeatherEntry.COLUMN_SHORT_DESC
    };

    // these indices must match the projection
    private static final int INDEX_WEATHER_ID = 0;
    private static final int INDEX_MAX_TEMP   = 1;
    private static final int INDEX_MIN_TEMP   = 2;
    private static final int INDEX_SHORT_DESC = 3;

    public SunshineSyncAdapter(Context context, boolean autoInitialize) {
        super(context, autoInitialize);
    }

    // 这是一个操作网络的方法
    @Override
    public void onPerformSync(Account account, Bundle extras, String authority, ContentProviderClient provider, SyncResult syncResult) {
        Log.d(TAG, "Starting sync");
        String locationQuery = Utility.getPreferredLocation(getContext());
        Log.d(TAG, "onPerformSync: " + locationQuery);

        // These two need to be declared outside the try/catch
        // so that they can be closed in the finally block.
        HttpURLConnection urlConnection = null;
        BufferedReader reader = null;

        // Will contain the raw JSON response as a string.
        String forecastJsonStr = null;

        String format = "json";
        String units = "metric";
        int numDays = 14;

        try {
            // Construct the URL for the OpenWeatherMap query
            // Possible parameters are avaiable at OWM's forecast API page, at
            // http://openweathermap.org/API#forecast
            // 封装获取网络的常量
            final String FORECAST_BASE_URL =
                    "http://api.openweathermap.org/data/2.5/forecast/daily?";
            final String QUERY_PARAM = "q";
            final String FORMAT_PARAM = "mode";
            final String UNITS_PARAM = "units";
            final String DAYS_PARAM = "cnt";
            final String APPID_PARAM = "APPID";

            // 进行拼接 API
            Uri builtUri = Uri.parse(FORECAST_BASE_URL)
                              .buildUpon()
                              .appendQueryParameter(QUERY_PARAM, locationQuery)
                              .appendQueryParameter(FORMAT_PARAM, format)
                              .appendQueryParameter(UNITS_PARAM, units)
                              .appendQueryParameter(DAYS_PARAM, Integer.toString(numDays))
                              .appendQueryParameter(APPID_PARAM,
                                                    BuildConfig.OPEN_WEATHER_MAP_API_KEY)
                              .build();

            Log.d(TAG, "URI的地址: " + builtUri.toString());
            URL url = new URL(builtUri.toString());

            // Create the request to OpenWeatherMap, and open the connection 进行网络请求
            urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setRequestMethod("GET");
            urlConnection.connect();

            // Read the input stream into a String 输入流获取请求的内容
            InputStream inputStream = urlConnection.getInputStream();
            StringBuffer buffer = new StringBuffer();
            if (inputStream == null) {
                // Nothing to do.
                return;
            }
            reader = new BufferedReader(new InputStreamReader(inputStream));

            String line;
            while ((line = reader.readLine()) != null) {
                // Since it's JSON, adding a newline isn't necessary (it won't affect parsing)
                // But it does make debugging a *lot* easier if you print out the completed
                // buffer for debugging.
                buffer.append(line + "\n");
            }

            if (buffer.length() == 0) {
                // Stream was empty.  No point in parsing.
                return;
            }
            forecastJsonStr = buffer.toString();
            Log.d(TAG, "forecastJsonStr: " + forecastJsonStr);

            // 调用方法 转换数据
            getWeatherDataFromJson(forecastJsonStr, locationQuery);
        } catch (IOException e) {
            Log.e(TAG, "Error ", e);
            // If the code didn't successfully get the weather data, there's no point in attempting
            // to parse it.
        } catch (JSONException e) {
            Log.e(TAG, e.getMessage(), e);
            e.printStackTrace();
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
            if (reader != null) {
                try {
                    reader.close();
                } catch (final IOException e) {
                    Log.e(TAG, "Error closing stream", e);
                }
            }
        }
        return;
    }

    /**
     * Take the String representing the complete forecast in JSON Format and
     * pull out the data we need to construct the Strings needed for the wireframes.
     * 拿到json数据以后进行转换， 在构造函数里去调用相关的转换逻辑 转换成一个entity（bean）
     * <p/>
     * Fortunately parsing is easy:  constructor takes the JSON string and converts it
     * into an Object hierarchy for us.
     */
    private void getWeatherDataFromJson(String forecastJsonStr,
                                        String locationSetting)
            throws JSONException {

        // Now we have a String representing the complete forecast in JSON Format.
        // Fortunately parsing is easy:  constructor takes the JSON string and converts it
        // into an Object hierarchy for us.

        // These are the names of the JSON objects that need to be extracted.
        // 下面所封装的是 json对象所需要提取的关键字

        // Location information   位置信息
        final String OWM_CITY = "city"; // 城市名
        final String OWM_CITY_NAME = "name"; // 名称
        final String OWM_COORD = "coord";  // 坐标

        // Location coordinate  经纬度
        final String OWM_LATITUDE = "lat";
        final String OWM_LONGITUDE = "lon";

        // Weather information.  Each day's forecast info is an element of the "list" array.
        final String OWM_LIST = "list";  // 天气的具体信息列表 的 key

        final String OWM_PRESSURE = "pressure";
        final String OWM_HUMIDITY = "humidity";
        final String OWM_WINDSPEED = "speed";
        final String OWM_WIND_DIRECTION = "deg";

        // All temperatures are children of the "temp" object.
        final String OWM_TEMPERATURE = "temp";
        final String OWM_MAX = "max";
        final String OWM_MIN = "min";

        final String OWM_WEATHER = "weather";
        final String OWM_DESCRIPTION = "main";
        final String OWM_WEATHER_ID = "id";

        try {
            // 拿到JsonObj的对象， 里面附带的是 json数据
            JSONObject forecastJson = new JSONObject(forecastJsonStr);
            // json对象去调用 getJsonArray拿到json里的数组 拿到里面的value 保存成一个 json数组的对象
            JSONArray weatherArray = forecastJson.getJSONArray(OWM_LIST);

            // 拿到城市名
            JSONObject cityJson = forecastJson.getJSONObject(OWM_CITY);
            // 城市名对象调用getString 根据key 拿到里面的value
            String cityName = cityJson.getString(OWM_CITY_NAME);
            Log.d(TAG, "cityName: " + cityName);

            // 根据城市名 拿到对象， 让对象去拿里面的 key 地理位置
            JSONObject cityCoord = cityJson.getJSONObject(OWM_COORD);
            double cityLatitude = cityCoord.getDouble(OWM_LATITUDE);
            double cityLongitude = cityCoord.getDouble(OWM_LONGITUDE);

            long locationId = addLocation(locationSetting, cityName, cityLatitude, cityLongitude);

            // Insert the new weather information into the database
            // 把数据保存到数据库里 每次获取数据时 有时间限制 可以避免多次进行网络操作
            // TODO: 2016/5/24  为什么要用Vector
            Vector<ContentValues> cVVector = new Vector<ContentValues>(weatherArray.length());

            // OWM returns daily forecasts based upon the local time of the city that is being
            // asked for, which means that we need to know the GMT offset to translate this data
            // properly.

            // Since this data is also sent in-order and the first day is always the
            // current day, we're going to take advantage of that to get a nice
            // normalized UTC date for all of our weather.

            // 拿到time 对象
            Time dayTime = new Time();
            dayTime.setToNow();  // 拿到当前的时间

            // we start at the day returned by local time. Otherwise this is a mess.
            int julianStartDay = Time.getJulianDay(System.currentTimeMillis(), dayTime.gmtoff);

            // now we work exclusively in UTC  以UTC时间为准
            dayTime = new Time();

            for (int i = 0; i < weatherArray.length(); i++) {
                // These are the values that will be collected.
                // 定义value里面需要取得值 的 变量

                long dateTime;
                double pressure;
                int humidity;
                double windSpeed;
                double windDirection;

                double high;
                double low;

                String description;
                int weatherId;

                // Get the JSON object representing the day
                // 拿到这一天中 json对象里的具体数据
                JSONObject dayForecast = weatherArray.getJSONObject(i);
                // Cheating to convert this to UTC time, which is what we want anyhow
                // 把日期转换成了需要的日期   打印log的时候 拿到了：1465185600000 1秒= 10亿纳秒
                dateTime = dayTime.setJulianDay(julianStartDay + i);
                Log.d(TAG, "被转换后的dateTime: " + dateTime);

                // 拿到压力
                pressure = dayForecast.getDouble(OWM_PRESSURE);
                // 湿度
                humidity = dayForecast.getInt(OWM_HUMIDITY);
                // 风速
                windSpeed = dayForecast.getDouble(OWM_WINDSPEED);
                // 风向
                windDirection = dayForecast.getDouble(OWM_WIND_DIRECTION);

                // Description is in a child array called "weather", which is 1 element long.
                // That element also contains a weather code.
                // 调用数据对象里面的weather 的key 拿到里面的第一个元素 得到weather对象
                JSONObject weatherObject =
                        dayForecast.getJSONArray(OWM_WEATHER)
                                   .getJSONObject(0);
                // 用weather对象 拿到里面的 description的key
                description = weatherObject.getString(OWM_DESCRIPTION);
                Log.d(TAG, "description: " + description);
                // weather对象 拿到对应的ID
                weatherId = weatherObject.getInt(OWM_WEATHER_ID);
                Log.d(TAG, "weatherId: " + weatherId);

                // Temperatures are in a child object called "temp".  Try not to name variables
                // "temp" when working with temperature.  It confuses everybody.
                // 根据 "temp" key 拿到里面的value  通过气温的对象 获得里面的高温和低温
                JSONObject temperatureObject = dayForecast.getJSONObject(OWM_TEMPERATURE);
                high = temperatureObject.getDouble(OWM_MAX);
                low = temperatureObject.getDouble(OWM_MIN);

                // 基本取到了需要的数据 需要 content provider
                ContentValues weatherValues = new ContentValues();
                // 把获取到的所有的数据 都放到了 map集合里
                weatherValues.put(WeatherContract.WeatherEntry.COLUMN_LOC_KEY, locationId);
                weatherValues.put(WeatherContract.WeatherEntry.COLUMN_DATE, dateTime);
                weatherValues.put(WeatherContract.WeatherEntry.COLUMN_HUMIDITY, humidity);
                weatherValues.put(WeatherContract.WeatherEntry.COLUMN_PRESSURE, pressure);
                weatherValues.put(WeatherContract.WeatherEntry.COLUMN_WIND_SPEED, windSpeed);
                weatherValues.put(WeatherContract.WeatherEntry.COLUMN_DEGREES, windDirection);
                weatherValues.put(WeatherContract.WeatherEntry.COLUMN_MAX_TEMP, high);
                weatherValues.put(WeatherContract.WeatherEntry.COLUMN_MIN_TEMP, low);
                weatherValues.put(WeatherContract.WeatherEntry.COLUMN_SHORT_DESC, description);
                weatherValues.put(WeatherContract.WeatherEntry.COLUMN_WEATHER_ID, weatherId);

                // 把map值 放到 vector对象里
                cVVector.add(weatherValues);
            }

            int inserted = 0;
            // add to database 遍历 vector对象 把里面的数据取出来 存到 数据库里
            if (cVVector.size() > 0) {
                // 拿到长度
                ContentValues[] cvArray = new ContentValues[cVVector.size()];
                // 变成数组对象
                cVVector.toArray(cvArray);
                // 存key和value
                getContext().getContentResolver()
                            .bulkInsert(WeatherContract.WeatherEntry.CONTENT_URI, cvArray);

                // delete old data so we don't build up an endless history
                // 删除数据库里面的旧的数据 找到key， 定义数据库的操作语句， 所对应要删除的数据
                getContext().getContentResolver()
                            .delete(WeatherContract.WeatherEntry.CONTENT_URI,
                                    WeatherContract.WeatherEntry.COLUMN_DATE + " <= ?", //使用数据库语句操作
                                    new String[]{Long.toString(
                                            dayTime.setJulianDay(julianStartDay - 1))});

                // 通知view更新数据
                notifyWeather();
            }

            Log.d(TAG, "Sync Complete. " + cVVector.size() + " Inserted");

        } catch (JSONException e) {
            Log.e(TAG, e.getMessage(), e);
            e.printStackTrace();
        }
    }

    /**
     * 通知view更新数据
     */
    private void notifyWeather() {
        Context context = getContext();
        //checking the last update and notify if it' the first of the day
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String displayNotificationsKey = context.getString(R.string.pref_enable_notifications_key);
        boolean displayNotifications = prefs.getBoolean(displayNotificationsKey,
                                                        Boolean.parseBoolean(context.getString(
                                                                R.string.pref_enable_notifications_default)));

        if (displayNotifications) {

            //拿到最后更新的日期
            String lastNotificationKey = context.getString(R.string.pref_last_notification);
            Log.d(TAG, "lastNotificationKey: " + lastNotificationKey);
            long lastSync = prefs.getLong(lastNotificationKey, 0); // 最后同步时间

            // 当前时间 -  最近一次同步的时间 大于 1天
            if (System.currentTimeMillis() - lastSync >= DAY_IN_MILLIS) {
                // Last sync was more than 1 day ago, let's send a notification with the weather.
                String locationQuery = Utility.getPreferredLocation(context);

                Uri weatherUri = WeatherContract.WeatherEntry.buildWeatherLocationWithDate(
                        locationQuery, System.currentTimeMillis());
                Log.d(TAG, "weatherUri: " + weatherUri.toString());

                // we'll query our contentProvider, as always 遍历数据库拿到cursor对象
                Cursor cursor = context.getContentResolver()
                                       .query(weatherUri, NOTIFY_WEATHER_PROJECTION, null, null,
                                              null);

                if (cursor.moveToFirst()) {
                    int weatherId = cursor.getInt(INDEX_WEATHER_ID);
                    double high = cursor.getDouble(INDEX_MAX_TEMP);
                    double low = cursor.getDouble(INDEX_MIN_TEMP);
                    String desc = cursor.getString(INDEX_SHORT_DESC);

                    // 根据weatherid 去判断 需要展示哪一个天气图标
                    int iconId = Utility.getIconResourceForWeatherCondition(weatherId);
                    Resources resources = context.getResources();
                    // 解码一个图片 拿到bitmap对象
                    Bitmap largeIcon = BitmapFactory.decodeResource(resources,
                                                                    Utility.getArtResourceForWeatherCondition(
                                                                            weatherId));
                    // 拿到应用名
                    String title = context.getString(R.string.app_name);

                    // Define the text of the forecast.  自己定义的展示数据的格式
                    // 进行数据的格式化 才能正确显示
                    String contentText = String.format(
                            context.getString(R.string.format_notification),
                            desc,
                            Utility.formatTemperature(context, high),
                            Utility.formatTemperature(context, low));

                    // NotificationCompatBuilder is a very convenient way to build backward-compatible
                    // notifications.  Just throw in some data.
                    // 通知显示当前的数据
                    NotificationCompat.Builder mBuilder =
                            new NotificationCompat.Builder(getContext())
                                    .setColor(resources.getColor(R.color.sunshine_light_blue))
                                    .setSmallIcon(iconId)
                                    .setLargeIcon(largeIcon)
                                    .setContentTitle(title)
                                    .setContentText(contentText);

                    // Make something interesting happen when the user clicks on the notification.
                    // In this case, opening the app is sufficient.
                    Intent resultIntent = new Intent(context, MainActivity.class);

                    // The stack builder object will contain an artificial back stack for the
                    // started Activity.
                    // This ensures that navigating backward from the Activity leads out of
                    // your application to the Home screen.
                    // 当按了导航键 返回到桌面时  会构建一个通知在下拉栏里
                    TaskStackBuilder stackBuilder = TaskStackBuilder.create(context);
                    stackBuilder.addNextIntent(resultIntent);
                    PendingIntent resultPendingIntent =
                            stackBuilder.getPendingIntent(
                                    0,
                                    PendingIntent.FLAG_UPDATE_CURRENT
                            );
                    mBuilder.setContentIntent(resultPendingIntent);

                    NotificationManager mNotificationManager =
                            (NotificationManager) getContext().getSystemService(
                                    Context.NOTIFICATION_SERVICE);
                    // WEATHER_NOTIFICATION_ID allows you to update the notification later on.
                    // 根据id 去重新构建一次 通知
                    mNotificationManager.notify(WEATHER_NOTIFICATION_ID, mBuilder.build());

                    //refreshing last sync 记住上一次的更新时间 和 当前系统时间
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putLong(lastNotificationKey, System.currentTimeMillis());
                    editor.commit();
                }
                cursor.close();
            }
        }
    }

    /**
     * Helper method to handle insertion of a new location in the weather database.
     *
     * @param locationSetting The location string used to request updates from the server.
     * @param cityName        A human-readable city name, e.g "Mountain View"
     * @param lat             the latitude of the city
     * @param lon             the longitude of the city
     * @return the row ID of the added location.
     */
    long addLocation(String locationSetting, String cityName, double lat, double lon) {
        long locationId;

        // First, check if the location with this city name exists in the db
        // 检查一下 当前传入的location 和 记住的location 是否匹配
        Cursor locationCursor = getContext().getContentResolver()
                                            .query(
                                                    WeatherContract.LocationEntry.CONTENT_URI,
                                                    new String[]{WeatherContract.LocationEntry._ID},
                                                    WeatherContract.LocationEntry.COLUMN_LOCATION_SETTING + " = ?",
                                                    new String[]{locationSetting},
                                                    null);

        if (locationCursor.moveToFirst()) {
            int locationIdIndex = locationCursor.getColumnIndex(WeatherContract.LocationEntry._ID);
            locationId = locationCursor.getLong(locationIdIndex);
        } else {
            // Now that the content provider is set up, inserting rows of data is pretty simple.
            // First create a ContentValues object to hold the data you want to insert.
            // 创建contentValues的对象 用来保存数据
            ContentValues locationValues = new ContentValues();

            // Then add the data, along with the corresponding name of the data type,
            // 添加数据, 根据相应的key 存 value
            // so the content provider knows what kind of value is being inserted.
            // content provider 知道 如何去存匹配的数据
            locationValues.put(WeatherContract.LocationEntry.COLUMN_CITY_NAME, cityName);
            locationValues.put(WeatherContract.LocationEntry.COLUMN_LOCATION_SETTING,
                               locationSetting);
            locationValues.put(WeatherContract.LocationEntry.COLUMN_COORD_LAT, lat);
            locationValues.put(WeatherContract.LocationEntry.COLUMN_COORD_LONG, lon);

            // Finally, insert location data into the database.
            // 把数据信息 插入到 数据库里
            Uri insertedUri = getContext().getContentResolver()
                                          .insert(WeatherContract.LocationEntry.CONTENT_URI,
                                                  locationValues
                                          );

            // The resulting URI contains the ID for the row.  Extract the locationId from the Uri.
            // 分析提取uri里面所携带的数据
            locationId = ContentUris.parseId(insertedUri);
        }

        locationCursor.close();
        // Wait, that worked?  Yes! 返回解析出来的LocationId 就可以了
        return locationId;
    }

    /**
     * Helper method to schedule the sync adapter periodic execution
     */
    public static void configurePeriodicSync(Context context, int syncInterval, int flexTime) {
        Account account = getSyncAccount(context);
        String authority = context.getString(R.string.content_authority);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            // we can enable inexact timers in our periodic sync
            SyncRequest request = new SyncRequest.Builder().syncPeriodic(syncInterval, flexTime)
                                                           .setSyncAdapter(account, authority)
                                                           .setExtras(new Bundle())
                                                           .build();
            ContentResolver.requestSync(request);
        } else {
            ContentResolver.addPeriodicSync(account,
                                            authority, new Bundle(), syncInterval);
        }
    }

    /**
     * Helper method to have the sync adapter sync immediately
     * 立刻同步数据
     *
     * @param context The context used to access the account service
     */
    public static void syncImmediately(Context context) {
        Bundle bundle = new Bundle();
        bundle.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
        bundle.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
        ContentResolver.requestSync(getSyncAccount(context),
                                    context.getString(R.string.content_authority), bundle);
    }

    /**
     * Helper method to get the fake account to be used with SyncAdapter, or make a new one
     * if the fake account doesn't exist yet.  If we make a new account, we call the
     * onAccountCreated method so we can initialize things.
     *
     * @param context The context used to access the account service
     * @return a fake account.
     */
    public static Account getSyncAccount(Context context) {
        // Get an instance of the Android account manager
        AccountManager accountManager =
                (AccountManager) context.getSystemService(Context.ACCOUNT_SERVICE);

        // Create the account type and default account
        Account newAccount = new Account(
                context.getString(R.string.app_name),
                context.getString(R.string.sync_account_type));

        // If the password doesn't exist, the account doesn't exist
        if (null == accountManager.getPassword(newAccount)) {

        /*
         * Add the account and account type, no password or user data
         * If successful, return the Account object, otherwise report an error.
         */
            if (!accountManager.addAccountExplicitly(newAccount, "", null)) {
                return null;
            }
            /*
             * If you don't set android:syncable="true" in
             * in your <provider> element in the manifest,
             * then call ContentResolver.setIsSyncable(account, AUTHORITY, 1)
             * here.
             */

            onAccountCreated(newAccount, context);
        }
        return newAccount;
    }

    private static void onAccountCreated(Account newAccount, Context context) {
        /*
         * Since we've created an account
         */
        SunshineSyncAdapter.configurePeriodicSync(context, SYNC_INTERVAL, SYNC_FLEXTIME);

        /*
         * Without calling setSyncAutomatically, our periodic sync will not be enabled.
         */
        ContentResolver.setSyncAutomatically(newAccount,
                                             context.getString(R.string.content_authority), true);

        /*
         * Finally, let's do a sync to get things started
         */
        syncImmediately(context);
    }

    public static void initializeSyncAdapter(Context context) {
        getSyncAccount(context);
    }
}