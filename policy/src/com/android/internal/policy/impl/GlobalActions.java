/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.policy.impl;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.server.PowerSaverService;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.app.ShutdownThread;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyProperties;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Helper to show the global actions dialog.  Each item is an {@link Action} that
 * may show depending on whether the keyguard is showing, and whether the device
 * is provisioned.
 */
class GlobalActions implements DialogInterface.OnDismissListener, DialogInterface.OnClickListener  {

    private static final String TAG = "SYSTEM :GlobalActions";
    private static final boolean SHOW_SILENT_TOGGLE = true;

    private final Context mContext;
    private final AudioManager mAudioManager;
    private ArrayList<Action> mItems;
    private AlertDialog mDialog;

    private SilentModeAction mSilentModeAction;
    private ToggleAction mAirplaneModeOn;
    private ToggleAction mPowerSaverOn;
    private MyAdapter mAdapter;

    private boolean mKeyguardShowing = false;
    private boolean mDeviceProvisioned = false;
    private ToggleAction.State mAirplaneState = ToggleAction.State.Off;
    private boolean mIsWaitingForEcmExit = false;
    private boolean mEnablePowerSaverToggle = false;
    private boolean mEnableScreenshotToggle = false;
    private boolean mEnableEasterEggToggle = false;
    private boolean mShowFullscreenMode = false;

    /**
     * @param context everything needs a context :(
     */
    public GlobalActions(Context context) {
        mContext = context;
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);

        // receive broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(TelephonyIntents.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED);
        context.registerReceiver(mBroadcastReceiver, filter);

        // get notified of phone state changes
        TelephonyManager telephonyManager =
                (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        telephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_SERVICE_STATE);
    }

    /**
     * Show the global actions dialog (creating if necessary)
     * @param keyguardShowing True if keyguard is showing
     */
    public void showDialog(boolean keyguardShowing, boolean isDeviceProvisioned) {
        mKeyguardShowing = keyguardShowing;
        mDeviceProvisioned = isDeviceProvisioned;

        if(mDialog != null)
            mDialog.dismiss();
        //always update the PowerMenu dialog
        mDialog = createDialog();

        prepareDialog();

        mDialog.show();
        mDialog.getWindow().getDecorView().setSystemUiVisibility(View.STATUS_BAR_DISABLE_EXPAND);
    }

    /**
     * Create the global actions dialog.
     * @return A new dialog.
     */
    private AlertDialog createDialog() {
        mEnablePowerSaverToggle = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.POWER_DIALOG_SHOW_POWER_SAVER, 0) == 1;
        
        mEnableScreenshotToggle = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.POWER_DIALOG_SHOW_SCREENSHOT, 1) == 1;

        mEnableEasterEggToggle = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.POWER_DIALOG_SHOW_EASTER_EGG, 0) == 1;

        mShowFullscreenMode = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.POWER_DIALOG_SHOW_FULLSCREEN, 1) == 1;

        //debugging
        if (mEnablePowerSaverToggle) {Log.d(TAG, "PowerSaver enabled");}else{Log.d(TAG, "PowerSaver disabled");}
        if (mEnableScreenshotToggle) {Log.d(TAG, "Screenshot enabled");}else{Log.d(TAG, "Screenshot disabled");}
        if (mEnableEasterEggToggle) {Log.d(TAG, "EasterEgg enabled");}else{Log.d(TAG, "EasterEgg disabled");}
        if (mShowFullscreenMode){Log.d(TAG, "Fullscreen enabled");}else{Log.d(TAG, "Fullscreen disabled");}

        mSilentModeAction = new SilentModeAction(mAudioManager, mHandler);

        mAirplaneModeOn = new ToggleAction(
                R.drawable.ic_lock_airplane_mode,
                R.drawable.ic_lock_airplane_mode_off,
                R.string.global_actions_toggle_airplane_mode,
                R.string.global_actions_airplane_mode_on_status,
                R.string.global_actions_airplane_mode_off_status) {

            void onToggle(boolean on) {
                if (Boolean.parseBoolean(
                        SystemProperties.get(TelephonyProperties.PROPERTY_INECM_MODE))) {
                    mIsWaitingForEcmExit = true;
                    // Launch ECM exit dialog
                    Intent ecmDialogIntent =
                            new Intent(TelephonyIntents.ACTION_SHOW_NOTICE_ECM_BLOCK_OTHERS, null);
                    ecmDialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    mContext.startActivity(ecmDialogIntent);
                } else {
                    changeAirplaneModeSystemSetting(on);
                }
            }

            @Override
            protected void changeStateFromPress(boolean buttonOn) {
                // In ECM mode airplane state cannot be changed
                if (!(Boolean.parseBoolean(
                        SystemProperties.get(TelephonyProperties.PROPERTY_INECM_MODE)))) {
                    mState = buttonOn ? State.TurningOn : State.TurningOff;
                    mAirplaneState = mState;
                }
            }

            public boolean showDuringKeyguard() {
                return true;
            }

            public boolean showBeforeProvisioning() {
                return false;
            }
        };
        
        mPowerSaverOn = new ToggleAction(
                R.drawable.ic_lock_power_saver,
                R.drawable.ic_lock_power_saver,
                R.string.global_actions_toggle_power_saver,
                R.string.global_actions_power_saver_on_status,
                R.string.global_actions_power_saver_off_status) {

            void onToggle(boolean on) {
                Settings.Secure.putInt(mContext.getContentResolver(),
                        Settings.Secure.POWER_SAVER_MODE,
                         on ? PowerSaverService.POWER_SAVER_MODE_ON
                                : PowerSaverService.POWER_SAVER_MODE_OFF);
            }

            public boolean showDuringKeyguard() {
                return true;
            }

            public boolean showBeforeProvisioning() {
                return false;
            }
        };
        
        mItems = new ArrayList<Action>();

        // first: power off
        mItems.add(
            new SinglePressAction(
                    com.android.internal.R.drawable.ic_lock_power_off,
                    R.string.global_action_power_off) {

                public void onPress() {
                    // shutdown by making sure radio and power are handled accordingly.
                    ShutdownThread.shutdown(mContext, true);
                }

                public boolean showDuringKeyguard() {
                    return true;
                }

                public boolean showBeforeProvisioning() {
                    return true;
                }
            });
        
        // next: reboot
        mItems.add(
                new SinglePressAction(com.android.internal.R.drawable.ic_lock_reboot,
                        R.string.global_action_reboot) {
                    public void onPress() {
                        ShutdownThread.reboot(mContext, "null", true);
                    }

                    public boolean showDuringKeyguard() {
                        return true;
                    }

                    public boolean showBeforeProvisioning() {
                        return true;
                    }
                });

        // next: airplane mode
        mItems.add(mAirplaneModeOn);

        // next: full screen
        final int onOff = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.POWER_DIALOG_FULLSCREEN, 0);
        int name = 0;
        int icon = 0;

        // TODO UPDATE ICONS!!!
        switch (onOff) {
            case 0:
                name = R.string.global_actions_fullscreen_title_off;
                icon = com.android.internal.R.drawable.ic_lock_nyandroid;
            break;
            case 1:
                name = R.string.global_actions_fullscreen_title_on;
                icon = com.android.internal.R.drawable.ic_lock_nyandroid;
            break;
        }
        if (mShowFullscreenMode) {
            mItems.add(
                    new SinglePressAction(icon, name) {
                        public void onPress() {
                            // just set the int and allow PhoneWindowManager to do the work
                            if (onOff == 1) {
                                Settings.System.putInt(mContext.getContentResolver(),
                                        Settings.System.POWER_DIALOG_FULLSCREEN, 0);
                            } else {
                                Settings.System.putInt(mContext.getContentResolver(),
                                        Settings.System.POWER_DIALOG_FULLSCREEN, 1);
                            }
                        }

                        public boolean showDuringKeyguard() {
                            return true;
                        }

                        public boolean showBeforeProvisioning() {
                            return true;
                        }
                    });
        } else {
            Log.d(TAG, "POWERMENU: not adding fullscreen");
        }

        // next: power saver
        try {
            Settings.Secure.getInt(mContext.getContentResolver(),
                    Settings.Secure.POWER_SAVER_MODE);
            if(mEnablePowerSaverToggle) {
                Log.d(TAG, "Adding powersaver");
                mItems.add(mPowerSaverOn); 
            } else {
                Log.d(TAG, "not adding power saver");
            }
        } catch (SettingNotFoundException e) {
            //Power Saver hasn't yet been initialized so we don't want to make it easy for the user without
            //  them reading any warnings that could be presented by enabling the power saver through ROM Control
        }

        // next: screenshot
        if (mEnableScreenshotToggle) {
            Log.d(TAG, "Adding screenshot");
            mItems.add(new SinglePressAction(com.android.internal.R.drawable.ic_lock_screenshot,
                    R.string.global_action_screenshot) {
                public void onPress() {
                    takeScreenshot();
                }

                public boolean showDuringKeyguard() {
                    return true;
                }

                public boolean showBeforeProvisioning() {
                    return true;
                }
            });
        } else {
            Log.d(TAG, "Not adding screenshot");
        }

        // next: easter egg shortcut
        if (mEnableEasterEggToggle) {
            Log.d(TAG, "Adding easter egg");
            mItems.add(new SinglePressAction(com.android.internal.R.drawable.ic_lock_nyandroid,
                    R.string.global_action_easter_egg) {
                public void onPress() {
                    Log.d(TAG, "easter egg pressed");
                    try {
                        mContext.startActivity(new Intent(Intent.ACTION_MAIN).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                        .setClassName("com.android.systemui","com.android.systemui.Nyandroid"));
                    } catch (ActivityNotFoundException ex) {
                        Log.e(TAG, "Unable to start easter egg");
                    }
                }

                public boolean showDuringKeyguard() {
                    return true;
                }

                public boolean showBeforeProvisioning() {
                    return true;
                }
            });
        } else {
            Log.d(TAG, "Not adding easter egg");
        }

        // last: silent mode
        if (SHOW_SILENT_TOGGLE) {
            mItems.add(mSilentModeAction);
        }

        mAdapter = new MyAdapter();
        final AlertDialog.Builder ab = new AlertDialog.Builder(mContext);
        ab.setAdapter(mAdapter, this).setInverseBackgroundForced(true);

        final AlertDialog dialog = ab.create();
        dialog.getListView().setItemsCanFocus(true);
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);

        dialog.setOnDismissListener(this);
        return dialog;
    }
    
    /**
    * functions needed for taking screenhots.  
    * This leverages the built in ICS screenshot functionality 
    */
   final Object mScreenshotLock = new Object();
   ServiceConnection mScreenshotConnection = null;

   final Runnable mScreenshotTimeout = new Runnable() {
       @Override public void run() {
           synchronized (mScreenshotLock) {
               if (mScreenshotConnection != null) {
                   mContext.unbindService(mScreenshotConnection);
                   mScreenshotConnection = null;
               }
           }
       }
   };

   private void takeScreenshot() {
       synchronized (mScreenshotLock) {
           if (mScreenshotConnection != null) {
               return;
           }
           ComponentName cn = new ComponentName("com.android.systemui",
                   "com.android.systemui.screenshot.TakeScreenshotService");
           Intent intent = new Intent();
           intent.setComponent(cn);
           ServiceConnection conn = new ServiceConnection() {
               @Override
               public void onServiceConnected(ComponentName name, IBinder service) {
                   synchronized (mScreenshotLock) {
                       if (mScreenshotConnection != this) {
                           return;
                       }
                       Messenger messenger = new Messenger(service);
                       Message msg = Message.obtain(null, 1);
                       final ServiceConnection myConn = this;
                       Handler h = new Handler(mHandler.getLooper()) {
                           @Override
                           public void handleMessage(Message msg) {
                               synchronized (mScreenshotLock) {
                                   if (mScreenshotConnection == myConn) {
                                       mContext.unbindService(mScreenshotConnection);
                                       mScreenshotConnection = null;
                                       mHandler.removeCallbacks(mScreenshotTimeout);
                                   }
                               }
                           }
                       };
                       msg.replyTo = new Messenger(h);
                       msg.arg1 = msg.arg2 = 0;                   

                       /* wait for the dislog box to close */
                       try {
                           Thread.sleep(1000); 
                       } catch (InterruptedException ie) {
                       }
                       
                       /* take the screenshot */
                       try {
                           messenger.send(msg);
                       } catch (RemoteException e) {
                       }
                   }
               }
               @Override
               public void onServiceDisconnected(ComponentName name) {}
           };
           if (mContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)) {
               mScreenshotConnection = conn;
               mHandler.postDelayed(mScreenshotTimeout, 10000);
           }
       }
   }

    private void prepareDialog() {
        final boolean silentModeOn =
                mAudioManager.getRingerMode() != AudioManager.RINGER_MODE_NORMAL;
        mAirplaneModeOn.updateState(mAirplaneState);

        mAdapter.notifyDataSetChanged();
        if (mKeyguardShowing) {
            mDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        } else {
            mDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);
        }
        if (SHOW_SILENT_TOGGLE) {
            IntentFilter filter = new IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION);
            mContext.registerReceiver(mRingerModeReceiver, filter);
        }
        final boolean powerSaverOn = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.POWER_SAVER_MODE, PowerSaverService.POWER_SAVER_MODE_OFF) == PowerSaverService.POWER_SAVER_MODE_ON;
        mPowerSaverOn.updateState(powerSaverOn ? ToggleAction.State.On : ToggleAction.State.Off);
    }

    /** {@inheritDoc} */
    public void onDismiss(DialogInterface dialog) {
        if (SHOW_SILENT_TOGGLE) {
            mContext.unregisterReceiver(mRingerModeReceiver);
        }
    }

    /** {@inheritDoc} */
    public void onClick(DialogInterface dialog, int which) {
        if (!(mAdapter.getItem(which) instanceof SilentModeAction)) {
            dialog.dismiss();
        }
        mAdapter.getItem(which).onPress();
    }

    /**
     * The adapter used for the list within the global actions dialog, taking
     * into account whether the keyguard is showing via
     * {@link GlobalActions#mKeyguardShowing} and whether the device is provisioned
     * via {@link GlobalActions#mDeviceProvisioned}.
     */
    private class MyAdapter extends BaseAdapter {

        public int getCount() {
            int count = 0;

            for (int i = 0; i < mItems.size(); i++) {
                final Action action = mItems.get(i);

                if (mKeyguardShowing && !action.showDuringKeyguard()) {
                    continue;
                }
                if (!mDeviceProvisioned && !action.showBeforeProvisioning()) {
                    continue;
                }
                count++;
            }
            return count;
        }

        @Override
        public boolean isEnabled(int position) {
            return getItem(position).isEnabled();
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        public Action getItem(int position) {

            int filteredPos = 0;
            for (int i = 0; i < mItems.size(); i++) {
                final Action action = mItems.get(i);
                if (mKeyguardShowing && !action.showDuringKeyguard()) {
                    continue;
                }
                if (!mDeviceProvisioned && !action.showBeforeProvisioning()) {
                    continue;
                }
                if (filteredPos == position) {
                    return action;
                }
                filteredPos++;
            }

            throw new IllegalArgumentException("position " + position
                    + " out of range of showable actions"
                    + ", filtered count=" + getCount()
                    + ", keyguardshowing=" + mKeyguardShowing
                    + ", provisioned=" + mDeviceProvisioned);
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            Action action = getItem(position);
            return action.create(mContext, convertView, parent, LayoutInflater.from(mContext));
        }
    }

    // note: the scheme below made more sense when we were planning on having
    // 8 different things in the global actions dialog.  seems overkill with
    // only 3 items now, but may as well keep this flexible approach so it will
    // be easy should someone decide at the last minute to include something
    // else, such as 'enable wifi', or 'enable bluetooth'

    /**
     * What each item in the global actions dialog must be able to support.
     */
    private interface Action {
        View create(Context context, View convertView, ViewGroup parent, LayoutInflater inflater);

        void onPress();

        /**
         * @return whether this action should appear in the dialog when the keygaurd
         *    is showing.
         */
        boolean showDuringKeyguard();

        /**
         * @return whether this action should appear in the dialog before the
         *   device is provisioned.
         */
        boolean showBeforeProvisioning();

        boolean isEnabled();
    }

    /**
     * A single press action maintains no state, just responds to a press
     * and takes an action.
     */
    private static abstract class SinglePressAction implements Action {
        private final int mIconResId;
        private final int mMessageResId;

        protected SinglePressAction(int iconResId, int messageResId) {
            mIconResId = iconResId;
            mMessageResId = messageResId;
        }

        public boolean isEnabled() {
            return true;
        }

        abstract public void onPress();

        public View create(
                Context context, View convertView, ViewGroup parent, LayoutInflater inflater) {
            View v = inflater.inflate(R.layout.global_actions_item, parent, false);

            ImageView icon = (ImageView) v.findViewById(R.id.icon);
            TextView messageView = (TextView) v.findViewById(R.id.message);

            v.findViewById(R.id.status).setVisibility(View.GONE);

            icon.setImageDrawable(context.getResources().getDrawable(mIconResId));
            messageView.setText(mMessageResId);

            return v;
        }
    }

    /**
     * A toggle action knows whether it is on or off, and displays an icon
     * and status message accordingly.
     */
    private static abstract class ToggleAction implements Action {

        enum State {
            Off(false),
            TurningOn(true),
            TurningOff(true),
            On(false);

            private final boolean inTransition;

            State(boolean intermediate) {
                inTransition = intermediate;
            }

            public boolean inTransition() {
                return inTransition;
            }
        }

        protected State mState = State.Off;

        // prefs
        protected int mEnabledIconResId;
        protected int mDisabledIconResid;
        protected int mMessageResId;
        protected int mEnabledStatusMessageResId;
        protected int mDisabledStatusMessageResId;

        /**
         * @param enabledIconResId The icon for when this action is on.
         * @param disabledIconResid The icon for when this action is off.
         * @param essage The general information message, e.g 'Silent Mode'
         * @param enabledStatusMessageResId The on status message, e.g 'sound disabled'
         * @param disabledStatusMessageResId The off status message, e.g. 'sound enabled'
         */
        public ToggleAction(int enabledIconResId,
                int disabledIconResid,
                int essage,
                int enabledStatusMessageResId,
                int disabledStatusMessageResId) {
            mEnabledIconResId = enabledIconResId;
            mDisabledIconResid = disabledIconResid;
            mMessageResId = essage;
            mEnabledStatusMessageResId = enabledStatusMessageResId;
            mDisabledStatusMessageResId = disabledStatusMessageResId;
        }

        /**
         * Override to make changes to resource IDs just before creating the
         * View.
         */
        void willCreate() {
        }

        public View create(Context context, View convertView, ViewGroup parent,
                LayoutInflater inflater) {
            willCreate();

            View v = inflater.inflate(R
                            .layout.global_actions_item, parent, false);

            ImageView icon = (ImageView) v.findViewById(R.id.icon);
            TextView messageView = (TextView) v.findViewById(R.id.message);
            TextView statusView = (TextView) v.findViewById(R.id.status);
            final boolean enabled = isEnabled();

            if (messageView != null) {
                messageView.setText(mMessageResId);
                messageView.setEnabled(enabled);
            }

            boolean on = ((mState == State.On) || (mState == State.TurningOn));
            if (icon != null) {
                icon.setImageDrawable(context.getResources().getDrawable(
                        (on ? mEnabledIconResId : mDisabledIconResid)));
                icon.setEnabled(enabled);
            }

            if (statusView != null) {
                statusView.setText(on ? mEnabledStatusMessageResId : mDisabledStatusMessageResId);
                statusView.setVisibility(View.VISIBLE);
                statusView.setEnabled(enabled);
            }
            v.setEnabled(enabled);

            return v;
        }

        public final void onPress() {
            if (mState.inTransition()) {
                Log.w(TAG, "shouldn't be able to toggle when in transition");
                return;
            }
            final boolean nowOn = !(mState == State.On);
            onToggle(nowOn);
            changeStateFromPress(nowOn);
        }

        public boolean isEnabled() {
            return !mState.inTransition();
        }

        /**
         * Implementations may override this if their state can be in on of the intermediate
         * states until some notification is received (e.g airplane mode is 'turning off' until
         * we know the wireless connections are back online
         * @param buttonOn Whether the button was turned on or off
         */
        protected void changeStateFromPress(boolean buttonOn) {
            mState = buttonOn ? State.On : State.Off;
        }

        abstract void onToggle(boolean on);

        public void updateState(State state) {
            mState = state;
        }
    }

    private static class SilentModeAction implements Action, View.OnClickListener {

        private final int[] ITEM_IDS = { R.id.option1, R.id.option2, R.id.option3 };

        private final AudioManager mAudioManager;
        private final Handler mHandler;

        SilentModeAction(AudioManager audioManager, Handler handler) {
            mAudioManager = audioManager;
            mHandler = handler;
        }

        private int ringerModeToIndex(int ringerMode) {
            // They just happen to coincide
            return ringerMode;
        }

        private int indexToRingerMode(int index) {
            // They just happen to coincide
            return index;
        }

        public View create(Context context, View convertView, ViewGroup parent,
                LayoutInflater inflater) {
            View v = inflater.inflate(R.layout.global_actions_silent_mode, parent, false);

            int selectedIndex = ringerModeToIndex(mAudioManager.getRingerMode());
            for (int i = 0; i < 3; i++) {
                View itemView = v.findViewById(ITEM_IDS[i]);
                itemView.setSelected(selectedIndex == i);
                // Set up click handler
                itemView.setTag(i);
                itemView.setOnClickListener(this);
            }
            return v;
        }

        public void onPress() {
        }

        public boolean showDuringKeyguard() {
            return true;
        }

        public boolean showBeforeProvisioning() {
            return false;
        }

        public boolean isEnabled() {
            return true;
        }

        void willCreate() {
        }

        public void onClick(View v) {
            if (!(v.getTag() instanceof Integer)) return;

            int index = (Integer) v.getTag();
            mAudioManager.setRingerMode(indexToRingerMode(index));
            mHandler.sendEmptyMessageDelayed(MESSAGE_DISMISS, DIALOG_DISMISS_DELAY);
        }
    }

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)
                    || Intent.ACTION_SCREEN_OFF.equals(action)) {
                String reason = intent.getStringExtra(PhoneWindowManager.SYSTEM_DIALOG_REASON_KEY);
                if (!PhoneWindowManager.SYSTEM_DIALOG_REASON_GLOBAL_ACTIONS.equals(reason)) {
                    mHandler.sendEmptyMessage(MESSAGE_DISMISS);
                }
            } else if (TelephonyIntents.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED.equals(action)) {
                // Airplane mode can be changed after ECM exits if airplane toggle button
                // is pressed during ECM mode
                if (!(intent.getBooleanExtra("PHONE_IN_ECM_STATE", false)) &&
                        mIsWaitingForEcmExit) {
                    mIsWaitingForEcmExit = false;
                    changeAirplaneModeSystemSetting(true);
                }
            }
        }
    };

    PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        @Override
        public void onServiceStateChanged(ServiceState serviceState) {
            final boolean inAirplaneMode = serviceState.getState() == ServiceState.STATE_POWER_OFF;
            mAirplaneState = inAirplaneMode ? ToggleAction.State.On : ToggleAction.State.Off;
            mAirplaneModeOn.updateState(mAirplaneState);
            mAdapter.notifyDataSetChanged();
        }
    };

    private BroadcastReceiver mRingerModeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(AudioManager.RINGER_MODE_CHANGED_ACTION)) {
                mHandler.sendEmptyMessage(MESSAGE_REFRESH);
            }
        }
    };

    private static final int MESSAGE_DISMISS = 0;
    private static final int MESSAGE_REFRESH = 1;
    private static final int DIALOG_DISMISS_DELAY = 50; // ms

    private Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            if (msg.what == MESSAGE_DISMISS) {
                if (mDialog != null) {
                    mDialog.dismiss();
                }
            } else if (msg.what == MESSAGE_REFRESH) {
                mAdapter.notifyDataSetChanged();
            }
        }
    };

    /**
     * Change the airplane mode system setting
     */
    private void changeAirplaneModeSystemSetting(boolean on) {
        Settings.System.putInt(
                mContext.getContentResolver(),
                Settings.System.AIRPLANE_MODE_ON,
                on ? 1 : 0);
        Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra("state", on);
        mContext.sendBroadcast(intent);
    }
}

