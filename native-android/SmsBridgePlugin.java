package com.spendlens.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Settings;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.util.regex.Pattern;

@CapacitorPlugin(
    name = "SmsBridge",
    permissions = {
        @Permission(
            alias = "sms",
            strings = {
                Manifest.permission.READ_SMS,
                Manifest.permission.RECEIVE_SMS
            }
        )
    }
)
public class SmsBridgePlugin extends Plugin {

    private static final Pattern OTP_FILTER = Pattern.compile(
        "\\b(otp|one time password|verification code|cvv|mpin|secret code|do not share)\\b",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern BANK_HINT = Pattern.compile(
        "\\b(debited|spent|paid|sent|purchased|purchase|charged|withdrawn|payment of|rs\\.?|inr|₹)\\b",
        Pattern.CASE_INSENSITIVE
    );

    private BroadcastReceiver smsReceiver;

    @Override
    public void load() {
        smsReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || !Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) {
                    return;
                }
                SmsMessage[] msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent);
                if (msgs == null || msgs.length == 0) return;

                String sender = msgs[0].getOriginatingAddress();
                StringBuilder bodyBuilder = new StringBuilder();
                long timestamp = msgs[0].getTimestampMillis();
                for (SmsMessage m : msgs) {
                    if (m.getMessageBody() != null) {
                        bodyBuilder.append(m.getMessageBody());
                    }
                }
                String body = bodyBuilder.toString();
                if (OTP_FILTER.matcher(body).find() || !BANK_HINT.matcher(body).find()) {
                    return;
                }

                JSObject payload = new JSObject();
                payload.put("sender", sender != null ? sender : "");
                payload.put("body", body);
                payload.put("date", timestamp);
                notifyListeners("smsReceived", payload);
            }
        };

        IntentFilter filter = new IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION);
        ContextCompat.registerReceiver(
            getContext(),
            smsReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        );
    }

    @Override
    protected void handleOnDestroy() {
        if (smsReceiver != null) {
            try {
                getContext().unregisterReceiver(smsReceiver);
            } catch (Exception ignored) {}
        }
        super.handleOnDestroy();
    }

    @PluginMethod
    public void checkSmsPermission(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("granted", getPermissionState("sms") == PermissionState.GRANTED);
        call.resolve(ret);
    }

    @PluginMethod
    public void requestSmsPermission(PluginCall call) {
        if (getPermissionState("sms") == PermissionState.GRANTED) {
            JSObject ret = new JSObject();
            ret.put("granted", true);
            call.resolve(ret);
        } else {
            requestPermissionForAlias("sms", call, "smsPermsCallback");
        }
    }

    @PermissionCallback
    private void smsPermsCallback(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("granted", getPermissionState("sms") == PermissionState.GRANTED);
        call.resolve(ret);
    }

    @PluginMethod
    public void openAppSettings(PluginCall call) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", getContext().getPackageName(), null));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(intent);
        call.resolve();
    }

    @PluginMethod
    public void scanRecentSms(PluginCall call) {
        if (getPermissionState("sms") != PermissionState.GRANTED) {
            call.reject("SMS permission not granted");
            return;
        }

        int days = call.getInt("days", 30);
        long cutoff = System.currentTimeMillis() - ((long) days * 24L * 60L * 60L * 1000L);

        JSArray messages = new JSArray();
        Cursor cursor = null;
        try {
            cursor = getContext().getContentResolver().query(
                Telephony.Sms.Inbox.CONTENT_URI,
                new String[]{"address", "body", "date"},
                "date >= ?",
                new String[]{String.valueOf(cutoff)},
                "date DESC"
            );

            int count = 0;
            if (cursor != null) {
                int addrIdx = cursor.getColumnIndex("address");
                int bodyIdx = cursor.getColumnIndex("body");
                int dateIdx = cursor.getColumnIndex("date");

                while (cursor.moveToNext() && count < 500) {
                    String sender = addrIdx >= 0 ? cursor.getString(addrIdx) : "";
                    String body = bodyIdx >= 0 ? cursor.getString(bodyIdx) : "";
                    long date = dateIdx >= 0 ? cursor.getLong(dateIdx) : System.currentTimeMillis();

                    if (body == null || OTP_FILTER.matcher(body).find() || !BANK_HINT.matcher(body).find()) {
                        continue;
                    }

                    JSObject obj = new JSObject();
                    obj.put("sender", sender != null ? sender : "");
                    obj.put("body", body);
                    obj.put("date", date);
                    messages.put(obj);
                    count++;
                }
            }
        } catch (Exception e) {
            call.reject("Failed to scan SMS: " + e.getMessage());
            return;
        } finally {
            if (cursor != null) cursor.close();
        }

        JSObject ret = new JSObject();
        ret.put("messages", messages);
        call.resolve(ret);
    }
}
